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

**PostgreSQL serialization anomaly** (SERIALIZABLE-only risk): Two transactions each reading a set and writing based on it can both commit under SERIALIZABLE using SSI (Serializable Snapshot Isolation) only if no dangerous cycle is detected. If one is detected, PG throws `serialization_failure` (SQLSTATE 40001) — **application must retry**. Under REPEATABLE READ (PG's snapshot isolation), this anomaly can silently produce inconsistent writes. Interview gotcha: PG REPEATABLE READ ≠ SQL standard REPEATABLE READ; it is actually snapshot isolation and is stronger for reads but weaker for write skew.

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

### Connection Pooling (HikariCP)

```yaml
# HikariCP (Spring Boot default, fastest pool). 2026 baseline settings:
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # Connections per instance (see formulas below)
      minimum-idle: 20               # Set == max for stable prod workloads (avoid churn)
      connection-timeout: 30000      # 30s to acquire; fail fast
      idle-timeout: 600000           # 10 min — only applies when minimumIdle < maximumPoolSize
      max-lifetime: 1800000          # 30 min, MUST be < DB wait_timeout / idle_in_transaction_timeout
      keepalive-time: 120000         # Pings idle conns; prevents stale TCP / NAT eviction
      leak-detection-threshold: 60000 # Warn if connection held > 60s (prod-safe)
      data-source-properties:
        # PostgreSQL / MySQL: enable server-side prepared statement caching
        cachePrepStmts: true
        prepStmtCacheSize: 250
        prepStmtCacheSqlLimit: 2048
```

**Pool Sizing Formulas**:
- **Throughput heuristic (HikariCP wiki)**: `connections = ((core_count * 2) + effective_spindle_count)` — SSD treats spindles as ~1. Typical: 10-20 per instance.
- **Deadlock-avoidance minimum**: `pool_size = Tn × (Cm − 1) + 1` where `Tn` = max concurrent threads, `Cm` = max simultaneous connections held by a single thread.
- **Rule**: total DB connections = `app_instances × max_pool_size` must stay well below `max_connections` on the DB. PostgreSQL: budget ~100 active connections per CPU core max; use PgBouncer (transaction-pooling) to multiplex beyond that.

**Virtual threads (Java 21+) + JDBC**:
- JDBC is blocking, but a blocked virtual thread unmounts — carriers are free. Since JEP 491 (Java 24), even `synchronized` blocks no longer pin carriers, so legacy drivers and HikariCP internals scale cleanly on virtual threads.
- **Key insight**: virtual threads give you unbounded *request* concurrency, but the DB still has finite *query* concurrency. The bottleneck shifts entirely to the pool and DB — if `max-pool-size = 20`, that's still your query parallelism ceiling. Raise the pool modestly (e.g. 32-64) but expect diminishing returns; disk I/O and DB locks dominate.
- **Pitfall**: a JDBC `SynchronousQueue`-style wait for a connection was historically a pinning site on virtual threads (pre-Java 24). On Java 24+ this is fixed; on 21-23 prefer HikariCP 5.1+ (uses non-pinning synchronizers).
- Enable with `spring.threads.virtual.enabled=true` (Spring Boot 3.2+).

**Risk**: Multiple microservice instances × pool size can overwhelm the database. Use **PgBouncer** (transaction or session pooling) or **ProxySQL** as a connection multiplexer. Beware: PgBouncer transaction pooling breaks prepared statements (use `prepareThreshold=0` on JDBC URL or PgBouncer 1.21+ protocol-level prepared statement support).

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

**Indexing**: B-tree indexes, compound indexes (same leftmost prefix rule), text indexes, geospatial indexes, TTL indexes (auto-delete), unique indexes, **vector indexes (Atlas Vector Search)** for AI/RAG workloads.

**Aggregation Pipeline**:
```javascript
db.orders.aggregate([
    { $match: { status: "completed", createdAt: { $gte: ISODate("2025-01-01") } } },
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

**MongoDB Java Driver 5.x (2026) — key additions**:
- **CSOT (Client-Side Operation Timeout)**: `MongoClientSettings.builder().timeout(Duration.ofSeconds(2))` — single timeout that applies to *every* operation (including retries), replacing the legacy `socketTimeoutMS` + `serverSelectionTimeoutMS` sprawl. Critical for well-behaved cascading timeouts in microservices.
- **`BinaryVector` / BSON subtype 9** (driver 5.3+): compact vector storage for Atlas Vector Search; ~4× smaller than `List<Double>`.
- **Kubernetes OIDC** workload-identity authentication (5.4+), **Azure/GCP federated identity** (5.5+).
- **Reactive Streams driver** is the canonical async entry point; avoid the deprecated callback-style `AsyncMongoClient`.

```java
// Modern (5.x) sync driver with CSOT + POJO codec
MongoClient client = MongoClients.create(MongoClientSettings.builder()
    .applyConnectionString(new ConnectionString("mongodb+srv://..."))
    .timeout(Duration.ofSeconds(2))                 // CSOT
    .codecRegistry(fromRegistries(getDefaultCodecRegistry(),
        fromProviders(PojoCodecProvider.builder().automatic(true).build())))
    .build());
MongoCollection<Order> coll = client.getDatabase("shop").getCollection("orders", Order.class);
```

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
- `ALTER TABLE ... ADD COLUMN ... DEFAULT ...` (before PG 11, locks table; PG 11+ is instant for non-volatile defaults)
- `CREATE INDEX` without `CONCURRENTLY` (locks writes)
- `ALTER TABLE ... ALTER COLUMN TYPE` (rewrites table)
- `ALTER TABLE ... ADD CONSTRAINT ... NOT NULL` (full table scan; in PG 12+, use `ADD CONSTRAINT ... CHECK (...) NOT VALID` then `VALIDATE CONSTRAINT` to avoid full-table lock)

### Flyway vs Liquibase (2026)

| Aspect | Flyway | Liquibase |
|--------|--------|-----------|
| Changeset format | SQL files (`V1__...sql`) + Java | XML / YAML / JSON / SQL |
| Undo / rollback | Enterprise only (Flyway Teams) | Built-in (community) |
| DB support | 50+ databases in 12.x | 60+ databases |
| Spring Boot | Auto-config reads `db/migration/` | Auto-config reads `db/changelog/` |
| Best for | SQL-first teams, monorepos, simple linear migrations | Multi-DB portability, branching changelogs |

**Spring Boot wiring** (Flyway — zero code):
```yaml
spring.flyway:
  enabled: true
  locations: classpath:db/migration
  baseline-on-migrate: true         # First deploy over existing DB
  out-of-order: false               # Reject out-of-sequence migrations in prod
  validate-migration-naming: true
```

**Golden rules**: migrations are immutable once shipped. To change a migration, write a new one. Keep them **additive and reversible-by-design** (expand/contract). For very large tables, run heavy DML out-of-band (e.g. `pg_repack`, `gh-ost`, Liquibase Pro reorg) — not inside a migration.

---

## 8. Hibernate / JPA Performance

### Jakarta Persistence & Hibernate Version Cheat Sheet (2026)

| Era | Package | Hibernate | Jakarta Persistence | Notes |
|-----|---------|-----------|---------------------|-------|
| Legacy | `javax.persistence` | ≤ 5.6 | JPA 2.2 | EOL; blocks Jakarta EE 10+ migration |
| Transitional | `jakarta.persistence` | 6.0–6.6 | Jakarta Persistence 3.1 | Spring Boot 3.0–3.3 default |
| Current | `jakarta.persistence` | **7.0+** | **Jakarta Persistence 3.2** | Spring Boot 3.4+; Java 17+ baseline, Java 21 recommended |

**Migration must-knows**: all `javax.persistence.*` imports → `jakarta.persistence.*`. Hibernate 6 rewrote the query engine — HQL now translates to a Semantic Query Model (SQM) before SQL, yielding much better SQL and fixing long-standing JPQL bugs. Hibernate 6+ drops `hbm.xml` mapping support (migrate to annotations/orm.xml).

### Jakarta Persistence 3.2 / Hibernate 7 Highlights

- **Type-safe bootstrap**: `Persistence.configure("pu").createEntityManagerFactory()` replaces string-property maps.
- **EntityGraph API overhaul**: new `addTreatedSubgraph(Attribute, Class)`, `addElementSubgraph`, and fluent type-safe graph construction; `addSubgraph(Attribute, Class)` and `addKeySubgraph` are deprecated for removal.
- **StatelessSession on EntityManager**: via `em.unwrap(StatelessSession.class)` — no L1 cache, no dirty checking, ideal for batch ETL and now covers nearly all ORM features in Hibernate 7.
- **Record-based DTO projections**: `select new com.acme.OrderDto(o.id, o.total) from Order o` supports Java `record` types natively.
- **Generic `@Id` via `GenerationType.UUID`** for UUIDv4/7 generation by the provider.
- **CriteriaBuilder.array() / tuple()**, better JSON functions, `SqlResultSetMapping` simplifications.

### First & Second Level Cache

| Cache | Scope | Default | Notes |
|-------|-------|---------|-------|
| L1 | Session/EntityManager | ON (always) | Per-transaction, cleared on close |
| L2 | SessionFactory (shared) | OFF | Requires explicit config + JCache provider |
| Query Cache | SessionFactory | OFF | Caches query results; invalidated on any write to involved tables |

```java
// Hibernate 6+/7: L2 cache (modern JCache-based setup — EHCache 3 or Infinispan)
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE) // READ_ONLY, NONSTRICT_READ_WRITE, TRANSACTIONAL
public class Product { }

