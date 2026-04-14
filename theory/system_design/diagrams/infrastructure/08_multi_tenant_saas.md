# Multi-Tenant SaaS Architecture -- Architecture Design

## Requirements
### Functional
- Serve multiple customers (tenants) from a shared infrastructure
- Tenant isolation: one tenant's data and operations must not be visible to or affect another
- Tenant onboarding: self-service signup with automatic provisioning of tenant resources
- Per-tenant configuration: feature flags, branding, data residency preferences
- Usage metering and billing per tenant
- Per-tenant rate limiting and quotas
- Support for different tiers: free, standard, enterprise (dedicated resources)
- Tenant data export and deletion (compliance with GDPR/SOC2)

### Non-Functional
- Noisy neighbor protection: one tenant's traffic spike must not degrade others
- Data isolation: no cross-tenant data leakage under any circumstances
- 99.95% availability per tenant
- Tenant-specific SLAs for enterprise customers
- Horizontal scalability: support 10K+ tenants without per-tenant infrastructure overhead for small tenants
- Audit logging per tenant for compliance

## Scale Estimates
- 10,000 tenants total: 9,500 free/standard (small), 500 enterprise (large)
- Free tenants: < 100 RPM each; Enterprise tenants: up to 50K RPM
- Aggregate: 2M requests/min across all tenants
- Data: 100GB average per standard tenant, 10TB per enterprise tenant
- Total data: 5PB across all tenants
- Tenant onboarding rate: 50-100 new tenants/day

## Architecture Decisions

### Pool vs Silo Data Isolation Model
**Pool model** (shared database, tenant_id column): all tenants share database tables, differentiated by a tenant_id column. Cheap, simple to operate, but isolation depends on correct application logic and row-level security. A bug that omits the tenant_id filter leaks data across tenants. **Silo model** (database per tenant): each tenant has its own database or schema. Strong isolation, easy to reason about, but operationally expensive (5000 databases to manage, migrate, back up). The hybrid approach: pool model for standard tenants (cost-effective), silo model for enterprise tenants (strong isolation, dedicated resources, compliance).

### Tenant Context Propagation
Every request must carry tenant identity throughout the entire call chain. The API gateway extracts tenant ID from the JWT, validates it, and injects it into request headers. Every service must include tenant_id in database queries, cache keys, queue messages, and log entries. This is non-negotiable and must be enforced at the framework level (middleware that rejects any database query without a tenant filter). Missing tenant context is a data leak waiting to happen.

### Noisy Neighbor Protection via Per-Tenant Rate Limiting
Without rate limiting, a single tenant's traffic spike consumes shared resources (CPU, database connections, cache) and degrades all tenants. Per-tenant rate limits at the API gateway and per-tenant queue partitions ensure fair resource allocation. Enterprise tenants get higher limits and optionally dedicated compute. The trade-off: rate limiting adds complexity and latency (rate limit check on every request).

### Tenant Provisioning: Lazy vs Eager
Lazy provisioning (create tenant resources on first use) is faster for onboarding and avoids wasting resources for tenants who sign up but never use the product. Eager provisioning (pre-create everything at signup) guarantees the tenant experience is fast from the first request. Recommendation: lazy provisioning for pool-model tenants (just create a row in the tenant table), eager provisioning for silo-model enterprise tenants (provision database, compute, DNS, certificates).

## Component Breakdown

