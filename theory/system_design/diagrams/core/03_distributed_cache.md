# Distributed Cache -- Architecture Design

## Requirements

### Functional
- Store and retrieve key-value pairs with sub-millisecond latency
- Support TTL (time-to-live) per entry and global default TTL
- Cache invalidation: explicit delete, TTL expiry, and event-driven invalidation
- Support multiple eviction policies: LRU, LFU, TTL-based
- Provide cache-aside, read-through, and write-through patterns
- Multi-tenant: isolate cache namespaces per service/team

### Non-Functional
- **Latency:** < 1ms for L1 (local), < 5ms for L2 (distributed) at p99
- **Throughput:** 1M+ reads/sec across the cluster
- **Availability:** 99.99% -- cache unavailability cascades to DB overload
- **Consistency:** Eventual consistency between L1 and L2 is acceptable (staleness window < 5 seconds)

## Scale Estimates
- **Cache entries:** 500M keys across all services
- **Average value size:** 1 KB (range: 100B to 100KB)
- **Total data in cache:** 500 GB across L2 cluster
- **Read QPS:** 2M/sec (95% cache hit target)
- **Write QPS:** 100K/sec (cache updates + invalidations)
- **L1 per-server:** 500 MB (hot working set, ~500K entries)

## Architecture Decisions

### Two-Tier Caching (L1 + L2)
This is the single most impactful design decision. A network call to Redis takes ~1-3ms. For data accessed thousands of times per second on a single server, that latency adds up.

**L1 (Local, in-process):** Caffeine cache on each app server. Zero network overhead, sub-microsecond access. But each server has its own copy, so consistency is weaker.

**L2 (Distributed):** Redis Cluster shared across all servers. Single source of truth for cached data.

**Lookup order:** L1 -> L2 -> Database. On DB read, populate both L2 and L1. On writes, invalidate L2 and broadcast invalidation to all L1 caches via pub/sub.

**Why this matters:** In practice, the L1 cache absorbs 60-80% of reads, meaning L2 only sees 20-40% of total read traffic. This dramatically reduces Redis cluster size requirements and network bandwidth.

### Consistent Hashing for L2 Sharding
When a cache cluster needs to scale (add/remove nodes), naive modulo hashing (`hash(key) % N`) causes massive redistribution -- nearly every key moves.

Consistent hashing with virtual nodes means:
- Adding a node moves only `K/N` keys (K = total keys, N = nodes) instead of nearly all
- Virtual nodes (100-200 per physical node) ensure even distribution
- **Trade-off:** Slightly more complex client routing, but Redis Cluster handles this natively with hash slots (16384 slots partitioned across nodes)

### Cache Invalidation Strategy
"There are only two hard things in computer science: cache invalidation and naming things."

The system supports three invalidation mechanisms:
1. **TTL-based expiry:** Every entry has a TTL. Safe default, handles the common case of "data changes occasionally"
2. **Explicit invalidation:** On write to DB, invalidate the cache key. Best-effort consistency
3. **Event-driven invalidation via pub/sub:** When a service updates data, it publishes an invalidation event. All app servers subscribe and evict from L1. This closes the gap between "data changed" and "all L1 caches are updated"

**The tricky part:** Between a DB write and the cache invalidation, stale data is served. The window is typically < 100ms for L2 and < 5 seconds for L1. For most use cases, this is acceptable. For those where it's not, use write-through caching (update cache synchronously with DB).

### Cache-Aside as the Default Pattern
Cache-aside (lazy loading) is the default because:
- Application controls exactly what gets cached and when
- Cache failures don't break writes -- the write goes to DB regardless
- No unnecessary data in cache -- only what's actually read gets cached

**Read-through/write-through** are used selectively for hot data where you want to guarantee the cache is always warm (e.g., user session data).