// application.yml
spring.jpa.properties.hibernate:
  cache.use_second_level_cache: true
  cache.use_query_cache: true
  cache.region.factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
  javax.cache.provider: org.ehcache.jsr107.EhcacheCachingProvider
```

**Cache Concurrency Strategies** (pick per entity):
- `READ_ONLY` — immutable reference data (countries, currencies).
- `NONSTRICT_READ_WRITE` — stale reads acceptable; invalidates after commit (cheap, no locks).
- `READ_WRITE` — soft locks on write; tolerant of frequent updates. Most common choice.
- `TRANSACTIONAL` — full JTA 2PC; only with a JTA cache provider (Infinispan).

**L2 Cache Risks**: Stale data if DB is modified outside Hibernate (triggers, other services, bulk `UPDATE` queries). In clustered environments, pick an invalidation-cluster-aware provider (Infinispan invalidation mode, Hazelcast, Redis via Redisson L2 adapter). Avoid L2 on frequently-mutated, low-read-ratio entities — it often *hurts* throughput.

### Solving N+1 (detection + fixes)

```java
// Detection: Hibernate statistics + alertable counters
spring.jpa.properties.hibernate.generate_statistics: true
// Assert at test time:
assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(1);

// Dev-time fail-fast library (very common in 2026 stacks):
// implementation("com.github.vladmihalcea:hypersistence-utils-hibernate-63:3.x")
// — or Datasource-Proxy / Hibernate-Query-Profiler to flag implicit N+1 in tests.

// Fix 1: JPQL fetch join (no EntityGraph)
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findWithItems(@Param("id") Long id);

// Fix 2: @EntityGraph (declarative, composable, Jakarta Persistence 3.2 type-safe)
@EntityGraph(attributePaths = {"items", "customer"})
List<Order> findByStatus(Status status);

