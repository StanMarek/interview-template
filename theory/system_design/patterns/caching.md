# Caching

## What It Is
Caching stores frequently accessed data in a faster storage layer (usually in-memory) so that future requests can be served faster and with less load on the primary data store.

## Why It Matters
Caching is arguably the single most impactful optimization in system design. A well-placed cache can reduce database load by 90%+ and cut latency from ~10ms (DB query) to ~1ms (cache hit).

## The Caching Hierarchy

```
Client-Side Cache (Browser) → CDN Cache → API Gateway Cache → Application Cache → Database Cache
          ~0ms                  ~5ms           ~2ms              ~1ms              ~5-10ms
```

Each layer serves a different purpose. In interviews, you'll most commonly discuss the application-level cache (Redis/Memcached) and CDN caching.

## Caching Strategies

### Read Strategies

| Strategy | Flow | Pros | Cons |
|----------|------|------|------|
| **Cache-Aside (Lazy Loading)** | App checks cache → miss → read DB → write to cache | Only caches what's needed; resilient to cache failure | Cache miss penalty (3 network calls); data can become stale |
| **Read-Through** | Cache sits between app and DB; cache itself fetches on miss | Simpler app code; consistent read path | Cache library must support it; still stale |
| **Refresh-Ahead** | Cache proactively refreshes entries before they expire | Reduced latency for hot keys; always fresh | Wasted refreshes for keys nobody reads |

### Write Strategies

| Strategy | Flow | Pros | Cons |
|----------|------|------|------|
| **Write-Through** | Write to cache AND DB synchronously | Cache always consistent with DB | Write latency doubles (2 writes); caches data that may never be read |
| **Write-Behind (Write-Back)** | Write to cache → async write to DB | Very fast writes; batching possible | Data loss risk if cache crashes before DB write |
| **Write-Around** | Write directly to DB, skip cache | Avoids cache pollution on writes | Subsequent read is a cache miss |

### Best Combinations
- **Most common**: Cache-Aside + Write-Around — simple, flexible, good for read-heavy workloads
- **Strong consistency needed**: Read-Through + Write-Through
- **Write-heavy + eventual consistency ok**: Cache-Aside + Write-Behind

## Eviction Policies

| Policy | Description | Use When |
|--------|-------------|----------|
| **LRU** (Least Recently Used) | Evicts the item not accessed for the longest time | General purpose, most common |
| **LFU** (Least Frequently Used) | Evicts the item accessed the fewest times | Frequency matters more than recency |
| **FIFO** | Evicts the oldest item | Simple, order matters |
| **TTL-based** | Evicts after a time-to-live expires | All items have a natural expiration |
| **Random** | Evicts a random item | Surprisingly effective, very simple |
| **ARC** (Adaptive Replacement Cache, ZFS) | Self-tuning between recency and frequency | Workloads with shifting access patterns |
| **W-TinyLFU** (Caffeine library) | Modern hit-ratio leader; sketch-based frequency estimation + small window cache for recency | Preferred over plain LRU in JVM applications |

## Cache Invalidation
This is the hardest problem in caching ("There are only two hard things in computer science: cache invalidation and naming things.").

### Approaches
- **TTL (Time-to-Live)**: Set an expiration. Simple but data may be stale until expiry.
- **Event-driven invalidation**: DB write triggers cache delete/update. Requires infrastructure (CDC, pub/sub).
- **Versioned keys**: Append version number to cache key. Increment version on write. Old keys naturally expire.
- **Manual purge**: Application explicitly deletes cache entries on write. Simple but error-prone if you miss a code path.

### The Thundering Herd Problem
When a popular cache key expires, hundreds of concurrent requests all miss the cache and hit the DB simultaneously. Solutions:
- **Locking**: Only one request fetches from DB; others wait for the cache to be populated.
- **Stale-while-revalidate**: Serve stale data while one request refreshes the cache in the background.
- **Pre-warming**: Refresh hot keys before they expire.
- **Probabilistic early expiration**: Each request has a small random chance of refreshing slightly before TTL.

## Cache Consistency Patterns
- **Eventually consistent**: TTL-based expiry. Good enough for 95% of use cases (user profiles, product listings).
- **Strong consistency**: Write-through or event-driven invalidation. Needed for financial data, inventory counts.
- **Bounded staleness**: Set TTL to an acceptable staleness window (e.g., 5 seconds).

## Distributed Cache Topology
- **Replicated cache**: Every node has a full copy. Fast reads, expensive writes. Good for small, read-heavy datasets.
- **Partitioned (sharded) cache**: Data split across nodes using consistent hashing. Scales to large datasets.
- **Near cache + remote cache**: Local in-process cache (L1) backed by a remote shared cache (L2 — Redis). Best of both worlds.

## Cache Hit Ratio
The single most important metric. Target 95%+ for read-heavy systems.

```
Hit Ratio = cache_hits / (cache_hits + cache_misses)
```

Improve it by: increasing cache size, tuning TTL, caching at the right granularity, pre-warming.

## Common Cache Failures

### Cache Stampede (Thundering Herd)
See above. Mitigations: locking, stale-while-revalidate, probabilistic early expiration, request coalescing (single-flight).

### Cache Penetration
Requests for keys that **don't exist** in the DB bypass the cache and hit the DB every time. Common attack vector.
- **Solution**: Cache the negative result (`null` with a short TTL), or use a Bloom filter to quickly reject non-existent keys.

### Cache Avalanche
Many keys expire at the same time (e.g., all set with TTL=3600 at deploy time), causing a sudden burst of DB load.
- **Solution**: Add jitter to TTL (`base_ttl + random(0, 0.1 * base_ttl)`), staggered warm-up.

### Hot Key
A single popular key (celebrity user, viral product) overwhelms the cache shard holding it.
- **Solution**: Replicate the hot key across multiple shards (key suffixing: `key:0`, `key:1`...), use a local in-process cache layer (L1) in front of Redis, or use a CDN for the key.

## Anti-Patterns
- **Caching everything**: Wastes memory, increases stale data risk.
- **No TTL**: Data lives forever, eventually all stale.
- **Cache as primary store**: If cache goes down, system breaks. Cache should always be a "best effort" layer.
- **Ignoring cache warming on deploy**: Cold cache after deploy = all traffic hits DB.
- **Synchronous TTL=0**: Re-fetching on every miss negates caching entirely under high traffic.

## Possible Interview Questions
1. "How would you design a caching layer for a social media feed?"
2. "Your cache hit ratio is 60%. How do you improve it?"
3. "How do you handle cache invalidation when you have 100 services writing to the same DB?"
4. "Explain the thundering herd problem and how to solve it."
5. "When would you choose write-through vs write-behind?"
6. "How do you keep cache and DB consistent in a distributed system?"
7. "What happens to your system when the cache goes down? How do you recover?"
8. "How would you design a multi-level caching system?"
