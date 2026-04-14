# CDC-Based Analytics Platform — Architecture Design

## Requirements

### Functional
- Capture every INSERT, UPDATE, DELETE from multiple OLTP databases (PostgreSQL, MySQL, MongoDB) without impacting production performance
- Replicate data into a centralized data lake with bronze/silver/gold medallion architecture
- Support near real-time analytics (dashboard refreshes within 5 minutes of source database change)
- Maintain full change history for audit, SCD Type 2 dimensions, and point-in-time queries
- Support schema evolution (source databases add/remove columns without breaking the pipeline)
- Enable reverse ETL: push computed metrics back to operational systems (CRM, marketing tools)

### Non-Functional
- Zero impact on source database performance (read from replication logs, not query the database)
- Sub-5-minute end-to-end latency from OLTP write to analytics query availability
- Exactly-once semantics for data lake writes (no duplicates, no missing records)
- Support 50+ source tables across 10+ databases
- Handle schema changes without pipeline downtime
- Data lineage tracking for compliance and debugging

## Scale Estimates
- **Source databases:** 10 databases, 50+ tables, total ~5TB of data
- **Change rate:** 50K changes/sec across all databases (peak during batch imports)
- **Daily volume:** ~500M change events/day, ~200GB raw CDC data
- **Data lake size:** ~50TB (all historical changes preserved), growing ~200GB/day
- **Query patterns:** 500 dashboard queries/hour (BI tools), 50 ad-hoc queries/hour (analysts), 10 ML training jobs/week
- **Retention:** Raw CDC events in Kafka: 7 days. Data lake: indefinite (all change history)

## Architecture Decisions

### Debezium (Log-Based CDC) Over Query-Based CDC
Query-based CDC (polling with `WHERE updated_at > last_run`) has three fundamental problems: (1) it misses DELETEs (a deleted row has no `updated_at`), (2) it adds query load to the OLTP database, (3) it can miss rapid updates (a row updated twice between polls loses the intermediate state). Debezium reads the database's write-ahead log (WAL/binlog/oplog) directly, capturing every change operation with zero query impact on the database. The trade-off: Debezium requires replication slot configuration on the source database, and WAL retention must be managed to prevent disk exhaustion.

**Deployment note:** Debezium 2.x supports "Debezium Server" as a standalone process (no Kafka Connect dependency) that can sink directly to Pulsar, Kinesis, or other buses — useful if you're not on Kafka. On Kafka, run Debezium connectors on Kafka Connect clusters, using KRaft-mode Kafka (ZooKeeper was fully removed in Kafka 4.0, released 2025).

### Apache Iceberg for the Data Lake Over Hive/Delta Lake
Iceberg provides three features critical for CDC workloads: (1) MERGE INTO (upsert) — CDC events are naturally insert/update/delete operations, and Iceberg's merge handles all three in a single atomic operation, (2) time travel — query the data as of any previous snapshot, which is essential for reproducing analytics results and debugging, (3) hidden partitioning — partition by date without requiring a date column in the data, reducing the burden on ETL logic. Delta Lake offers similar features but is more tightly coupled to the Spark ecosystem. Iceberg works with Flink, Spark, Trino, and Presto.

### Stream Processing (Flink) for Real-Time + dbt for Batch Transformations
The medallion architecture splits transformations into two layers: (1) Flink handles bronze-to-silver (real-time): CDC event deduplication, schema normalization, stream joins (denormalize orders with users), SCD Type 2 tracking. This runs continuously with sub-minute latency. (2) dbt handles silver-to-gold (batch): complex business logic, aggregations, data marts. dbt runs hourly and operates on Iceberg tables via Trino. This split avoids the complexity of putting all transformation logic in a stream processor while keeping the data reasonably fresh.

### Schema Registry for Schema Evolution
Source databases evolve their schemas independently (new columns, renamed fields, type changes). The Schema Registry (Confluent or Apicurio) tracks every schema version and enforces compatibility rules. Debezium automatically registers new schemas when it detects DDL changes. Downstream consumers (Flink, Spark) use the registry to deserialize events with the correct schema version. Without this, a simple `ALTER TABLE ADD COLUMN` in production would break every downstream consumer.

## Pipeline Stages