// Fix 3: @BatchSize — batches lazy loads (N+1 becomes N/batch + 1)
@OneToMany @BatchSize(size = 50) private List<Item> items;

// Fix 4: DTO projection — no entity, no lazy graph
interface OrderSummary { Long getId(); BigDecimal getTotal(); }
```

### Lazy Loading & Open Session in View

**OSIV (Open Session in View)**: Keeps the Hibernate session open through the entire HTTP request so view/controller code can trigger lazy loads. **Still enabled by default in Spring Boot 3.x** — you will get a WARN log on startup reminding you.

**Why OSIV is controversial**:
- Pros: Convenient, no `LazyInitializationException`.
- Cons: Connection (or at least a Session) held for the whole request including view render; N+1 queries hidden in templates; transaction boundaries bleed into the web layer; makes virtual-thread/async adoption painful.

```yaml
spring.jpa.open-in-view: false   # Disable OSIV in production
```

### Batch Operations

```yaml
spring.jpa.properties.hibernate:
  jdbc.batch_size: 50
  order_inserts: true
  order_updates: true
  jdbc.batch_versioned_data: true   # Enables batching for @Version entities
```

```java
// IMPORTANT: Batch doesn't work with IDENTITY generator (MySQL auto_increment)
// Use SEQUENCE (Postgres) or UUID to get true batched INSERTs
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
@SequenceGenerator(name = "order_seq", sequenceName = "order_sequence", allocationSize = 50)
private Long id;

// Bulk operations (bypass L1 cache)
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("UPDATE Order o SET o.status = :status WHERE o.createdAt < :date")
int bulkUpdateStatus(@Param("status") Status status, @Param("date") LocalDate date);
// WARNING: Still does not update the L2 cache — manually evict cache regions after bulk DML

// Streaming large result sets (avoid OOM on millions of rows)
@QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "1000"))
Stream<Order> findByStatus(Status status); // must be used inside a @Transactional(readOnly=true)
```

### Hibernate Reactive vs R2DBC vs JDBC (2026 guidance)

| Stack | Under the hood | When it wins | Watch-outs |
|-------|----------------|--------------|------------|
| JDBC + Virtual Threads | Blocking driver, vthread unmounts | Default for greenfield services on Java 21+; simplest mental model | Pool sizing, JEP 491 (Java 24) fixes `synchronized` pinning |
| Spring Data JDBC / jOOQ | Thin, explicit SQL | No lazy-loading footguns, predictable perf | Less ORM sugar |
| Hibernate ORM 7 + JDBC | Full ORM | Rich mapping, L2 cache, CriteriaBuilder | N+1, OSIV, batch caveats |
| R2DBC (Spring Data R2DBC) | Non-blocking reactive driver | Reactor/WebFlux end-to-end | Immature drivers; benchmarks often show JDBC+vthreads equaling or beating R2DBC |
| Hibernate Reactive | Built on Vert.x SQL client (not R2DBC) | Reactive ORM when WebFlux is mandated | Smaller feature surface than Hibernate ORM |

**2026 consensus**: unless the entire app is reactive end-to-end, prefer **JDBC + virtual threads + Hibernate 7 (or jOOQ)** over R2DBC — virtual threads removed most of R2DBC's throughput advantage and reactive debugging/tooling is still harder.

---

## 9. PostgreSQL-Specific Features Senior Engineers Get Asked About

- **Logical replication & publication/subscription**: row-level stream per table; underpins blue/green DB upgrades and zero-downtime major-version migrations. Debezium uses the same WAL via `pgoutput`.
- **JSONB + GIN indexes + path ops**: `CREATE INDEX ON docs USING GIN (data jsonb_path_ops)` for containment queries (`@>`). Prefer JSONB over JSON (binary, deduped keys, indexable).
- **Generated columns**: `GENERATED ALWAYS AS (lower(email)) STORED` — indexable derived data without triggers.
- **Partitioning**: declarative `PARTITION BY RANGE/LIST/HASH`. Partition pruning + constraint exclusion make time-series queries cheap. Combine with `BRIN` on partition key.
- **`SKIP LOCKED`**: `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 100` implements lock-free work queues (the classic "PostgreSQL as a message queue" pattern).
- **Advisory locks**: `pg_advisory_xact_lock(bigint)` — application-level mutexes scoped to a transaction, handy for cron/singleton jobs across replicas.
- **`pg_stat_statements`** + `auto_explain`: mandatory prod observability; track top queries by `total_exec_time` and `mean_plan_time`.
- **Serializable Snapshot Isolation (SSI)**: true serializable without range locks — cheaper than traditional 2PL but retries under contention.
- **Upserts**: `INSERT ... ON CONFLICT (key) DO UPDATE SET ... RETURNING *` — atomic, returns the resulting row; prefer over select-then-insert.

---

## 10. Change Data Capture (CDC) & the Outbox Pattern

**The dual-write problem**: a service that must (a) write to its DB and (b) publish an event to Kafka cannot do both atomically — if the broker is down after the commit, the event is lost; if the commit fails after publish, consumers see phantom events.

**Transactional Outbox + Debezium** (the 2026-standard fix):

```java
@Transactional
public void placeOrder(Order o) {
    orderRepository.save(o);
    outboxRepository.save(OutboxEvent.of(
        "order.created",            // aggregate type / topic routing key
        o.getId().toString(),       // aggregate id (Kafka partition key)
        orderEventMapper.toJson(o)  // payload
    ));
    // No Kafka call here — Debezium will tail WAL and publish.
}
```

**Debezium** runs as a Kafka Connect source connector, reads Postgres WAL (or MySQL binlog), and emits a Kafka record per outbox row. Use the **Debezium Outbox Event Router SMT** to strip the envelope and route to topics by `aggregate_type`. Guarantees: **at-least-once** delivery (consumers must be idempotent), preserves ordering per aggregate id.

**Alternatives**:
- **Listen/Notify + polling**: simpler but lossy under load and lacks backfill.
- **Event sourcing**: store events as the source of truth; CDC becomes unnecessary but mapping/query complexity explodes.
- **Kafka transactional producer + 2PC (XA)**: historically painful; almost no one uses it in modern Java stacks.

**Outbox table hygiene**: partition by day, `DELETE` or `TRUNCATE` old partitions after Debezium LSN has advanced past them. Never let it grow unbounded — WAL retention tracks the slowest consumer.

---

## 11. Multi-Tenancy Patterns

| Pattern | Isolation | Cost | Schema changes | Good for |
|---------|-----------|------|----------------|----------|
| Database-per-tenant | Strongest | Highest (connection pools × tenants) | Run migrations per DB | Regulated / enterprise tenants |
| Schema-per-tenant | Strong | Medium (one pool, `SET search_path`) | Migrations per schema | 10-1000 tenants, B2B SaaS |
| Discriminator column (shared schema) | Weakest (app-enforced) | Lowest | Normal migrations | Large-scale consumer SaaS |

**Hibernate 6+/7 (discriminator approach — finally first-class)**:

```java
@Entity
public class Invoice {
    @Id Long id;
    @TenantId String tenantId;        // Jakarta-era annotation; auto-filtered + auto-populated
    BigDecimal amount;
}

