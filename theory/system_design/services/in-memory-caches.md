# In-Memory Caches (Redis, Memcached)

## What They Are
In-memory data stores that provide sub-millisecond latency for read/write operations. Used as caching layers, session stores, real-time leaderboards, message brokers, and more.

## Redis vs Memcached

| Feature | Redis | Memcached |
|---------|-------|-----------|
| Data structures | Strings, lists, sets, sorted sets, hashes, streams, bitmaps, HyperLogLog | Strings only |
| Persistence | RDB snapshots + AOF (append-only file) | None (pure cache) |
| Replication | Primary-replica | None (client-side sharding) |
| Clustering | Redis Cluster (auto-sharding) | Client-side only |
| Pub/Sub | Built-in | No |
| Lua scripting | Yes (atomic operations) | No |
| Transactions | MULTI/EXEC (optimistic) | CAS (check-and-set) |
| Threading | Single-threaded (event loop) + I/O threads in v6+ | Multi-threaded |
| Memory efficiency | Higher overhead per key | More efficient for simple strings |

**Rule of thumb**: Use Redis unless you have a specific reason for Memcached (extreme simplicity, multi-threaded performance for string-only workloads).

## Redis Data Structures and Use Cases

| Structure | Use Case |
|-----------|----------|
| **String** | Caching, counters, session data, rate limiting |
| **List** | Message queues, activity feeds, recent items |
| **Set** | Unique visitors, tags, social graphs (mutual friends) |
| **Sorted Set** | Leaderboards, priority queues, range queries by score |
| **Hash** | Object storage (user profiles), partial field updates |
| **Stream** | Event sourcing, message queues with consumer groups |
| **Bitmap** | Feature flags, daily active users tracking |
| **HyperLogLog** | Cardinality estimation (unique counts) with ~12KB memory |

## Redis Persistence

### RDB (Snapshotting)
Point-in-time snapshots at configured intervals. Fast restart but loses data since last snapshot.

### AOF (Append-Only File)
Logs every write operation. Three sync policies:
- `always`: Fsync every write. Safest, slowest.
- `everysec`: Fsync every second. Good balance (default).
- `no`: OS decides when to flush. Fastest, least safe.

### Best Practice
Use both: AOF for durability, RDB for fast restarts and backups.

## Redis Clustering

### Redis Sentinel
Monitoring, notification, automatic failover for Redis primary-replica setups. Not true clustering (no sharding).

### Redis Cluster
Auto-sharding across multiple nodes using 16,384 hash slots. Each key maps to a slot; slots are distributed across nodes.
- Minimum 3 primary nodes (recommended 6: 3 primary + 3 replica)
- Multi-key operations only work if all keys are on the same node (use hash tags: `{user:123}:profile` and `{user:123}:settings` go to the same slot)

## Redis Anti-Patterns
- **Big keys**: A single key with millions of elements. Blocks the event loop on access/delete. Break into smaller keys.
- **Using as primary database**: Redis is a cache first. Data loss is possible. Always have a source of truth elsewhere.
- **KEYS command in production**: Scans all keys, blocks the server. Use `SCAN` instead.
- **No TTL on cache keys**: Memory fills up, eviction becomes unpredictable. Always set TTLs.
- **Hot key**: One key getting 100K QPS while others get 100. Solutions: read replicas, local caching, key splitting.

## Eviction Policies
When Redis memory is full, it evicts keys based on the configured policy:
- `noeviction`: Return error on new writes (default)
- `allkeys-lru`: Evict least recently used key (most common for caching)
- `volatile-lru`: Evict LRU key with a TTL set
- `allkeys-random`: Random eviction
- `volatile-ttl`: Evict key closest to expiration

## Possible Interview Questions
1. "How would you implement a real-time leaderboard? What data structure would you use?"
2. "Compare Redis and Memcached. When would you use each?"
3. "How does Redis Cluster handle sharding?"
4. "You have a hot key in Redis getting 500K QPS. How do you handle it?"
5. "How do you ensure Redis data survives a restart?"
6. "Design a rate limiter using Redis."
7. "How would you implement distributed locking with Redis?"
8. "What happens when Redis runs out of memory?"
