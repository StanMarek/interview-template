# Zero-Downtime Deployment Platform -- Architecture Design

## Requirements
### Functional
- Deploy application updates with zero dropped requests, zero error spikes, zero user-visible impact
- Support multiple deployment strategies: rolling update, blue-green, canary, feature flags
- Automated canary analysis: compare new version metrics against baseline, auto-promote or auto-rollback
- One-click (or automatic) rollback to previous version
- Pre-deployment checks: health, schema compatibility, config validation
- Post-deployment verification: synthetic tests, SLO checks
- GitOps-driven: desired state in git, controller reconciles
- Deploy history and audit log

### Non-Functional
- Deploy 50+ services per day across the organization
- Canary analysis decision within 10-15 minutes
- Rollback completes within 60 seconds
- In-flight requests must complete (connection draining)
- Schema migrations must be backward-compatible (both old and new code can run against the DB simultaneously)
- Deploy process itself must be resilient (controller crash must not leave partial deploys)

## Scale Estimates
- 200 microservices, 3-5 environments (dev, staging, production)
- 50-100 production deploys per day
- Each service: 5-50 pods per environment
- Canary phase: 5-15 minutes with 5% traffic
- Blue-green: full parallel environment for ~30 minutes during switchover
- Deploy artifacts: 100-500MB container images

## Architecture Decisions

### GitOps (Declarative, Git-Driven)
The deploy config (manifests, Helm values, Kustomize overlays) lives in git. A controller (ArgoCD, Flux) watches for changes and reconciles the cluster to match. This provides: auditability (git history = deploy history), reproducibility (any commit can be redeployed), and recoverability (if the cluster is destroyed, reapply from git). Trade-off: adds a sync loop (seconds of delay) and requires disciplined git workflows.

### Canary with Automated Analysis
Instead of deploying to all pods at once and hoping, deploy to a small percentage (5%) and compare metrics (error rate, latency, saturation) against the stable version running alongside. If the canary is healthy after the analysis window, gradually increase traffic. If it degrades, auto-rollback. This catches issues that slip through tests: performance regressions, edge-case errors, compatibility issues. Trade-off: slower deploys (15+ minutes instead of 2-3 minutes), and requires good metric coverage to detect issues.

### Blue-Green for Stateless, Rolling for Stateful
Blue-green (run two full environments, switch traffic atomically) gives the cleanest rollback (just switch back) but requires 2x resources during deployment. Best for stateless services where rollback speed is critical. Rolling updates (replace pods one by one) use fewer resources but make rollback slower (must roll forward or reverse the rolling update). For stateful services (databases, caches), rolling updates are necessary because you cannot maintain two full data copies.

### Database Schema and Code Must Be Decoupled
Zero-downtime deployments require that both the old and new versions of the code work against the same database schema. This means schema changes must be backward-compatible: add new columns as nullable, never rename or remove columns during the deploy, use the expand-contract pattern. Schema migrations run separately from code deploys, typically before the code deploy starts.

## Component Breakdown

- **Git Repository**: Source code with Dockerfile. Merged PRs trigger CI pipeline. Semantic versioning for releases.
- **CI Pipeline**: Build, test, scan (vulnerability + static analysis), push container image. On success, update the GitOps config repo with the new image tag. Must be fast (< 10 minutes) to not slow down deploy frequency.
- **Container Registry**: Stores versioned container images. Images are immutable (same tag always refers to same image). Vulnerability scanning on push. Retention policy for old images.
- **GitOps Config (Manifests)**: Separate repo containing Kubernetes manifests, Helm values, or Kustomize overlays for each service and environment. CI updates the image tag in this repo when a new build succeeds.
- **Deploy Controller (Argo Rollouts)**: Watches the GitOps config repo. When a new image tag is detected, initiates the deployment strategy (canary, blue-green, rolling). Manages the rollout lifecycle: create canary pods, shift traffic, analyze, promote or rollback.
- **Canary Analyzer (Metric Comparison)**: During canary phase, queries the observability stack (Prometheus) for key metrics from both the canary and stable pods. Compares error rate, p50/p99 latency, and custom business metrics. Uses statistical analysis (Mann-Whitney U test, or simple threshold comparison) to determine if the canary is healthy.
- **Rollback Controller**: Triggers rollback when: canary analysis fails, manual rollback requested, or post-deploy verification fails. For canary/blue-green: scales down new version, routes all traffic to stable. For rolling: initiates reverse rolling update. Must complete within 60 seconds.
- **Feature Flag Service**: Decouples deploy from release. New code is deployed with the feature behind a flag. The flag is enabled gradually (1% -> 10% -> 50% -> 100%) independently from the deploy. If the feature causes issues, disable the flag without redeploying.
- **Pre-Deploy Checks**: Before starting the rollout: verify container image exists in registry, schema migration is applied, config is valid, dependent services are healthy, there are no ongoing incidents.
- **Post-Deploy Verification**: After full promotion: run synthetic tests (Selenium, curl-based health checks), verify SLOs are being met, check for error log spikes. If verification fails, auto-rollback.
- **Deploy Notifications**: Notify Slack/Teams when deploy starts, when canary is promoted, and especially when rollback occurs. Include deploy details: who triggered, what changed, link to diff.
- **Load Balancer (Traffic Splitting)**: Supports weighted traffic routing. During canary: 95% to stable, 5% to canary. During blue-green: atomic switch from blue to green. Must support graceful connection draining (complete in-flight requests before removing pods).
- **Service Mesh (Istio VirtualService)**: Fine-grained traffic control. Can route specific users or request types to the canary (header-based routing). Enables progressive delivery patterns without changing the load balancer.
- **Health Gate (Readiness)**: Kubernetes readiness probes ensure that only healthy pods receive traffic. New pods must pass readiness before receiving any traffic. This prevents routing to pods that are still starting up or warming caches.
- **Blue/Green Environments**: Blue is the current stable version. Green is the new version. Both run simultaneously during the transition. The shared database must be compatible with both versions. After the green environment is verified, blue is scaled down.
- **Shared Database (Schema Compatible)**: The database schema must work with both the old and new application versions. This is the hardest constraint of zero-downtime deployments and must be enforced at the schema migration level.
- **Deploy History (Audit Log)**: Records every deploy: who, when, what version, which strategy, canary analysis results, promotion/rollback outcome. Essential for incident investigations and compliance audits.