1. **Source Systems:** OLTP databases serving production applications: Orders (PostgreSQL), Users (MySQL), Inventory (PostgreSQL), Payments (MongoDB). Each database has a Debezium connector deployed as a Kafka Connect source connector
2. **CDC Capture:** Debezium reads database replication logs (PostgreSQL WAL, MySQL binlog, MongoDB oplog) and produces CDC events in a standardized envelope format: `{before: {old_row}, after: {new_row}, op: "c|u|d", ts_ms: timestamp, source: {db, table, lsn}}`. One Kafka topic per table (e.g., `cdc.orders.order_items`)
3. **Schema Registry:** Avro schemas auto-registered by Debezium. Backward compatibility enforced — consumers can always read older events. Schema evolution events (new column) trigger downstream alerts
4. **Flink Denormalization (Bronze -> Silver):** Joins CDC streams from multiple tables in real-time. Example: join `orders` with `users` and `payments` to create a denormalized `enriched_orders` stream. Uses Flink's temporal join (join with the version of the user record that was current at the time of the order). Writes to Iceberg silver tables
5. **Flink SCD Type 2 Tracking:** For dimension tables (users, products), maintains full history of changes. Each change creates a new record with `valid_from` and `valid_to` timestamps. This enables point-in-time queries: "what was this user's tier when they placed this order?"
6. **Flink Real-Time Aggregations:** Computes near real-time business metrics: revenue per hour, orders per minute, inventory levels. Writes to ClickHouse for low-latency dashboard queries
7. **dbt Transformations (Silver -> Gold):** Hourly dbt runs build data marts: `mart_revenue` (revenue by product/region/day), `mart_customer_ltv` (customer lifetime value), `mart_inventory_health`. dbt tests validate data quality (unique keys, not-null constraints, referential integrity)
8. **Data Quality Checks:** Great Expectations or dbt tests validate row counts, null rates, schema conformance, and cross-table referential integrity. Failures block the silver->gold promotion and alert the data engineering team
9. **Analytics Layer:** BI tools (Tableau/Looker) query gold Iceberg tables via Trino. Real-time dashboards query ClickHouse. Data catalog (DataHub) tracks lineage from source tables to gold marts. Reverse ETL pushes computed segments back to CRM (e.g., "high-LTV customers" segment synced to Salesforce)

## Partitioning Strategy

- **Kafka CDC topics:** One topic per source table. Partitioned by the table's primary key — ensures ordered processing of all changes to the same row (INSERT -> UPDATE -> DELETE arrive in order on the same partition). This is essential for correct CDC replay
- **Iceberg Bronze:** Partitioned by `ingestion_date` (daily). This matches the most common query pattern (analyze changes from a specific date) and enables efficient incremental processing
- **Iceberg Silver:** Partitioned by the table's natural key (e.g., `order_date` for orders, `region` for users). Different tables have different optimal partitions based on query patterns
- **Iceberg Gold (Marts):** Partitioned by the most common filter dimension (usually date + one business dimension). Iceberg's hidden partitioning auto-derives partition from `order_timestamp` without requiring an explicit partition column
- **ClickHouse:** Sharded by time (monthly partitions) with `ORDER BY (metric_name, timestamp)` for efficient time-range queries

## Failure Handling