- **API Gateway (Tenant Routing)**: Entry point for all tenant traffic. Extracts tenant ID from JWT or API key. Routes enterprise tenants to dedicated compute, standard tenants to shared compute. Enforces per-tenant rate limits. Logs per-tenant request metrics.
- **Auth / Identity (JWT + Tenant ID)**: Issues JWTs containing user ID and tenant ID. Supports multi-tenant SSO (SAML, OIDC). Validates that the authenticated user belongs to the requested tenant. Manages API keys for service-to-service calls.
- **Tenant Manager (Provisioning)**: Handles tenant lifecycle: create, configure, suspend, delete. For pool tenants: inserts a row in the tenant config table. For silo tenants: triggers infrastructure provisioning (Terraform, Pulumi). Manages tenant metadata: plan tier, data region, feature flags.
- **Billing / Metering Service**: Tracks per-tenant usage (API calls, storage, compute time). Aggregates usage into billing records. Integrates with payment providers (Stripe). Supports usage-based pricing, seat-based pricing, or hybrid. Must be accurate to the cent and auditable.
- **Quota / Rate Limit Service**: Maintains per-tenant rate limits and quotas. Checks on every request via the API gateway. Different limits per tier (free: 100 RPM, standard: 10K RPM, enterprise: 50K RPM). Also enforces storage quotas, user count limits.
- **Feature Flags (Per-Tenant)**: Controls which features each tenant can access. Enables gradual feature rollout by tenant tier. Supports tenant-specific overrides (enable beta feature for specific tenant). Used for plan differentiation and A/B testing.
- **Core Services (Shared Compute)**: The application microservices. Run as a shared fleet for all pool tenants. Every service includes tenant-aware middleware that enforces tenant context in all data access. Scales based on aggregate traffic.
- **Async Workers (Per-Tenant Queue)**: Background job processing with per-tenant queue partitions. Ensures one tenant's job backlog does not block another's. Priority based on tier.
- **Dedicated Compute (Enterprise Tenants)**: Isolated pods/VMs for enterprise tenants that require dedicated resources, compliance (SOC2), or custom SLAs. Managed by the tenant manager. Higher cost, passed through to the enterprise tenant.
- **Shared DB (tenant_id column)**: PostgreSQL with row-level security (RLS) policies. Every table includes a tenant_id column. RLS policies ensure queries can only see rows belonging to the authenticated tenant. Partitioned by tenant_id for query performance.
- **Schema/DB per Tenant (Silo)**: Enterprise tenants get their own database instance. Complete data isolation. Can be in a specific region for data residency compliance. Independent backup and restore.
- **Cache (Redis) with Tenant-Prefixed Keys**: Shared Redis cluster. All cache keys include the tenant ID prefix (tenant:{id}:resource:{key}). Per-tenant TTL policies. Memory limits per tenant (if supported by the caching layer).
- **Blob Storage (S3) with Tenant-Prefixed Paths**: Object storage with path-based isolation (s3://bucket/tenants/{tenant_id}/...). IAM policies or bucket policies restrict cross-tenant access. Enterprise tenants can have dedicated buckets.
- **Message Queue (Per-Tenant Topics)**: Kafka or SQS with per-tenant topics or partitions. Ensures fair consumption and prevents one tenant from monopolizing queue throughput.

## Operational Concerns
- **Deploying changes safely**: Application deployments affect all tenants simultaneously (shared compute). Use canary deploys with tenant-aware traffic routing: route 1% of tenants to the new version first. Feature flags for new functionality let you control rollout per tenant.
- **Blast radius of a bug**: A bug in shared compute affects all pool tenants. Strong test coverage and canary deploys limit this. For enterprise tenants on dedicated compute, the blast radius is one tenant per deployment.
- **Tenant data deletion**: GDPR requires complete deletion. For pool model: delete all rows with tenant_id (verify with audit). For silo model: drop the database. Blob storage: delete the tenant prefix. Cache: flush tenant-prefixed keys. Queue: purge tenant topics. This is operationally complex and must be tested regularly.
- **Tenant onboarding/offboarding**: Must be fully automated. Manual steps do not scale to 100 tenants/day. The tenant manager orchestrates all provisioning steps and tracks status. Failed provisioning must be retried or alerted.

## Failure Modes
- **Shared database overloaded by one tenant**: Even with quotas at the API layer, database-level resource consumption (slow queries, locks) can affect all tenants. Mitigation: connection pooling per tenant, query timeout per tenant, and moving heavy tenants to silo databases.
- **Tenant context lost in a service**: If a service processes a request without tenant context, it might access all tenants' data or write data without a tenant_id. This is a critical security bug. Mitigation: mandatory middleware that rejects requests without tenant context, and database triggers that reject inserts without tenant_id.
- **Rate limit service failure**: If the rate limit service is down, either allow all traffic (risk of noisy neighbor) or reject all traffic (all tenants affected). The safer default: allow with degraded limits (use a local in-memory rate limiter with conservative limits).
- **Billing metering gap**: If metering data is lost, you cannot accurately bill tenants. Mitigation: write metering events to a durable queue (Kafka) before processing. Reconcile metering data daily against actual usage.

## Key Trade-offs
- **Pool vs Silo isolation**: Pool is cheaper and simpler to operate but has weaker isolation and higher risk of data leakage. Silo is more isolated but expensive and complex to manage at scale. The hybrid approach (pool for standard, silo for enterprise) balances cost and isolation.
- **Shared compute vs Dedicated compute**: Shared compute maximizes resource utilization but introduces noisy-neighbor risk. Dedicated compute eliminates this but is expensive and requires per-tenant infrastructure management. Offer dedicated compute as a premium feature.
- **Per-tenant customization vs Operational simplicity**: Every per-tenant config option (feature flag, rate limit, custom domain) adds operational complexity. Balance by offering tier-based defaults with limited overrides rather than fully custom per-tenant configuration.
- **Tenant-level encryption vs Performance**: Encrypting data with per-tenant keys provides the strongest isolation (one compromised key does not expose other tenants) but adds key management complexity and slight performance overhead. Required for enterprise/compliance tenants.

## What Fails First
Cross-tenant data leakage due to a missing tenant_id filter. In a system with hundreds of database queries across dozens of services, it takes one missed WHERE clause to serve Tenant A's data to Tenant B. This is the most critical and most likely failure mode. The fix: row-level security at the database level (not just application-level filtering), mandatory ORM-level tenant scoping, integration tests that verify no query returns cross-tenant data, and regular security audits. Automated scanning for queries missing the tenant filter should be part of CI.

## v1 vs v2
### v1 (Minimum Viable Multi-Tenant)
- Shared database with tenant_id column (pool model for all tenants)
- API gateway with JWT-based tenant extraction
- Per-tenant rate limiting at API gateway
- Usage metering (API call counts) to a metrics store
- Feature flags for plan differentiation (3 tiers)
- Standard compute shared across all tenants
- S3 with tenant-prefixed paths for file storage
- Basic tenant dashboard (usage, settings)

### v2 (Production Grade)
- Hybrid pool + silo model (silo for enterprise tenants)
- Row-level security (RLS) at the database layer
- Dedicated compute for enterprise tenants
- Per-tenant encryption keys (BYOK for enterprise)
- Custom domains with automatic certificate provisioning
- Data residency controls (choose region for silo tenants)
- Per-tenant backup and restore
- Billing with usage-based pricing and invoicing
- Tenant-aware canary deploys
- Cross-tenant data leakage detection (automated scanning)
- GDPR-compliant tenant data export and deletion workflows
- SOC2 audit logging per tenant