## Operational Concerns
- **Deploying the deployment platform itself**: The deploy controller runs in the cluster it manages. Upgrading it requires careful coordination. Use a separate management cluster or self-deploy with extra caution (blue-green deploy of the deploy controller itself).
- **Blast radius of a bad deploy**: Canary limits blast radius to 5% of traffic. With 1000 RPS, 5% canary means 50 RPS hit the new version. If the canary has a bug, 50 RPS see errors for the duration of the analysis window (10-15 min) before auto-rollback. To reduce further: use header-based routing to send only internal traffic to the canary first.
- **Rollback**: For canary: stop traffic to canary pods, scale them down. Takes seconds. For blue-green: switch DNS/LB back to blue. Takes seconds. For rolling updates already completed: must deploy the previous version as a new rolling update. Takes minutes. Blue-green has the fastest rollback.
- **Database rollback**: If the code deploy is rolled back but the schema migration has already been applied, the old code must work with the new schema. This is why expand-contract is mandatory: the schema change is always additive, and the old code simply ignores the new column.

## Failure Modes
- **Deploy controller crash mid-rollout**: The rollout state is persisted in the cluster (Kubernetes CRD). When the controller restarts, it reads the state and resumes the rollout from where it left off. No manual intervention needed.
- **Canary analyzer gives false positive**: The canary is promoted even though it has a bug that the metrics did not catch. This is the most common failure. Mitigation: monitor post-promotion metrics and auto-rollback if SLOs degrade within the bake period (30-60 minutes after full promotion).
- **Canary analyzer gives false negative**: The canary is rolled back even though it was healthy. This wastes time but is safe. Caused by noisy metrics or too-sensitive thresholds. Tune analysis thresholds based on historical deploy data.
- **Traffic split does not work correctly**: All traffic goes to the canary instead of 5%. This can happen if the load balancer or service mesh is misconfigured. Mitigation: verify traffic split with synthetic requests before routing real traffic. Monitor per-pod request counts during canary.
- **Database schema incompatibility**: New code requires a column that does not exist yet (migration not applied), or old code breaks because a column was removed. This violates the backward-compatibility requirement. Mitigation: CI check that verifies both old and new code work against the current schema. Never remove columns in the same deploy that adds the new code.

## Key Trade-offs
- **Deploy speed vs Safety**: Canary analysis adds 10-15 minutes to every deploy. For 50 deploys/day, that is significant pipeline time. But the safety benefit (catching bad deploys before full rollout) is worth it. Optimize by running canary analysis only for production, not staging.
- **Blue-green resource cost vs Rollback speed**: Blue-green requires 2x pods during the transition (10-30 minutes). This is expensive. Rolling updates use less resources but rollback is slower. Use blue-green for critical user-facing services, rolling updates for internal services.
- **Feature flags vs Deploy simplicity**: Feature flags add code complexity (if/else branches, flag cleanup) but decouple release from deploy. This is extremely valuable for high-risk changes. Use feature flags for user-visible features, direct deploys for backend-only changes.
- **GitOps purity vs Deploy speed**: Strict GitOps (every change goes through git) adds a sync delay and requires a PR workflow even for emergency fixes. Most teams add an escape hatch (manual deploy trigger that also records in git).

## What Fails First
Database schema incompatibility during the transition period. When both old and new code run simultaneously against the same database, subtle issues emerge: the new code writes a new column format that the old code cannot read, or the old code writes to a column that the new code does not expect. These issues are hard to catch in tests because tests run only one version at a time. The fix: integration tests that run both old and new code against the same database simultaneously, and a mandatory checklist for schema changes that includes backward-compatibility verification.

## v1 vs v2
### v1 (Minimum Viable Zero-Downtime Deploy)
- Rolling update strategy (Kubernetes native)
- Readiness probes on all services
- Connection draining (terminationGracePeriodSeconds)
- Schema migrations applied before code deploy (expand-contract)
- Manual rollback (kubectl rollout undo)
- Slack notification on deploy start/finish
- Deploy audit log in a shared spreadsheet or wiki

### v2 (Production Grade)
- Argo Rollouts with canary strategy and automated analysis
- Blue-green for critical services
- Prometheus-based canary metric comparison
- Auto-rollback on canary failure or SLO degradation
- GitOps with ArgoCD for declarative deploys
- Feature flag integration for progressive delivery
- Pre-deploy checks (schema, config, dependency health)
- Post-deploy synthetic tests and SLO verification
- Header-based routing for internal canary testing
- Deploy pipeline dashboard with history and diffing
- Emergency deploy escape hatch with audit trail
- Multi-cluster deploys (deploy to staging, then prod-1, then prod-2)