- **Debezium connector failure:** Kafka Connect restarts the connector automatically. Debezium resumes from its stored offset (WAL LSN for PostgreSQL, binlog position for MySQL). No data loss because the WAL/binlog still exists on the source database. Critical: ensure the source database's WAL retention period exceeds the maximum expected connector downtime (set `wal_keep_size` to at least 10GB on PostgreSQL)
- **Kafka failure:** Standard replication (RF=3). CDC topics use `cleanup.policy=compact` — Kafka retains the latest value for each key, enabling full state reconstruction from the compacted topic
- **Flink failure:** Checkpoints to S3. Flink's Iceberg sink supports exactly-once writes using Iceberg's atomic commit protocol — each checkpoint produces one Iceberg snapshot, and rollback on failure discards uncommitted data files. This prevents partial writes and duplicates
- **Schema change in source:** Debezium detects DDL changes and registers new schema versions. If the change is backward-compatible (new nullable column), downstream consumers continue transparently. If breaking (column rename, type change), the Schema Registry rejects the change, Debezium pauses, and the pipeline alerts. Engineers must update the schema compatibility rules or migrate downstream consumers before resuming
- **DLQ:** CDC events that fail processing (e.g., Flink can't deserialize due to schema mismatch) go to DLQ. These are investigated quickly because each DLQ event represents a production database change that's missing from the data lake
- **Data quality failure:** If dbt tests fail, gold tables are not updated. The last successful version remains available. Alerts fire. Engineers investigate the silver data for corruption and decide whether to fix forward (patch the transformation) or backward (replay from bronze)

## Key Trade-offs

- **Real-time vs correctness:** Flink's stream joins produce results within seconds but may produce "early" results for joins across tables (an order event arrives before the corresponding payment event). Late arrivals update the joined result, but downstream consumers may see temporary inconsistencies. dbt's batch processing sees the complete picture but runs hourly. For dashboards, the real-time view with "eventually consistent" joins is acceptable. For financial reporting, wait for the dbt batch
- **Full history vs storage cost:** Keeping all CDC events (SCD Type 2) enables powerful analytics (track customer journey over time) but storage grows linearly with change rate. For high-churn tables (session logs, click events), the change volume may exceed practical storage limits. Compromise: SCD Type 2 for dimension tables (users, products — low change rate, high analytical value), SCD Type 1 (overwrite) for fact tables (sessions — high volume, history less valuable)
- **Topic-per-table vs single-topic:** Debezium's topic-per-table approach creates many Kafka topics (50+ tables = 50+ topics). Each topic has overhead (metadata, consumer groups). A single topic with table name as a field reduces overhead but loses per-table ordering guarantees and makes consumer filtering more complex. For 50 tables, topic-per-table is manageable. At 500+ tables, consider topic-per-schema (one topic per database)
- **Log compaction vs full retention:** Kafka topic compaction keeps only the latest value per key, enabling efficient full-state reconstruction. But it loses the change history (intermediate updates are discarded). For CDC, we use both: a non-compacted topic for streaming consumers (who need all changes in order) and a compacted topic for bootstrapping new consumers (who need current state). This doubles storage but serves both use cases

## What Fails First

**WAL retention on the source database** is the silent killer. If Debezium falls behind (connector outage, Kafka unavailable, slow consumer), the source database continues generating WAL. PostgreSQL's replication slot prevents WAL deletion, but the WAL grows on disk. If the source database's disk fills up, PRODUCTION GOES DOWN. This is a CDC-specific failure mode that doesn't exist in query-based ETL. 

Solutions: (1) Monitor replication slot lag aggressively (alert if lag exceeds 1 hour), (2) Set `max_slot_wal_keep_size` to cap WAL retention at a safe size (at the cost of potentially losing CDC data), (3) If the slot falls too far behind, drop it and perform a full snapshot (Debezium supports this as a recovery mechanism). Prevention: size the source database's disk with 3x headroom for WAL growth during incidents.

Second failure point: **Iceberg small file problem.** Flink's streaming writes produce many small Parquet files (one per checkpoint, per partition). 50K changes/sec with 30-second checkpoints across 50 partitions = 1500 small files per checkpoint cycle. Over a day, this creates 72K files per table. Iceberg queries slow down as they must open each file. Solution: Iceberg's `rewrite_data_files` compaction action, run hourly as a maintenance job, merges small files into larger ones. Also tune Flink's checkpoint interval (longer = fewer, larger files, but more data at risk on failure).

## v1 vs v2

### v1 — Ship in 3 weeks
- Debezium CDC for 3 critical tables (orders, users, payments)
- Single Kafka topic per table, no schema registry (use JSON)
- Kafka Connect S3 sink for raw data landing (Parquet on S3)
- dbt on Spark/Trino for all transformations (no Flink, batch-only)
- Hourly dbt runs for silver and gold tables
- Hive metastore for table management (not Iceberg yet)
- Basic BI tool connection (Looker/Tableau against gold tables)
- Manual schema change handling

### v2 — Production-grade CDC analytics
- All 50+ tables across 10 databases
- Schema Registry with backward compatibility enforcement
- Iceberg for all data lake tables (bronze/silver/gold) with time travel and MERGE INTO
- Flink for real-time silver transformations (stream joins, SCD Type 2, real-time aggregations)
- ClickHouse for sub-second OLAP queries on real-time dashboards
- dbt for gold marts with comprehensive data quality tests
- DataHub data catalog with full lineage tracking
- Reverse ETL to push segments/metrics to CRM and marketing tools
- Iceberg compaction maintenance jobs
- WAL monitoring and automatic replication slot health checks
- Schema evolution automation (auto-detect, compatibility check, consumer notification)
