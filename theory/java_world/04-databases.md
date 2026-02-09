# Databases — Senior Engineer Interview Preparation

---

## 1. Relational Database Internals

### ACID Properties

| Property | Meaning | Implementation |
|----------|---------|----------------|
| **Atomicity** | All or nothing | Undo/redo logs (WAL) |
| **Consistency** | Valid state transitions | Constraints, triggers, application logic |
| **Isolation** | Concurrent txns don't interfere | Locking, MVCC |
| **Durability** | Committed data survives crash | WAL flushed to disk, replication |

### Transaction Isolation Levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|-------|-----------|--------------------|--------------|----|
| READ UNCOMMITTED | ✅ Yes | ✅ Yes | ✅ Yes | Fastest |
| READ COMMITTED (default PG, Oracle) | ❌ No | ✅ Yes | ✅ Yes | Good |
| REPEATABLE READ (default MySQL InnoDB) | ❌ No | ❌ No | ✅ Yes (❌ in MySQL*) | Moderate |
| SERIALIZABLE | ❌ No | ❌ No | ❌ No | Slowest |

*MySQL InnoDB's REPEATABLE READ uses gap locks to also prevent phantom reads in most cases.

**MVCC (Multi-Version Concurrency Control)**:
- PostgreSQL: Each row version has `xmin` (creating txn) and `xmax` (deleting txn). Readers see a consistent snapshot. Dead tuples cleaned by VACUUM.
- MySQL InnoDB: Undo log stores previous versions. Rollback segments recreate older versions on demand.

**Key difference**: PostgreSQL stores all versions in the main table (requires VACUUM). InnoDB stores current version in table, old versions in undo space (no VACUUM needed, but long transactions hold undo space).

### Locking

**Lock Types**:
- **Shared (S)**: Multiple transactions can read simultaneously
- **Exclusive (X)**: Only one transaction can write
- **Intent Locks**: Table-level signals that row-level locks are held (IS, IX)
- **Gap Locks** (InnoDB): Lock the gap between index records to prevent phantom reads
- **Next-Key Locks**: Combination of record lock + gap lock

**Deadlock Detection**: The DB maintains a wait-for graph. When a cycle is detected, one transaction is chosen as the victim and rolled back.

```sql
-- Deadlock scenario
-- Transaction A:
UPDATE accounts SET balance = balance - 100 WHERE id = 1; -- Locks row 1
UPDATE accounts SET balance = balance + 100 WHERE id = 2; -- Waits for row 2

-- Transaction B:
UPDATE accounts SET balance = balance - 50 WHERE id = 2;  -- Locks row 2
UPDATE accounts SET balance = balance + 50 WHERE id = 1;  -- Waits for row 1 → DEADLOCK

-- Prevention: Always lock resources in a consistent order (by id)
```

---

## 2. Indexing Deep Dive

### B+ Tree (Default Index)

The standard index in most RDBMS. Internal nodes store keys for routing, leaf nodes store keys + pointers to rows (or row data in clustered index). Leaf nodes are linked for range scans.

```
                  [50]
                /      \
          [20, 35]      [70, 85]
         /   |   \     /   |   \
     [10,15][20,25][35,40][50,60][70,80][85,95]  ← Leaf nodes (linked list)
```

**B+ Tree characteristics**: O(log n) lookup, range scans efficient via leaf links, balanced (all leaves at same depth), typically 3-4 levels deep for millions of rows.

### Clustered vs Non-Clustered Index

| Aspect | Clustered | Non-Clustered (Secondary) |
|--------|-----------|--------------------------|
| Data storage | Rows stored in index order | Separate structure, points to row |
| Per table limit | ONE (usually PK) | Many |
| Range queries | Very fast (data is physically adjacent) | Requires "bookmark lookup" to main table |
| Insert performance | Slower if not sequential | Faster (append to separate structure) |

**InnoDB**: Primary key IS the clustered index. Secondary indexes store the PK value (not row pointer) — so PK lookups happen after secondary index lookup. This is why small PKs (int/bigint) are preferred over UUIDs for InnoDB.

### Index Types