// Required beans:
@Bean CurrentTenantIdentifierResolver<String> tenantResolver() {
    return () -> TenantContext.getCurrent();      // typically from SecurityContext / JWT claim
}
// Hibernate auto-adds `WHERE tenant_id = ?` to every select/update/delete.
```

For database- or schema-per-tenant, implement `MultiTenantConnectionProvider` (returns the right `DataSource` per tenant id). Use an outer `Map<TenantId, HikariDataSource>` with lazy initialization — do **not** create one HikariDataSource per request.

**Pitfalls**: cross-tenant leaks via native queries that bypass filters; L2 cache keyed without tenant id; connection pools sized as if single-tenant (N tenants × pool = DB overload).

---

## 12. Vector Databases & AI Workloads (Java side)

AI/RAG apps need similarity search over embeddings. The two mainstream Java options in 2026:

**PostgreSQL + pgvector** (default choice for teams already on Postgres):
```sql
CREATE EXTENSION vector;
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    embedding VECTOR(1536)           -- OpenAI ada-002 dim; use 3072 for text-embedding-3-large
);
-- HNSW index (approx NN, fast). IVFFlat is the older alternative.
CREATE INDEX ON documents USING hnsw (embedding vector_cosine_ops);
-- Top-5 nearest neighbors
SELECT id, content FROM documents ORDER BY embedding <=> $1 LIMIT 5;
```

**Spring AI (`spring-ai-pgvector-store`)** auto-configures the table, HNSW index, and a `VectorStore` bean:
```java
@Bean VectorStore vectorStore(JdbcTemplate jt, EmbeddingModel model) {
    return PgVectorStore.builder(jt, model)
        .dimensions(1536).distanceType(COSINE).indexType(HNSW).build();
}
// Usage
vectorStore.add(List.of(new Document("Apache Kafka is a log...", Map.of("src","wiki"))));
List<Document> hits = vectorStore.similaritySearch(SearchRequest.query("streaming platform").topK(5));
```

**When to reach for a dedicated vector DB** (Pinecone, Weaviate, Qdrant, Milvus) instead of pgvector: billions of vectors, hybrid dense+sparse search, heavy metadata filtering at low latency, or multi-tenant shards of vectors. For most Java backends, pgvector is enough and avoids another datastore.

**MongoDB Atlas Vector Search** is the same story on the Mongo side — Atlas-hosted HNSW on a BSON field; driver 5.3+ adds `BinaryVector` to store embeddings compactly.

---

## 13. Common Senior Interview Questions

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
Each service owns its database (database-per-service pattern). Use connection pooling (HikariCP). Size pools carefully: instances × pool_size < DB max_connections. Use connection multiplexers (PgBouncer transaction pooling) for high fan-out, but be aware it breaks server-side prepared statements on older drivers. Monitor connection usage and LSN lag. Consider serverless databases (Aurora Serverless v2, Neon, Planetscale) for variable workloads.

**Q: How do virtual threads change your database strategy?**
They don't change *what* you should use — JDBC is still fine — but they do change *how you size*. Carrier threads are no longer the bottleneck, so request concurrency becomes effectively unbounded; the DB connection pool and the DB itself become the only throttle. Practical implications: (1) keep `maximumPoolSize` conservative (raise modestly, not 10×); (2) add backpressure (bulkhead / semaphore) at the request layer to avoid burying the DB; (3) on Java 21-23, upgrade HikariCP to 5.1+ to avoid pinning on `synchronized`; Java 24+ (JEP 491) makes this a non-issue; (4) disable OSIV — long-lived sessions across async hops defeat the point.

**Q: How would you guarantee an event is published whenever a row is committed?**
Transactional Outbox + CDC (Debezium). Write the business row and an `outbox` row in the same transaction; Debezium tails the WAL/binlog and publishes to Kafka. Guarantees at-least-once, partition-ordered by aggregate id. Consumers must be idempotent (dedup key in the payload). Alternative without CDC: a polling worker with `SELECT ... FOR UPDATE SKIP LOCKED` on the outbox table — simpler but laggier.

**Q: You have a 2 TB table with growing write volume — what's your scaling playbook?**
In order: (1) verify it's really write-bound (not index bloat or autovacuum starvation); (2) declarative partitioning (by time or tenant) to keep hot sets small and enable parallel maintenance; (3) offload read-heavy analytics to a replica or columnar store (ClickHouse, DuckDB, Postgres with pg_duckdb); (4) archive cold partitions to object storage; (5) only then shard — by tenant_id for SaaS, by user_id for consumer apps. Avoid sharding as the first step; it forces cross-shard complexity into every query.

---

## 14. Deep Topics — Expanded

> Cross-refs: deep dives on indexing strategy and cache coherence live in
> [`theory/system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md)
> and [`theory/system_design/patterns/caching.md`](../system_design/patterns/caching.md).

