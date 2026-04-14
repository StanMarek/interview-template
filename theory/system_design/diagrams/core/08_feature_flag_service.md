# Feature Flag Service -- Architecture Design

## Requirements

### Functional
- Define boolean flags (on/off), multivariate flags (A/B/C variants), and percentage rollouts
- Evaluate flags based on user attributes: user_id, email, country, plan tier, custom attributes
- Targeting rules: "enable for 10% of users in US on premium plan"
- SDK for server-side (Java, Python, Go) and client-side (JavaScript, iOS, Android) evaluation
- Admin dashboard to create, modify, and kill-switch flags without deployment
- Audit log of every flag change with who/when/what
- Flag dependencies: "flag B only active if flag A is active"
- Scheduled flag activation/deactivation (launch at midnight EST)

### Non-Functional
- **Evaluation latency:** < 1ms per flag (must be in-process, not a network call per evaluation)
- **Flag propagation:** Changes visible to all clients within 10 seconds
- **Availability:** 99.99% -- if the flag service is down, applications must continue with last-known values
- **Evaluation volume:** 10B flag evaluations/day across all services
- **Consistency:** All instances of a service should see the same flag values within the propagation window

## Scale Estimates
- **Active flags:** 500-2000 at any time
- **Flag evaluations:** 10B/day (~115K/sec)
- **Unique user contexts:** 100M (users who get flag evaluations)
- **Flag data size:** All flags + rules ~10 MB (easily fits in memory on every server)
- **Evaluation events (analytics):** 10B/day at ~100 bytes = 1 TB/day

## Architecture Decisions

### Client-Side Evaluation (Not Server-Side API Calls)
This is the defining architectural decision. There are two models:

**Server-side evaluation (LaunchDarkly model):** SDK sends user context to API, API evaluates flag, returns result. Every evaluation is a network call.

**Client-side evaluation (local model):** SDK downloads ALL flag definitions to local memory. Evaluation happens in-process with zero network calls. SDK syncs flag definitions periodically.

**Decision: Client-side evaluation.** At 115K evaluations/sec, making a network call per evaluation would require massive API infrastructure. Instead, each application instance holds a ~10 MB snapshot of all flags in memory. Evaluation is a local hash-map lookup + rule evaluation = sub-millisecond.

**Trade-off:** Flag changes take up to 10 seconds to propagate (sync interval). For 99.9% of flags, this is fine. For emergency kill switches, we add a push channel (SSE/WebSocket) for instant propagation.

### Deterministic Hashing for Percentage Rollouts
When a flag is set to "enable for 30% of users," every evaluation for the same user must return the same result. We can't use `random() < 0.3` because:
- The same user would get different results on different servers
- The same user would get different results on page reload

**Solution:** Hash the `(flag_key, user_id)` pair to a value 0-99. If `hash < 30`, the user sees the feature. This is:
- **Deterministic:** Same input always produces same output
- **Uniform:** Users are evenly distributed across buckets
- **Sticky:** Changing the rollout from 30% to 40% adds 10% of users -- the original 30% remain in

**Implementation:** `murmurhash3(flag_key + "." + user_id) % 100`

### Streaming Updates + Polling Fallback
Flag propagation uses a layered approach:
1. **SSE (Server-Sent Events):** SDK maintains a persistent connection to the streaming endpoint. Flag changes are pushed in real-time (~1 second propagation)
2. **Polling fallback:** If SSE disconnects, fall back to polling every 30 seconds
3. **Bootstrap from cache:** On SDK initialization, load flags from Redis (fast) while establishing the SSE connection

**Why SSE over WebSocket?** SSE is simpler (unidirectional, HTTP-based, auto-reconnects), and flag updates are unidirectional (server to client). WebSocket adds complexity for no benefit here.

### Flag Evaluation Rules Engine
Flags have complex targeting rules that the SDK must evaluate locally:

```json
{
  "flag_key": "new_checkout",
  "variations": [false, true],
  "rules": [
    {"if": {"attribute": "email", "op": "endsWith", "value": "@company.com"}, "serve": 1},
    {"if": {"attribute": "country", "op": "in", "value": ["US", "CA"]}, "percentage": {"0": 70, "1": 30}},
    {"fallthrough": {"serve": 0}}
  ]
}
```

Rules are evaluated top-to-bottom. First match wins. The SDK must implement all operators (equals, contains, in, regex, semver comparison) locally.

**Why not simplify?** Complex rules replace code-level `if` statements. Without them, developers embed business logic in code instead of the flag service, defeating the purpose.