| Type | Use Case | Example |
|------|----------|---------|
| B+ Tree | General purpose, range queries | `CREATE INDEX idx ON orders(created_at)` |
| Hash | Exact match only (no range) | Memory-optimized tables |
| GIN (Generalized Inverted) | Full-text search, arrays, JSONB | `CREATE INDEX idx ON docs USING GIN(tags)` |
| GiST | Geometric, range types, full-text | PostGIS spatial queries |
| BRIN (Block Range) | Very large, naturally ordered tables | Time-series data |
| Partial | Subset of rows | `CREATE INDEX idx ON orders(id) WHERE status = 'PENDING'` |
| Covering | All query columns in index | `CREATE INDEX idx ON orders(customer_id) INCLUDE (total, status)` |

### Composite Index Design

**Leftmost prefix rule**: A composite index `(a, b, c)` supports queries on `(a)`, `(a, b)`, and `(a, b, c)`, but NOT `(b)`, `(c)`, or `(b, c)` alone.

**Column ordering heuristic**:
1. Equality conditions first (high selectivity)
2. Range condition last
3. Columns for ORDER BY / GROUP BY in between

```sql
-- Query: WHERE status = 'ACTIVE' AND region = 'US' AND created_at > '2024-01-01' ORDER BY created_at
-- Best index:
CREATE INDEX idx ON orders(status, region, created_at);
-- status (equality) → region (equality) → created_at (range + ordering)
```

### EXPLAIN / Query Plan Analysis

```sql
EXPLAIN ANALYZE
SELECT o.id, o.total, c.name
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.status = 'PENDING'
  AND o.created_at > '2024-01-01';

-- Key things to look for:
-- Seq Scan        → Missing index or low selectivity (full table scan)
-- Index Scan      → Good, using index
-- Index Only Scan → Best, all data from index (covering index)
-- Bitmap Scan     → Multiple index conditions combined
-- Nested Loop     → Good for small result sets (index lookup per row)
-- Hash Join       → Good for large result sets (builds hash table)
-- Merge Join      → Good when both inputs are sorted
-- Sort            → Can be expensive; check if index can provide order
-- Rows            → Compare estimated vs actual; big difference → stale statistics
```

**Index Anti-Patterns**:
```sql
-- Function on indexed column prevents index usage
WHERE YEAR(created_at) = 2024        -- BAD: full scan
WHERE created_at >= '2024-01-01'
  AND created_at < '2025-01-01'      -- GOOD: uses index

-- Implicit type conversion
WHERE varchar_column = 12345          -- BAD: implicit cast, no index
WHERE varchar_column = '12345'        -- GOOD

-- LIKE with leading wildcard
WHERE name LIKE '%smith'              -- BAD: full scan
WHERE name LIKE 'smith%'             -- GOOD: uses index

-- OR conditions on different columns
WHERE status = 'A' OR region = 'US'  -- May not use indexes efficiently
-- Fix: UNION of two queries, or bitmap scan
```

---

## 3. Query Optimization

### JOIN Performance

| Join Type | When Used | Characteristics |
|-----------|-----------|-----------------|
| Nested Loop | Small outer, indexed inner | O(n × m) worst case, great with index |
| Hash Join | Large tables, equality join | O(n + m), needs memory for hash table |
| Merge Join | Pre-sorted inputs | O(n + m), efficient for large sorted sets |

### Pagination Strategies

```sql
-- OFFSET-based (simple but slow for deep pages)
SELECT * FROM orders ORDER BY id LIMIT 20 OFFSET 10000;
-- Problem: DB must scan and discard 10000 rows

-- Keyset/Cursor-based (fast, consistent)
SELECT * FROM orders
WHERE id > :last_seen_id
ORDER BY id
LIMIT 20;
-- Always fast regardless of page depth

-- For complex sorting:
SELECT * FROM orders
WHERE (created_at, id) > (:last_date, :last_id)
ORDER BY created_at, id
LIMIT 20;
```

### Common Table Expressions (CTEs) & Window Functions

```sql
-- Recursive CTE: org hierarchy
WITH RECURSIVE org_tree AS (
    SELECT id, name, manager_id, 1 AS depth
    FROM employees WHERE manager_id IS NULL
    UNION ALL
    SELECT e.id, e.name, e.manager_id, t.depth + 1
    FROM employees e JOIN org_tree t ON e.manager_id = t.id
)
SELECT * FROM org_tree;

-- Window functions: running totals, rankings
SELECT
    id,
    customer_id,
    total,
    SUM(total) OVER (PARTITION BY customer_id ORDER BY created_at) AS running_total,
    ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY total DESC) AS rank,
    LAG(total) OVER (PARTITION BY customer_id ORDER BY created_at) AS prev_order_total
FROM orders;
```

---

## 4. Database Scaling

### Vertical vs Horizontal Scaling

