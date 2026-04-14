# SQL Databases (RDBMS)

## What They Are
Relational Database Management Systems store data in tables with rows and columns, enforce schemas, and support ACID transactions. They use SQL (Structured Query Language) for querying and manipulating data.

## ACID Properties
- **Atomicity**: A transaction is all-or-nothing. If any part fails, the entire transaction is rolled back.
- **Consistency**: A transaction brings the database from one valid state to another. Constraints, triggers, and cascades are enforced.
- **Isolation**: Concurrent transactions don't interfere with each other (as if executed serially).
- **Durability**: Once committed, data survives crashes (written to disk/WAL).

## Transaction Isolation Levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|-------|-----------|-------------------|-------------|-------------|
| Read Uncommitted | ✓ | ✓ | ✓ | Fastest |
| Read Committed | ✗ | ✓ | ✓ | Fast |
| Repeatable Read | ✗ | ✗ | ✓ | Medium |
| Serializable | ✗ | ✗ | ✗ | Slowest |

- **Dirty read**: Reading uncommitted data from another transaction
- **Non-repeatable read**: Same query returns different results within one transaction
- **Phantom read**: New rows appear that match a previously-executed query

Most databases default to **Read Committed** (PostgreSQL, Oracle) or **Repeatable Read** (MySQL InnoDB).

## Scaling SQL Databases

### Vertical Scaling
Bigger machine: more CPU, RAM, faster SSD. Simple but has limits.

### Read Replicas
Single leader handles writes. Replicas serve reads. Good for read-heavy workloads (80-90% reads).

### Sharding
Split data across multiple database servers. See the Sharding pattern for details.

### Connection Pooling
Reuse database connections instead of creating new ones per request. Tools: PgBouncer (PostgreSQL), ProxySQL (MySQL).

### Query Optimization
Indexes, query plan analysis (EXPLAIN), denormalization, materialized views.

## Major SQL Databases

| Database | Strengths | Notes |
|----------|-----------|-------|
| **PostgreSQL** | Feature-rich, extensible, JSON/JSONB, FTS, pgvector | Best general-purpose RDBMS. PG 17 (Sept 2024) improved VACUUM memory, logical replication failover; PG 18 (Sept 2025) shipped async I/O, UUIDv7, and skip-scan indexes. |
| **MySQL** | Simple, fast reads, mature replication | Most widely deployed; InnoDB engine. MySQL 8.4 LTS (2024) is current. |
| **SQL Server** | Enterprise, BI integration, .NET ecosystem | Microsoft shops |
| **Oracle** | Enterprise, RAC for clustering, advanced features | Expensive, legacy enterprise |
| **CockroachDB** | Distributed SQL, horizontal scaling, strong consistency | NewSQL: SQL interface + distributed architecture. Relicensed to CockroachDB Software License (CSL, source-available) in 2024. |
| **TiDB** | MySQL-compatible, distributed, HTAP | NewSQL |
| **Vitess** | MySQL sharding middleware | Used by YouTube, PlanetScale |
| **Amazon Aurora** | Cloud-native MySQL/PostgreSQL, separated storage | 5x throughput of standard MySQL. Aurora DSQL (2024): active-active multi-region Postgres. |
| **Neon / Supabase** | Serverless Postgres with branching / BaaS | Modern Postgres-based platforms with copy-on-write branching. |

## Postgres Ecosystem Notes
- **pgvector** (extension): Turns Postgres into a vector DB with `vector` type, HNSW and IVFFlat indexes. A major reason teams stick with Postgres for LLM/RAG workloads instead of adopting a dedicated vector DB.
- **TimescaleDB** (extension): Time-series hypertables on top of Postgres (see time-series file).
- **PostGIS** (extension): Geospatial — industry standard.
- **Citus** (extension): Horizontal sharding/distributed Postgres, now owned by Microsoft.

## NewSQL
Databases that provide the scalability of NoSQL with the ACID guarantees of SQL. They use distributed consensus (Raft/Paxos) and distributed storage.
- Examples: CockroachDB, Google Spanner, TiDB, YugabyteDB

## When to Use SQL
- Data has clear relationships (joins are common)
- ACID transactions are required
- Schema is well-defined and relatively stable
- Complex queries (aggregations, subqueries, window functions)
- Data integrity is critical (financial, healthcare)

## When NOT to Use SQL
- Massive write throughput (>100K writes/sec) with simple access patterns
- Schema changes frequently
- Data is mostly unstructured (documents, graphs)
- Horizontal scaling to 100+ nodes is needed (unless using NewSQL)

## Possible Interview Questions
1. "When would you choose SQL over NoSQL for this system?"
2. "How would you scale a PostgreSQL database that's hitting write limits?"
3. "Explain the different transaction isolation levels and when you'd use each."
4. "What is N+1 query problem and how do you solve it?"
5. "How does connection pooling improve database performance?"
6. "Compare PostgreSQL vs MySQL for a new project."
7. "What is a materialized view and when would you use one?"
8. "Explain WAL (Write-Ahead Log) and why it matters for durability."