### Audit Log and Change Management
Every flag change produces an audit event:
```
{timestamp, user, flag_key, old_value, new_value, environment, reason}
```

This is non-negotiable because:
- Debugging production incidents requires knowing exactly what changed when
- Compliance requires audit trails for feature changes
- Teams can answer "who turned on feature X in production and why?"

**Implementation:** Append-only table in PostgreSQL. Changes are also published to Kafka for real-time alerting.

## Component Breakdown

| Component | Role |
|---|---|
| **Backend Services** | Application code that calls `flagClient.isEnabled("flag_key", userContext)` |
| **Frontend SDK** | JavaScript SDK that evaluates flags locally for client-side rendering decisions |
| **Mobile SDK** | iOS/Android SDK with offline support and local evaluation |
| **Admin Dashboard** | UI for creating, editing, targeting, and killing flags. Shows evaluation analytics |
| **Local Flag Cache** | In-process cache within each SDK instance. Holds full flag snapshot (~10 MB) |
| **Flag Evaluator** | SDK component that applies targeting rules to determine flag value for a given context |
| **Flag Evaluation API** | REST endpoint for initial flag bootstrap and polling fallback |
| **Admin API** | CRUD operations for flags, rules, environments. Write path with validation |
| **SSE/Streaming Endpoint** | Persistent connection that pushes flag changes to SDKs in real-time |
| **Flag Store (PostgreSQL)** | Source of truth for flag definitions, rules, and environments |
| **Redis Cache** | Cached flag snapshots for fast bootstrap. Invalidated on flag changes |
| **Audit Log** | Append-only record of every flag change |
| **Evaluation Events (Kafka)** | Stream of flag evaluation events for analytics |
| **Analytics (ClickHouse)** | Stores and queries evaluation data: how many users saw each variant |
| **A/B Experiment Engine** | Statistical analysis of flag experiments: conversion rates, significance testing |

## Key Trade-offs

- **Local evaluation vs API evaluation:** Local is 1000x faster but requires all flags in every SDK instance. If you have 100K flags (unlikely), the payload becomes large. At 500-2000 flags, it's trivially small
- **Push vs pull for updates:** SSE push gives sub-second propagation but requires persistent connections (resource-intensive for the server). Polling is simple but adds 30-second latency. Hybrid (SSE + polling fallback) is the right answer
- **Per-evaluation analytics vs sampled:** Logging every evaluation (10B/day) generates massive data volume. Sampling (log 1%) reduces volume but loses precision for low-traffic flags. **Decision:** Log all evaluations but aggregate client-side and batch-send every 60 seconds
- **Multi-environment complexity:** Flags have different values in dev/staging/prod. Each environment is a separate configuration. SDKs must be environment-aware. Adds complexity but prevents "it worked in staging" incidents

## What Fails First

**The streaming endpoint (SSE) under connection churn.** If 10K service instances each maintain an SSE connection, and a deployment rolls all instances simultaneously, the streaming service sees 10K new connections in seconds. Each connection triggers a full flag snapshot send.

**Mitigation:**
- Jitter SDK reconnection (random delay 0-5 seconds)
- Use the Redis-cached snapshot for bootstrap (don't query PostgreSQL on every connection)
- Scale streaming endpoint horizontally (stateless -- each instance serves any client)

**Secondary risk:** Flag configuration conflicts. Two engineers edit the same flag simultaneously. One overwrites the other. **Mitigation:** Optimistic locking (version field on each flag) + conflict detection in the admin API.

## v1 vs v2

### v1 (Ship in 1 week)
- Boolean flags only (on/off)
- Server-side SDK with polling (no SSE)
- Simple targeting: percentage rollout and user-ID allowlist
- PostgreSQL for flag storage
- Admin API with basic CRUD (no UI)
- No analytics
- Single environment (production only)

### v2 (Production-grade)
- Multivariate flags with complex targeting rules
- Client-side evaluation with SSE push + polling fallback
- SDKs for Java, Python, Go, JavaScript, iOS, Android
- Admin dashboard with flag lifecycle management
- Multi-environment (dev/staging/prod) with promotion workflow
- Audit log with Slack/PagerDuty alerting on flag changes
- A/B experiment engine with statistical significance testing
- Scheduled flag activation/deactivation
- Flag dependencies and prerequisites
- Stale flag detection (flags unchanged for 90+ days -> notify owner)
- RBAC: who can modify which flags in which environments