| Strategy | Approach | Limits |
|----------|----------|--------|
| Vertical | Bigger machine (more RAM, CPU, SSD) | Hardware ceiling, single point of failure |
| Read Replicas | Route reads to replicas | Replication lag, eventual consistency |
| Sharding | Partition data across databases | Complex queries, cross-shard joins |
| Caching | Redis/Memcached layer | Cache invalidation, stale data |

### Read Replicas

```
    Writes          Reads
      │               │
      ▼               ▼
  ┌────────┐    ┌──────────┐
  │ Primary │───→│ Replica 1│  (async replication)
  │  (RW)   │───→│ Replica 2│
  └────────┘    └──────────┘
```

**Replication Lag Risks**: User writes data, immediately reads from replica → sees stale data. Mitigations:
- Read-your-writes consistency: Route user's reads to primary for N seconds after their write
- Sticky sessions to primary for critical flows
- Monitor replication lag and route accordingly

### Sharding Strategies

**Hash-based**: `shard = hash(shard_key) % num_shards`. Even distribution but resharding requires data migration.

**Range-based**: Shard by date range or ID range. Easy to add new shards. Risk: hotspots if recent data is more accessed.

**Directory-based**: Lookup table maps shard key → shard. Most flexible but lookup table is a bottleneck/SPOF.

**Shard Key Selection** (critical decision):
- High cardinality (many distinct values)
- Even distribution
- Used in most queries (avoid cross-shard queries)
- Example: tenant_id for multi-tenant SaaS, user_id for social apps

**Cross-shard query problem**: JOINs across shards are extremely expensive. Denormalize data or use an aggregation layer.

### Connection Pooling

```yaml
# HikariCP (Spring Boot default, fastest pool)
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # Connections per instance
      minimum-idle: 5
      idle-timeout: 300000           # 5 minutes
      max-lifetime: 1800000          # 30 minutes (< DB wait_timeout)
      connection-timeout: 30000      # 30 seconds to get connection
      leak-detection-threshold: 60000 # Warn if connection held > 60s
```

**Pool Sizing Formula** (from HikariCP wiki):
`connections = ((core_count * 2) + effective_spindle_count)`
For SSD: typically 10-20 connections per application instance. More connections ≠ better performance due to context switching and lock contention.

**Risk**: Multiple microservice instances × pool size can overwhelm the database. Use PgBouncer or ProxySQL as a connection multiplexer.

---

## 5. NoSQL Databases

### When to Use NoSQL

| Need | Best Fit |
|------|----------|
| Flexible schema, document storage | MongoDB, DocumentDB |
| High-throughput key-value | DynamoDB, Redis |
| Wide-column, time-series | Cassandra, ScyllaDB |
| Graph relationships | Neo4j, Neptune |
| Full-text search | Elasticsearch, OpenSearch |
| Caching | Redis, Memcached |

### CAP Theorem

A distributed system can guarantee at most **two** of three:
- **Consistency**: All nodes see same data at same time
- **Availability**: Every request gets a response
- **Partition Tolerance**: System works despite network partitions

In practice, network partitions DO happen, so the real choice is **CP** (consistent but may be unavailable) or **AP** (available but may be inconsistent).

| System | CAP | Notes |
|--------|-----|-------|
| PostgreSQL (single node) | CA | No partition tolerance (single node) |
| MongoDB | CP | Primary election on partition → unavailable briefly |
| Cassandra | AP | Tunable consistency (quorum reads/writes) |
| DynamoDB | AP | Eventually consistent reads (or strongly consistent option) |
| Redis Cluster | AP | Async replication → potential data loss on failover |

### Redis Deep Dive

**Data Structures**: String, List, Set, Sorted Set, Hash, HyperLogLog, Stream, Bitmap, Geo.

**Persistence Options**:
- **RDB (snapshots)**: Point-in-time snapshots at intervals. Fast restart, some data loss.
- **AOF (Append-Only File)**: Logs every write. More durable, slower restart.
- **RDB + AOF**: Best of both.

**Eviction Policies**: `noeviction` (error when full), `allkeys-lru`, `volatile-lru` (only keys with TTL), `allkeys-random`, `volatile-ttl` (shortest TTL first).

**Common Patterns**:

```java
// Distributed Lock (Redisson)
RLock lock = redisson.getLock("order-lock:" + orderId);
try {
    if (lock.tryLock(5, 30, TimeUnit.SECONDS)) { // wait 5s, hold 30s
        processOrder(orderId);
    }
} finally {
    lock.unlock();
}

// Cache-Aside Pattern
public User getUser(Long id) {
    String key = "user:" + id;
    User cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return cached;

    User user = userRepository.findById(id).orElseThrow();
    redisTemplate.opsForValue().set(key, user, Duration.ofMinutes(30));
    return user;
}

// Rate Limiting with Sorted Set (sliding window)
public boolean isAllowed(String clientId, int maxRequests, int windowSeconds) {
    String key = "rate:" + clientId;
    long now = System.currentTimeMillis();
    long windowStart = now - (windowSeconds * 1000L);

    redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
    Long count = redisTemplate.opsForZSet().zCard(key);

    if (count != null && count < maxRequests) {
        redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);
        redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        return true;
    }
    return false;
}
```

**Redis Cluster**: Data sharded across nodes using hash slots (16384 slots). Each key hashed to a slot. Minimum 3 masters + 3 replicas for HA.

**Risks**:
- **Cache stampede/thundering herd**: Many threads simultaneously miss cache and hit DB. Fix: distributed lock on cache miss, or probabilistic early expiration.
- **Cache penetration**: Queries for non-existent data always miss cache. Fix: cache null results with short TTL, or use Bloom filter.
- **Hot key**: Single key receiving disproportionate traffic. Fix: local cache (Caffeine), key replication, or sharding the hot key.

### MongoDB Essentials

**Document Model**: BSON documents (JSON-like), schema-flexible. Max document size 16MB.

**Indexing**: B-tree indexes, compound indexes (same leftmost prefix rule), text indexes, geospatial indexes, TTL indexes (auto-delete), unique indexes.

**Aggregation Pipeline**:
```javascript
db.orders.aggregate([
    { $match: { status: "completed", createdAt: { $gte: ISODate("2024-01-01") } } },
    { $group: { _id: "$customerId", totalSpent: { $sum: "$total" }, orderCount: { $sum: 1 } } },
    { $sort: { totalSpent: -1 } },
    { $limit: 10 }
]);
```

**Write Concern & Read Concern**:
- `w:1` — acknowledged by primary only (default)
- `w:majority` — acknowledged by majority of replica set
- `readConcern: "majority"` — only reads data acknowledged by majority
- `readConcern: "linearizable"` — strongest, reads reflect all majority-committed writes

---

## 6. Caching Strategies

| Strategy | Flow | Best For |
|----------|------|----------|
| **Cache-Aside** (Lazy) | App checks cache → miss → load from DB → write to cache | General purpose, read-heavy |
| **Read-Through** | Cache loads from DB on miss (transparent to app) | Simpler app code |
| **Write-Through** | Write to cache and DB synchronously | Strong consistency |
| **Write-Behind** (Write-Back) | Write to cache, async write to DB | High write throughput, risk of data loss |
| **Write-Around** | Write to DB only, invalidate cache | Write-heavy, reads of newly written data are rare |

### Cache Invalidation

**Time-based (TTL)**: Simple, eventual consistency. Choose TTL based on data staleness tolerance.

**Event-based**: DB change → publish event → invalidate cache. More complex but fresher data. Can use CDC (Debezium) for reliable invalidation.

**Versioned keys**: `user:123:v5`. Increment version on change. Old versions naturally expire.

---

## 7. Database Migration & Schema Evolution

### Flyway / Liquibase

```sql
-- Flyway migration: V2__add_email_index.sql
-- ALWAYS write backward-compatible migrations

-- Step 1: Add column as nullable (backward compatible)
ALTER TABLE users ADD COLUMN email VARCHAR(255);

-- Step 2: Backfill data (in batches for large tables)
UPDATE users SET email = username || '@example.com'
WHERE email IS NULL
LIMIT 10000; -- Run in batches

-- Step 3: Add constraint (after all data is backfilled)
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
CREATE UNIQUE INDEX CONCURRENTLY idx_users_email ON users(email);
-- CONCURRENTLY prevents locking the table (PostgreSQL)
```

### Zero-Downtime Schema Changes

**Expand-Contract Pattern**:
1. **Expand**: Add new column/table (both old and new code work)
2. **Migrate**: Backfill data, deploy new code that writes to both old and new
3. **Contract**: Remove old column/table after all code uses new schema