### 14.1 Isolation Anomalies in Practice

Three anomalies senior engineers must be able to narrate verbally, with DB-specific behaviour:

**Non-Repeatable Read** — same row read twice in a txn returns different data.
```sql
-- Postgres (READ COMMITTED, the default) — anomaly reproduces
-- Session A                              | Session B
BEGIN;                                    |
SELECT balance FROM acct WHERE id=1;      |  -- reads 100
                                          | UPDATE acct SET balance=200 WHERE id=1;
                                          | COMMIT;
SELECT balance FROM acct WHERE id=1;      |  -- reads 200  <-- non-repeatable
COMMIT;
-- Fix: SET TRANSACTION ISOLATION LEVEL REPEATABLE READ (PG snapshot isolation)
```

**Phantom Read** — a re-executed range query returns new rows committed by another txn.
```sql
-- MySQL InnoDB at REPEATABLE READ (default) — prevented by next-key/gap locks
SELECT * FROM orders WHERE status='NEW' FOR UPDATE; -- locks gap
-- Another session INSERTing a NEW order blocks until commit.
-- Postgres REPEATABLE READ (snapshot) — the re-read still sees the old snapshot,
-- so phantom is invisible to SELECT but write-skew remains possible (need SERIALIZABLE).
```

**Lost Update** — two txns read the same value and both write based on it; one win silently overwrites.
```sql
-- Classic "increment counter" bug at READ COMMITTED:
-- Both sessions read counter=5, both write 6 — one update is lost.
-- Fix A (pessimistic): SELECT counter FROM t WHERE id=1 FOR UPDATE;
-- Fix B (optimistic):  UPDATE t SET counter=counter+1 WHERE id=1 AND version=:v;
-- Fix C (atomic SQL):  UPDATE t SET counter=counter+1 WHERE id=1;  -- single statement, row-locked
```

**Write skew** (SSI territory): two txns read a set, each writes disjoint rows based on the read — both commit, invariants broken. Only `SERIALIZABLE` catches it. MySQL REPEATABLE READ does NOT; Postgres REPEATABLE READ does NOT — escalate to `SERIALIZABLE` (PG) and **retry on SQLSTATE 40001**.

### 14.2 MVCC vs Pessimistic vs Optimistic — When Each Wins

| Concern | MVCC (read path) | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version`) |
|---------|------------------|-----------------------------|--------------------------|
| Reader blocks writer? | No | Depends on lock mode | No |
| Writer blocks writer? | Yes (row-level) | Yes (explicit) | No — detected at commit |
| Conflict frequency fit | any | high contention, short txns | low contention, UI/batch |
| Failure mode | none (readers never wait) | deadlock / long waits | `OptimisticLockException`, retry |
| Cost under load | version bloat (VACUUM) | lock waits, deadlocks | wasted work on retry |

```java
// JPA optimistic locking — the interview-favourite
@Entity class Product {
    @Id Long id;
    @Version Long version;    // auto-bumped on every UPDATE; WHERE version = ? enforced by Hibernate
    BigDecimal price;
}

@Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = 3,
           backoff = @Backoff(delay = 50, multiplier = 2, random = true))
@Transactional
public void applyDiscount(Long id, BigDecimal pct) {
    Product p = repo.findById(id).orElseThrow();
    p.setPrice(p.getPrice().multiply(BigDecimal.ONE.subtract(pct)));
    // commit: UPDATE product SET price=?, version=version+1 WHERE id=? AND version=?
}
```

### 14.3 Reading EXPLAIN / EXPLAIN ANALYZE

Pattern recognition cheatsheet (Postgres syntax; MySQL analogue is `EXPLAIN ANALYZE` as of 8.0+):

| Node | Normal | Red flag |
|------|--------|----------|
| `Seq Scan` | small table, low selectivity after filter | large table + few matching rows → missing index |
| `Index Scan` | selective predicate on indexed column | but compare `rows removed by filter` — if huge, index isn't selective enough |
| `Index Only Scan` | best; fully covered query | if `Heap Fetches` is high → VACUUM needed (visibility map) |
| `Bitmap Index Scan` + `Bitmap Heap Scan` | multiple conditions combined | many heap fetches → consider covering index |
| `Nested Loop` | small outer side, indexed inner | large outer × large inner → switch to hash join (raise `work_mem`) |
| `Hash Join` | large equality join, hash fits memory | `Batches > 1` → spilled to disk, raise `work_mem` |
| `Sort` | small sets, no suitable index | `Sort Method: external merge` → spill; add index on ORDER BY |
| Row estimates vs actual | within ~10× | 1000× off → run `ANALYZE`, check `default_statistics_target`, correlated predicates |

Two numbers to read first: `actual rows` vs `estimated rows` (planner error) and `Buffers: shared read=…` (use `EXPLAIN (ANALYZE, BUFFERS)`; high `read` vs `hit` = cold cache / missing index).

### 14.4 Index-Type Decision Tree

See [`system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md) for the full treatment. Quick decision:

- **B-tree**: default. Equality + range + ORDER BY. Use 95% of the time.
- **Hash** (PG): equality only, slightly faster than B-tree for that one case; rarely worth it. MySQL memory engine only.
- **GIN**: multi-valued columns — `jsonb`, arrays, `tsvector` (full-text), trigram (`pg_trgm` for `LIKE '%foo%'`).
- **GiST**: ranges, geometry (PostGIS), nearest-neighbour.
- **BRIN**: append-mostly, physically ordered (time-series, log tables) — tiny index, great for billions of rows scanned in bulk.
- **Partial**: skewed predicate, e.g. `WHERE status='PENDING'` (most rows are `DONE`).
- **Covering / `INCLUDE`**: add non-key columns to avoid heap fetches for read-heavy hotspots.

### 14.5 Connection Pool Pitfalls (HikariCP + Virtual Threads)

- **Pool starvation**: requests queue on `connection-timeout` and time out; symptom: `HikariPool-1 - Connection is not available, request timed out`. Causes: long-running txns (find with `pg_stat_activity`), leaked connections (`leak-detection-threshold`), pool sized smaller than concurrent transactions.
- **Virtual-thread amplification**: with vthreads + unbounded request concurrency, every inbound request demands a connection *now*. A pool of 20 + 2 000 concurrent requests = 1 980 threads parked on pool. Add request-layer bulkhead (`Semaphore` / `@Bulkhead` / Tomcat `max-threads` replaced by a semaphore on vthread Tomcat).
- **Transaction-level pooling (PgBouncer)**: mixes connections between txns — kills server-side prepared statements on drivers < PG JDBC 42.6 / PgBouncer < 1.21. Use `prepareThreshold=0` or upgrade both.
- **`max-lifetime` must be strictly less than DB-side idle timeouts** (`wait_timeout`, `idle_in_transaction_session_timeout`, LB idle-kill) — otherwise you get `Connection is closed` randomly.
- **`minimumIdle == maximumPoolSize` for stable prod** — avoids connection churn and the latency spike of opening a TCP+TLS+auth handshake on a cold request.

### 14.6 N+1 Detection and Fixes

Already covered in §8 (`generate_statistics`, `@EntityGraph`, `JOIN FETCH`, `@BatchSize`, DTO projections). Interview angle:
- **Detect**: assert statement count in integration tests; `datasource-proxy` or hypersistence-utils in CI.
- **Diagnose**: enable `spring.jpa.show-sql` + `logging.level.org.hibernate.SQL=DEBUG` in staging only.
- **Fix choice matrix**: one-shot endpoint → `JOIN FETCH`; generic repo reused everywhere → `@EntityGraph`; deep graph / batch job → `@BatchSize` or `StatelessSession`; read-only listing → DTO projection.
- **Anti-pattern**: `JOIN FETCH` on two `@OneToMany` collections in one query → Cartesian product and `MultipleBagFetchException`. Fix: fetch one collection, let `@BatchSize` handle the other.

### 14.7 Spring Transactional Boundaries

**Propagation levels** (`@Transactional(propagation=...)`):
- `REQUIRED` (default) — join existing or start one.
- `REQUIRES_NEW` — suspend outer, start inner; inner commit/rollback is independent. Classic use: audit log that must persist even if business txn rolls back.
- `NESTED` — JDBC savepoint inside outer txn. Inner rollback doesn't kill outer.
- `SUPPORTS` / `NOT_SUPPORTED` / `MANDATORY` / `NEVER` — niche.

**Rollback rules**: by default Spring rolls back on unchecked exceptions (`RuntimeException`, `Error`) and NOT on checked. Override with `@Transactional(rollbackFor = Exception.class)`. Countless prod bugs come from throwing `IOException` out of a `@Transactional` method expecting a rollback.

**Self-invocation trap**:
```java
@Service
class OrderService {
    public void place(Order o) {
        this.audit(o);   // <-- proxy NOT involved; @Transactional on audit() is IGNORED
    }
    @Transactional void audit(Order o) { ... }
}
// Fix: call via injected self-reference, or split into two beans, or use AspectJ weaving.
```

