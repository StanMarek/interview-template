# NoSQL Databases

## What They Are
Non-relational databases designed for specific data models and access patterns. They trade some SQL guarantees (joins, full ACID) for scalability, flexibility, and performance in specific use cases.

## Types of NoSQL Databases

### 1. Key-Value Stores
Simplest model: a hashmap. Lookup by key, get a value (opaque blob).

| Database | Notes |
|----------|-------|
| Redis | In-memory, rich data structures (see In-Memory Caches) |
| DynamoDB | Managed, auto-scaling, single-digit ms latency |
| Riak | Distributed, AP system, conflict resolution via CRDTs |
| etcd | Distributed KV for config/service discovery (CP) |

**Best for**: Session stores, caching, shopping carts, user preferences, feature flags.
**Access pattern**: `GET(key)`, `PUT(key, value)`. No queries on values.

### 2. Document Stores
Store semi-structured documents (JSON/BSON). Documents can have nested structures and varying schemas.

| Database | Notes |
|----------|-------|
| MongoDB | Most popular, rich query language, aggregation pipeline. MongoDB 7 (2023) added queryable encryption; MongoDB 8 (Oct 2024) brought time-series/vector improvements and ~30% throughput gains. Relicensed from AGPL to SSPL in 2018 — still not OSI-approved. |
| CouchDB | AP, multi-master replication, HTTP/REST API |
| Firestore | Managed, real-time sync, mobile-first |
| Elasticsearch / OpenSearch | Document store + full-text search (see Search Engines) |

**Best for**: Content management, catalogs, user profiles, event logging. Anything with variable schemas or nested data.
**Access pattern**: Query by any field, nested field access, partial updates.

### 3. Wide-Column Stores
Data organized by column families. Each row can have different columns. Optimized for writes and range scans over large datasets.

| Database | Notes |
|----------|-------|
| Cassandra | AP, leaderless, tunable consistency, linear write scaling. Cassandra 5.0 (Sept 2024) added vector search (SAI indexes), storage-attached indexes, and trie memtables. |
| HBase | CP, built on HDFS, strong consistency, Hadoop ecosystem |
| ScyllaDB | Cassandra-compatible, C++ shard-per-core (faster). ScyllaDB 6+ replaced internal Gossip with Raft and LWT group0. |
| Bigtable | Google's managed, original inspiration for Cassandra/HBase |

**Data model**: `(row_key, column_family:column_name, timestamp) → value`

**Best for**: Time-series data, IoT sensor data, messaging (large write volumes), analytics. Workloads with known access patterns.
**Access pattern**: Lookup by row key, scan by row key range. No joins, limited secondary queries.

### 4. Graph Databases
See dedicated Graph Databases file.

## SQL vs NoSQL Decision Framework

| Factor | Choose SQL | Choose NoSQL |
|--------|-----------|-------------|
| Relationships | Complex joins needed | Few or no joins |
| Schema | Well-defined, stable | Flexible, evolving |
| Transactions | Multi-row ACID required | Single-document atomic is sufficient |
| Scale | Vertical or moderate horizontal | Massive horizontal scale |
| Consistency | Strong consistency required | Eventual consistency acceptable |
| Query complexity | Complex (aggregations, subqueries) | Simple (key lookup, range scan) |
| Read pattern | Ad-hoc, unpredictable queries | Known access patterns |

## The "It Depends" Answer
In interviews, the answer is almost never "always SQL" or "always NoSQL." Most real systems use both:
- SQL for transactional data (orders, payments, user accounts)
- NoSQL for high-volume, low-latency data (sessions, caches, activity feeds, real-time analytics)

## Common NoSQL Trade-offs
- **No joins**: Denormalize data (store redundant copies) or join in application code
- **Eventual consistency**: Design for it (idempotency, conflict resolution)
- **Limited transactions**: Single-document atomicity (MongoDB), or use patterns like saga
- **Schema flexibility**: Can be a blessing (rapid iteration) or curse (inconsistent data)

## Possible Interview Questions
1. "When would you use a document store vs a wide-column store?"
2. "How would you model a social media feed in a NoSQL database?"
3. "Your system needs to handle 1M writes/second. Which database would you choose?"
4. "How do you handle relationships in a NoSQL database without joins?"
5. "Explain eventual consistency and how you'd design around it."
6. "Compare DynamoDB vs Cassandra for a time-series workload."
7. "How does data modeling differ between SQL and NoSQL?"