### Thundering Herd Protection
When a popular cache key expires, hundreds of concurrent requests see a cache miss and all hit the DB simultaneously. Solutions:
1. **Singleflight / Request coalescing:** Only one request fetches from DB; others wait for the result. Implemented at the application level using a lock per cache key
2. **Stale-while-revalidate:** Serve the expired value while one request refreshes it in the background
3. **Probabilistic early expiry:** Each request has a small random chance of refreshing the key before it actually expires, spreading the load

**Decision:** Use singleflight as the primary mechanism. It's simple, effective, and well-understood.

## Component Breakdown

| Component | Role |
|---|---|
| **App Servers** | Business logic; contain cache client library that implements L1+L2 lookup |
| **L1 Local Cache (Caffeine)** | In-process cache per server. Size-bounded, LRU/LFU eviction. Near-zero latency |
| **Consistent Hash Ring** | Routes cache keys to the correct Redis shard. Handles node addition/removal gracefully |
| **Redis Shards (Primary+Replica)** | L2 distributed cache. Each shard is a primary with 1-2 replicas for HA. Redis Cluster handles failover via gossip protocol (Sentinel is for non-clustered Redis). Since mid-2024, Valkey (Linux Foundation fork after the Redis license change) is a drop-in alternative used by AWS ElastiCache/MemoryDB |
| **Primary DB (PostgreSQL)** | Source of truth. Only hit on L1+L2 cache miss |
| **Read Replicas** | Offload read queries from primary DB during cache miss storms |
| **Invalidation Bus (Pub/Sub)** | Redis pub/sub or Kafka. Broadcasts cache invalidation events to all app servers for L1 eviction |
| **Cache Metrics** | Tracks hit rate, miss rate, eviction rate, latency percentiles. Critical for tuning cache size and TTLs |
| **Config Service** | Manages per-namespace TTL, eviction policy, max size, and warm-up configuration |

## Key Trade-offs

- **Consistency vs performance:** Longer TTLs improve hit rate but increase staleness. The right TTL depends on how often data changes and how much staleness the use case tolerates
- **Memory vs hit rate:** Larger L1 cache means higher hit rate but more memory per server. Too large and it competes with application heap. Typically cap at 10-20% of server RAM
- **L1 invalidation latency:** Pub/sub invalidation is best-effort. If a server misses the message, it serves stale data until L1 TTL expires. Accept this or add periodic L2-check heartbeats
- **Complexity vs performance:** Two-tier caching adds significant complexity (invalidation, consistency, monitoring). Only justified at scale -- for < 10K QPS, a single Redis is sufficient

## What Fails First

**L2 (Redis) memory exhaustion** is the most common failure mode. When Redis runs out of memory:
- If `maxmemory-policy` is set to `allkeys-lru`, Redis evicts cold keys automatically -- but hit rate degrades
- If set to `noeviction`, writes fail and the system cascades to DB overload

**Mitigation:** Monitor memory usage with alerts at 70% and 85%. Auto-scale shards based on memory pressure. Set appropriate `maxmemory` limits per shard.

**Secondary risk:** Cache stampede (thundering herd) after a cold start or mass eviction. Mitigation: pre-warm the cache during deployment using a warm-up job that reads the most popular keys.

## v1 vs v2

### v1 (Ship in 1 week)
- Single Redis instance (no sharding), cache-aside pattern
- No L1 cache (just Redis)
- TTL-based expiry only (no event-driven invalidation)
- Simple client library wrapping Redis GET/SET
- Basic monitoring (hit rate, latency)

### v2 (Production-grade)
- Redis Cluster with consistent hashing and automatic failover
- Two-tier L1 (Caffeine) + L2 (Redis) with pub/sub invalidation
- Thundering herd protection via singleflight
- Per-namespace configuration (TTL, eviction, max size)
- Cache warming on deployment
- Comprehensive metrics dashboard (per-key-pattern hit rates, eviction causes, latency histograms)
- Multi-region replication for global latency
- Support for cache-aside, read-through, and write-through patterns
- Circuit breaker on Redis client -- fall through to DB on Redis failure