**`readOnly = true`**: hint to Hibernate (skip dirty checking) and to JDBC driver (may route to replica with Spring's `LazyConnectionDataSourceProxy` + read/write routing). Not a safety constraint — native SQL can still mutate.

### 14.8 Deadlock Detection & Retry

- **Detection**: Postgres / InnoDB wait-for graph finds cycle within `deadlock_timeout` (PG default 1 s) → one txn killed with SQLSTATE `40P01` (PG) / 1213 (MySQL).
- **Prevention**: lock resources in a consistent global order (sort IDs before updating), keep txns short, shrink lock scope with targeted `WHERE`, avoid `SELECT FOR UPDATE` across multiple tables without ordering.
- **Retry pattern**:

```java
@Retryable(
    retryFor = {CannotAcquireLockException.class, DeadlockLoserDataAccessException.class,
                ConcurrencyFailureException.class},
    maxAttempts = 4,
    backoff = @Backoff(delay = 25, multiplier = 2.0, random = true)) // jitter is mandatory
@Transactional
public void transfer(long from, long to, BigDecimal amt) {
    // sort locks to prevent future deadlocks
    long first = Math.min(from, to), second = Math.max(from, to);
    acctRepo.lockForUpdate(first);
    acctRepo.lockForUpdate(second);
    // ...
}
```

Retry at the transaction boundary, never inside a still-open txn. Cap attempts; surface as 409/503 when exhausted.

---

## 15. Practice & Drills

### 15.1 Must-Know Checklist

- [ ] Name the four isolation levels + which anomaly each prevents, and the default for Postgres vs MySQL.
- [ ] Explain MVCC in one paragraph (PG row versions + VACUUM vs InnoDB undo log).
- [ ] Read `EXPLAIN ANALYZE` output and identify the three most common red flags.
- [ ] Design a composite index for a given `WHERE ... ORDER BY ... LIMIT` query.
- [ ] Describe index types (B-tree / GIN / BRIN / partial / covering) and pick one per workload.
- [ ] Size a HikariCP pool and defend the number.
- [ ] Explain JPA `@Version` optimistic locking end-to-end with retry.
- [ ] Detect + fix N+1 with `@EntityGraph`, `JOIN FETCH`, `@BatchSize`.
- [ ] Explain Spring `@Transactional` propagation, rollback rules, self-invocation trap.
- [ ] Describe the transactional outbox + CDC pattern.
- [ ] Name three zero-downtime migration techniques (expand/contract, `CREATE INDEX CONCURRENTLY`, `NOT VALID`).
- [ ] Distinguish read-replica lag mitigation strategies.
- [ ] Compare sharding strategies (hash / range / directory) and pick a shard key.
- [ ] Explain why virtual threads don't change *what* but change *how you size* pools.

### 15.2 Common Traps (Ten That Bite in Production)

1. **`SELECT *` in prod code** — breaks covering indexes, bloats network, breaks when a column is added (tuple expansion in ORMs).
2. **Transaction scope leaking across the HTTP request** via OSIV → connection held through view render + lazy N+1 in templates.
3. **"Read-your-writes" under `SERIALIZABLE` / `REPEATABLE READ`**: reading the replica right after writing the primary returns stale data; same inside a snapshot txn returns the old snapshot.
4. **Updating rows during a cursor iteration** without `FOR UPDATE` — either skipped rows, double-processed rows, or deadlocks. Use `FOR UPDATE SKIP LOCKED` for queue-style processing.
5. **`IDENTITY` generator + JPA batch inserts** — Hibernate disables batching silently because it must flush to obtain the id. Use `SEQUENCE` with `allocationSize`.
6. **`@Transactional` on a `private` method or self-invocation** — proxy doesn't intercept; silently no txn.
7. **Catching `Exception` inside a `@Transactional` method** — the catch swallows the rollback-marking exception; Spring sees no error and commits (or marks rollback-only and you get `UnexpectedRollbackException` on commit).
8. **Long-running transaction + Postgres** — holds the snapshot; VACUUM can't reclaim dead tuples; table bloat grows until performance collapses.
9. **Missing index on FK** — `ON DELETE` of parent scans child table; `JOIN` plans degrade.
10. **`JOIN FETCH` two collections at once** → Cartesian + `MultipleBagFetchException`. Fetch one, batch the other.

### 15.3 Two-Minute Answer Drill (Oral)

1. *"What's the difference between a non-repeatable read and a phantom read?"* — Non-repeatable = same row read twice differs; phantom = same range re-read returns new rows. Prevented by REPEATABLE READ and SERIALIZABLE respectively (InnoDB's REPEATABLE READ also blocks phantoms via gap locks).
2. *"Pessimistic vs optimistic locking — when?"* — Pessimistic for high-contention short txns (inventory decrement); optimistic for low-contention user edits (CMS, profile updates). Pessimistic pays with lock waits, optimistic pays with retry cost.
3. *"Why is `UUIDv4` PK slow on InnoDB?"* — Random → page splits and fragmentation in clustered B+ tree; secondary indexes store the 16-byte PK. Use UUIDv7 (time-ordered) or bigint.
4. *"How do you size a HikariCP pool?"* — Start with `2 × cores + spindles` per instance (10-20 typical). Stay below DB `max_connections / instances`. Add PgBouncer transaction pooling for high fan-out.
5. *"What does `Seq Scan` in EXPLAIN mean?"* — Full table read. OK on small tables or low-selectivity predicates; red flag on large tables where you expected an index — check predicate shape, function wrappers, type mismatch, stale stats.
6. *"Outbox pattern in 30 seconds?"* — Write business row + event row in one txn. Debezium tails WAL, publishes to Kafka. Solves dual-write without 2PC; consumers must be idempotent.
7. *"CAP trade-off for Postgres?"* — Single-node Postgres is CA-ish; clustered Postgres (Patroni, streaming replication) is effectively CP — primary lost → brief unavailability while election runs.
8. *"Why disable OSIV?"* — Connections/sessions held across the entire request; hides N+1 in views; bleeds txns into the web layer; painful on virtual threads / async.
9. *"Self-invocation trap?"* — A `@Transactional` method called via `this.method()` bypasses the Spring proxy, so no txn is started. Fix: inject self, split bean, or use AspectJ.
10. *"Best way to paginate 100 pages deep?"* — Keyset pagination (`WHERE (created_at, id) > (:last)`). `OFFSET` scans and discards rows linearly.

### 15.4 Query / Schema Drill

**Q1 — Design an index.**
Given:
```sql
SELECT id, total FROM orders
 WHERE tenant_id = ? AND status = 'PENDING' AND created_at > NOW() - INTERVAL '7 days'
 ORDER BY created_at DESC LIMIT 50;
```
Best index: `(tenant_id, status, created_at DESC) INCLUDE (total, id)` — equalities first, range + sort last, covering. On heavy skew (few pending), a **partial** index `WHERE status='PENDING'` is even tighter.

**Q2 — Rewrite a slow query.**
```sql
-- Slow:
SELECT * FROM events WHERE DATE(ts) = '2026-04-13';     -- function wrap, no index use
-- Fix:
SELECT id, type, ts FROM events
 WHERE ts >= '2026-04-13' AND ts < '2026-04-14';         -- sargable, uses btree on ts
```

**Q3 — Pick isolation for a workload.**
A ticketing system decrementing seat count for events. Expected contention: high on popular events.
Answer: either pessimistic (`SELECT ... FOR UPDATE` on the event row) at `READ COMMITTED`, or optimistic with `@Version` and retry. Avoid `SERIALIZABLE` — it'll thrash under contention. Do NOT rely on `COUNT(*) < capacity` at `READ COMMITTED` — classic lost-update; use atomic `UPDATE events SET seats_left = seats_left - 1 WHERE id=? AND seats_left > 0` and check `affected rows`.

**Q4 — Explain this EXPLAIN.**
```
Nested Loop (actual rows=120000 loops=1)
  -> Seq Scan on orders  (actual rows=1200000 loops=1, rows removed by filter=1080000)
  -> Index Scan on customers using customers_pkey (actual rows=1 loops=120000)
```
Diagnosis: outer is a full `Seq Scan` on 1.2M rows, filter removes 90% → missing index on `orders.<predicate column>`. Inner nested loop hits pkey 120 k times — fine. Add the missing predicate index; planner may switch to hash join if outer set shrinks below threshold.

**Q5 — Resolve a deadlock.**
Two services update `accounts` rows `(A,B)` and `(B,A)` respectively. Fix: (1) always lock in ID-sorted order in code; (2) shorten txns; (3) add `@Retryable` on `CannotAcquireLockException` with exponential backoff + jitter; (4) if the workload is append-only, switch to an event-sourced ledger (no row contention).

### 15.5 Debugging Drill

1. **Slow query appeared overnight.** Steps: `pg_stat_statements` top by `total_exec_time`; `EXPLAIN (ANALYZE, BUFFERS)`; compare plan to last-known-good (if you log plans). Usual suspects: stats staleness (`ANALYZE`), data-size crossed threshold flipping nested-loop → hash, autovacuum off, new predicate pattern.
2. **Deadlock spike in logs.** Pull `pg_stat_activity` + `pg_locks` during the window; inspect server log with `log_lock_waits=on`. Build the wait-for graph from SQL trace. Enforce lock ordering; add retry with jitter. Verify no long-held advisory or table locks from a scheduled job.
3. **Connection pool exhaustion.** Symptoms: `Connection is not available, request timed out`. Check: (a) `leak-detection-threshold` warnings for methods not closing; (b) `pg_stat_activity` for `idle in transaction` > N seconds — indicates missing `commit`/`rollback` or long non-DB work inside `@Transactional`; (c) raise pool only after fixing root cause.
4. **Bloated table / autovacuum starvation.** `pg_stat_user_tables.n_dead_tup`, `pg_stat_all_tables.last_autovacuum`. Long-running txns (`xact_start` > hours in `pg_stat_activity`) block cleanup. Kill offenders, tune `autovacuum_vacuum_scale_factor` per-table for hot tables, schedule `pg_repack` off-peak.
5. **Replication lag affecting user experience.** Measure: `pg_stat_replication.replay_lag`. Cause: big DML on primary, slow network, replica under-provisioned, long queries on replica blocking `recovery` (hot_standby_feedback interplay). Mitigate: route critical post-write reads to primary for N seconds (sticky); add read-your-writes token; scale up replica; split reporting to a dedicated replica.

### 15.6 Timed Practice (30-minute Mock)

**Scenario (whiteboard, no IDE):**
You are the senior on-call for an e-commerce checkout service on Postgres 16 + Spring Boot 3.4 + Hibernate 7. Orders table is 400 M rows, 1.2 TB, partitioned by month. Symptoms reported today:
- p99 `POST /checkout` jumped from 120 ms to 2.5 s around 09:00.
- HikariCP exhaustion alerts on three of eight pods.
- `orders_items` write throughput halved.
- One customer reports double-charging for a single order.

**Deliver in 30 minutes:**
1. (6 min) Triage plan — which metrics and queries you hit first and why.
2. (6 min) Root-cause hypothesis tree (at least four branches: long txn, missing index, replication lag, bad deploy).
3. (6 min) The exact SQL you'd run against `pg_stat_activity`, `pg_stat_statements`, `pg_locks`.
4. (6 min) A concrete fix for the double-charge (idempotency key + `INSERT ... ON CONFLICT DO NOTHING`, outbox for the payment event).
5. (6 min) Post-incident hardening: pool sizing, `leak-detection-threshold`, `statement_timeout`, `idle_in_transaction_session_timeout`, dashboards.

Grade yourself on: did you name the specific system views, did you explicitly mention idempotency, did you separate "stop the bleeding" from "prevent recurrence"?

### 15.7 Review Checklist (Before the Interview)

- [ ] I can explain MVCC, snapshot isolation, SSI, and the PG retry on SQLSTATE 40001.
- [ ] I can pick a composite index given a query and justify column order.
- [ ] I can read `EXPLAIN (ANALYZE, BUFFERS)` aloud.
- [ ] I can size a pool and explain the virtual-thread twist.
- [ ] I can draw the outbox + Debezium flow on a whiteboard.
- [ ] I can describe expand/contract migration on a live table.
- [ ] I know the five pitfalls of Spring `@Transactional` (self-invocation, rollback on checked, OSIV, `private` method, readOnly routing).
- [ ] I can explain shard key selection and when NOT to shard.
- [ ] I've rehearsed the two-minute drill aloud, on a timer.
- [ ] I've cross-linked my mental model with [`system_design/patterns/database-indexing.md`](../system_design/patterns/database-indexing.md) and [`system_design/patterns/caching.md`](../system_design/patterns/caching.md).