**Dangerous Operations** (can lock tables):
- `ALTER TABLE ... ADD COLUMN ... DEFAULT ...` (before PG 11, locks table; PG 11+ is instant)
- `CREATE INDEX` without `CONCURRENTLY` (locks writes)
- `ALTER TABLE ... ALTER COLUMN TYPE` (rewrites table)
- `ALTER TABLE ... ADD CONSTRAINT ... NOT NULL` (full table scan)

---

## 8. Hibernate / JPA Performance

### First & Second Level Cache

| Cache | Scope | Default | Notes |
|-------|-------|---------|-------|
| L1 | Session/EntityManager | ON (always) | Per-transaction, cleared on close |
| L2 | SessionFactory (shared) | OFF | Requires explicit config, cache provider |
| Query Cache | SessionFactory | OFF | Caches query results (invalidated on any write to table) |

```java
// Enable L2 cache
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE) // or NONSTRICT_READ_WRITE
public class Product { }

// application.yml
spring.jpa.properties.hibernate:
  cache.use_second_level_cache: true
  cache.use_query_cache: true
  cache.region.factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

**L2 Cache Risks**: Stale data if DB is modified outside Hibernate. Cache invalidation complexity in clustered environments. Not suitable for frequently updated entities.

### Lazy Loading & Open Session in View

**OSIV (Open Session in View)**: Keeps Hibernate session open through the entire request, allowing lazy loading in the view/controller layer. **Enabled by default in Spring Boot.**

**Why OSIV is controversial**:
- Pros: Convenient, no LazyInitializationException
- Cons: Database connection held for entire request (including view rendering), N+1 queries hidden in templates, harder to reason about transaction boundaries

```yaml
# Disable OSIV (recommended for production)
spring.jpa.open-in-view: false
```

### Batch Operations

```java
// Batch inserts (Hibernate)
spring.jpa.properties.hibernate:
  jdbc.batch_size: 50
  order_inserts: true
  order_updates: true

// IMPORTANT: Batch doesn't work with IDENTITY generator (MySQL auto_increment)
// Use SEQUENCE generator for batch inserts with PostgreSQL
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
@SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", allocationSize = 50)
private Long id;

// Bulk operations (bypass L1 cache)
@Modifying
@Query("UPDATE Order o SET o.status = :status WHERE o.createdAt < :date")
int bulkUpdateStatus(@Param("status") Status status, @Param("date") LocalDate date);
// WARNING: This doesn't update L1 cache — entities already loaded are stale
```

---

## 9. Common Senior Interview Questions

**Q: How would you optimize a slow query?**
1. Run EXPLAIN ANALYZE to understand the execution plan
2. Check for missing indexes (Seq Scan on large tables)
3. Check for stale statistics (`ANALYZE table`)
4. Look for N+1 patterns in application code
5. Consider denormalization or materialized views for complex reads
6. Check for lock contention (pg_stat_activity, slow_query_log)
7. Review connection pool settings and query timeouts
8. Consider caching frequently-accessed, rarely-changing data

**Q: UUID vs auto-increment for primary keys?**
Auto-increment: Sequential, smaller (8 bytes), better for B+ tree (append-only, no page splits), better clustered index performance. Risk: predictable, leaks information, hard to merge across databases.
UUID: Globally unique, no coordination needed, good for distributed systems. Risk: 16 bytes, random insertion causes B+ tree page splits and fragmentation, poor clustered index performance. **Compromise**: UUIDv7 (time-ordered) gets global uniqueness with near-sequential ordering.

**Q: When would you denormalize?**
When read performance is critical and the data is read far more than written. Common patterns: materialized views for dashboards, embedding frequently-joined data, pre-computed aggregates. Always maintain a normalized source of truth and denormalize into read models (CQRS approach).

**Q: Explain the difference between optimistic and pessimistic locking.**
**Pessimistic**: `SELECT ... FOR UPDATE` — locks the row, others wait. Best when conflicts are frequent. Risk: deadlocks, reduced concurrency.
**Optimistic**: Version column (`@Version`). Read without lock, on update check version matches. If not → `OptimisticLockException`, retry. Best when conflicts are rare. Risk: retry overhead under high contention.

```java
@Entity
public class Product {
    @Version
    private Long version; // Hibernate auto-increments on update
}
```

**Q: How do you handle database connections in a microservices architecture?**
Each service owns its database (database-per-service pattern). Use connection pooling (HikariCP). Size pools carefully: instances × pool_size < DB max_connections. Use connection multiplexers (PgBouncer) if needed. Monitor connection usage. Consider serverless databases (Aurora Serverless) for variable workloads.
