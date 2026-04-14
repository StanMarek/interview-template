# Rate Limiter -- Architecture Design

## Requirements

### Functional
- Limit requests per client/IP/API-key within a configurable time window
- Support multiple rate-limiting strategies: per-user, per-endpoint, per-tier (free/premium)
- Return `429 Too Many Requests` with `Retry-After` header when limit is exceeded
- Admin API to define and update rate-limit rules without redeployment
- Support both hard limits (reject) and soft limits (log-only / degrade gracefully)

### Non-Functional
- **Ultra-low latency:** Rate check must add < 5ms to each request (p99)
- **High throughput:** Must handle 1M+ checks/sec across the cluster
- **Availability:** If the rate limiter itself is down, fail open (allow traffic) rather than blocking all requests
- **Accuracy:** Small over-counting is acceptable; under-counting (letting too many through) is not

## Scale Estimates
- **Requests to rate-limit:** 500K/sec (peak 2M/sec)
- **Unique keys (users/IPs):** 10M active per day
- **Redis memory:** Each sliding window entry ~100 bytes. 10M keys * 100B = ~1 GB (very manageable)
- **Rules storage:** 1000s of rules -- trivial in any DB

## Architecture Decisions

### Sliding Window Log vs Token Bucket vs Fixed Window

| Algorithm | Accuracy | Memory | Complexity |
|---|---|---|---|
| Fixed Window | Low (burst at boundaries) | O(1) per key | Simple |
| Sliding Window Log | Perfect | O(N) per key (stores each timestamp) | High |
| Sliding Window Counter | Good (weighted approximation) | O(1) per key | Medium |
| Token Bucket | Good (allows controlled bursts) | O(1) per key | Medium |

**Decision: Sliding Window Counter** for most rules, **Token Bucket** for burst-tolerant endpoints.

The sliding window counter approximates the true sliding window by weighting the previous and current fixed windows:
```
count = (prev_window_count * overlap_percentage) + current_window_count
```
This gives ~99.7% accuracy with O(1) memory per key, which is the sweet spot for a general-purpose rate limiter.

### Redis Over Local In-Memory Counters
In a distributed deployment, each server sees only a fraction of a client's requests. If you rate-limit locally, a client sending 100 req/sec across 10 servers sees only 10 req/sec per server -- the limit is effectively multiplied by server count.

Redis provides a **single shared counter** across all servers. The `INCR` + `EXPIRE` atomic operation is perfectly suited:
```
MULTI
INCR rate:{user_id}:{window_key}
EXPIRE rate:{user_id}:{window_key} {window_size + 1}
EXEC
```
**Trade-off:** Adds a network hop (~1-2ms). For cases where this is too slow, use a **hybrid approach**: local token bucket for fast-path (allows small bursts), synced to Redis periodically.

### Fail-Open Design
If Redis is unreachable, the rate limiter should **allow** requests through rather than rejecting everything. Reasoning:
- A rate limiter outage shouldn't cause a total service outage
- The backend services have their own capacity limits (connection pools, circuit breakers)
- Brief periods without rate limiting are survivable; brief periods of total rejection are not
- **Mitigation:** Alert immediately so engineers can respond before abuse takes hold

### Rules Engine with Hot Reload
Rate-limit rules change frequently (new tiers, endpoint-specific limits, emergency throttling during incidents). Storing rules in a DB with a local cache (30-second TTL) means:
- No redeployment to change limits
- Emergency throttle: update a rule, wait 30 seconds, all servers pick it up
- Rules can be complex: "user X on endpoint Y in tier Z gets N requests per M seconds"

### Response Headers for Client Cooperation
Every response includes:
- `X-RateLimit-Limit`: the limit for this window
- `X-RateLimit-Remaining`: requests remaining
- `X-RateLimit-Reset`: epoch second when the window resets

Well-behaved clients use these to self-throttle, reducing server-side rejection overhead.

## Component Breakdown

| Component | Role |
|---|---|
| **API Clients** | Any service or user calling the API |
| **API Gateway** | Entry point; delegates to rate limiter middleware before forwarding |
| **Rate Limiter Middleware** | Intercepts requests, checks Redis counters, returns 429 or passes through |
| **Rules Engine** | Loads and caches rate-limit rules from the config DB; matches request to applicable rule |
| **Backend Services** | The actual business logic, only reached if the rate check passes |
| **Redis (Counters)** | Stores per-key counters with TTL-based expiration. Clustered for HA |
| **Rules Config DB** | PostgreSQL table of rules: `{id, key_pattern, limit, window_seconds, strategy, tier}` |
| **Metrics (Prometheus)** | Tracks allowed/rejected counts per rule, latency of rate checks, Redis hit/miss |
| **Admin Dashboard (Grafana)** | Visualizes rejection rates, identifies misbehaving clients, manages rules |

## Key Trade-offs

- **Accuracy vs latency:** Perfectly accurate sliding window logs require storing every timestamp (memory-heavy, O(log N) to trim). The sliding window counter trades ~0.3% accuracy for O(1) operations
- **Centralized vs distributed counters:** Redis gives accuracy but adds latency. Local counters are fast but inaccurate in multi-server deployments. The hybrid approach (local + periodic sync) is complex to implement correctly
- **Fail-open vs fail-closed:** Fail-open risks abuse during Redis outages. Fail-closed risks total service disruption. Most systems choose fail-open and accept the risk window
- **Per-IP vs per-user:** IP-based limiting is easy but penalizes users behind NAT/corporate proxies. API-key-based limiting is fair but requires authentication before rate-limiting (which itself could be a DoS vector)

## What Fails First

**Redis becomes the bottleneck** under extreme load. At 2M checks/sec, even with pipelining, a single Redis instance tops out around 100K ops/sec. Mitigation:
- Redis Cluster with 20+ shards (partition by rate-limit key hash)
- Use Redis pipelining to batch INCR operations
- Local L1 cache with token bucket to absorb bursts without hitting Redis

**Secondary risk:** The rules engine cache becomes stale if the config DB is unreachable. If rules can't be loaded, the limiter should use the last-known rules rather than failing.

## v1 vs v2

### v1 (Ship in 1 week)
- Fixed window counter in Redis (simplest to implement)
- Single global rule: N requests per minute per API key
- Hard-coded rules in config file (no admin API)
- Fail-open on Redis failure
- Basic Prometheus metrics (allowed/rejected counts)

### v2 (Production-grade)
- Sliding window counter + token bucket (selectable per rule)
- Per-endpoint, per-tier rules with admin CRUD API
- Rules hot-reload from PostgreSQL with 30-sec cache
- Distributed Redis Cluster with automatic failover
- Client-facing rate-limit headers on every response
- Grafana dashboard with per-client breakdown
- DDoS protection integration (move IP-level blocking to edge/CDN)
- Rate limit by request cost (e.g., a search query costs 5 units, a read costs 1)
