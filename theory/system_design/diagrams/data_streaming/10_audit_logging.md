# Large-Scale Audit Logging Platform — Architecture Design

## Requirements

### Functional
- Capture every state-changing operation across all services: CRUD operations, authentication events, permission changes, configuration modifications, data access events, infrastructure operations
- Standardized audit event format: who (actor), what (action), where (resource), when (timestamp), how (context: IP, device, session), outcome (success/failure)
- Tamper-proof storage: audit logs cannot be modified or deleted by anyone, including system administrators
- Full-text search and structured filtering across all audit events
- Real-time detection of suspicious access patterns (privilege escalation, bulk data export, off-hours access)
- Regulatory compliance: SOX, HIPAA, GDPR, PCI-DSS audit trail requirements
- Bulk export for regulatory audits and forensic investigations

### Non-Functional
- Zero data loss: every audit event must be durably stored (losing audit logs is a compliance violation)
- Append-only, immutable storage (no updates, no deletes until retention expires)
- Write latency: audit logging must not add more than 5ms to the application's request path (async write)
- Search latency: sub-second for recent events (last 30 days), under 30 seconds for historical queries
- Handle 500K audit events/sec across all services
- 7-year retention (regulatory requirement for financial services)
- Cryptographic integrity: ability to prove no records were tampered with between write time and query time

## Scale Estimates
- **Events/sec:** 500K peak, 100K sustained
- **Event size:** ~1KB average (actor, action, resource, context, metadata)
- **Daily volume:** ~50GB compressed (500K * 1KB * 86400 / compression)
- **7-year storage:** ~130TB compressed, ~15TB on Glacier (older data tiered to cheaper storage)
- **Hot data (30 days):** ~1.5TB in Elasticsearch
- **Warm data (30 days - 1 year):** ~18TB in S3 with Athena access
- **Cold data (1-7 years):** ~110TB in S3 Glacier
- **Search queries:** 10K/day (compliance dashboards, investigations)

## Architecture Decisions

### Asynchronous Audit Logging via Kafka Over Synchronous
Audit logging must never block the application's critical path. If the audit service is down, the application should still serve users. The audit SDK writes events to a local buffer (in-memory queue with disk overflow), then asynchronously sends to Kafka. This adds <1ms to the application path (just the buffer enqueue). If Kafka is temporarily unavailable, the SDK's disk buffer holds events until Kafka recovers. The alternative (synchronous write to a database) would add 5-20ms per request and create a hard dependency.

The critical insight: audit logs require *durability*, not *synchronicity*. It's acceptable for an audit event to appear in the searchable index 30 seconds after the action occurred. It's NOT acceptable for the event to be lost.

### S3 Object Lock for Immutability Over Database-Level Controls
Database administrators can modify database records, even in "append-only" databases, by manipulating the storage layer directly. S3 Object Lock (Compliance mode) provides true immutability at the object storage level — no one, including the AWS root account, can delete or modify locked objects until the retention period expires. This satisfies the "tamper-proof" requirement at the infrastructure level, not just the application level. Audit events are written as S3 objects with a 7-year Object Lock retention. Even if a malicious admin compromises the entire application stack, the S3 objects remain intact.

### Merkle Tree Hash Chain for Integrity Verification
How do you prove that no records were inserted, deleted, or modified between write time and query time? A sequential hash chain (each event's hash includes the previous event's hash) creates a tamper-evident log — any modification to a historical record breaks the chain. We store hash chain checkpoints in a separate Merkle tree database. During integrity audits, the batch verification job recomputes hashes from the raw events and compares them against the stored chain. Any discrepancy indicates tampering.

### Elasticsearch for Hot Search, Athena for Historical Queries
Recent audit events (last 30 days) need sub-second search because compliance investigations often focus on recent activity. Elasticsearch provides this with its inverted index. Historical queries (older than 30 days) are infrequent and can tolerate higher latency. Athena (serverless SQL over S3) handles these at a fraction of Elasticsearch's cost — no running cluster needed, pay only for queries. The S3 data is stored as compressed Parquet, partitioned by date and service name.

### PII Redaction Before Storage
Audit events often contain PII (user email in the "actor" field, customer data in the "resource" field). GDPR requires the ability to delete or anonymize PII. But audit logs are immutable. Contradiction? Solution: extract PII at ingestion time into a separate, mutable PII mapping store (actor_id -> email). The audit log stores only anonymized IDs. When GDPR deletion is requested, delete the PII mapping. The audit log retains the anonymized record (satisfying audit requirements) while the PII link is severed (satisfying GDPR). The redaction happens in the Flink PII pipeline before events reach immutable storage.

## Pipeline Stages

1. **Audit Producers:** Every service uses the Audit SDK (client library) to emit audit events. The SDK provides a standardized API: `auditLog.record(actor, action, resource, outcome, context)`. It validates the event schema, assigns a monotonic sequence number, and enqueues to the local buffer
2. **Audit SDK:** In-process buffer with disk overflow. Uses at-least-once delivery to Kafka (retries on failure, idempotent producer). Each event includes a unique `event_id` (UUID) for deduplication downstream
3. **Kafka Ingestion:** `audit-events` topic, 128 partitions, RF=3, `min.insync.replicas=2`. `acks=all` for maximum durability. Retention: 7 days (enough to replay if downstream fails). Schema Registry enforces the audit event schema (backward compatible Avro)
4. **Cryptographic Signing Service:** Before events enter Kafka, the SDK optionally signs each event with a service-specific private key (HMAC-SHA256). The signing service manages key rotation. At query time, signatures can be verified to prove the event was produced by the claimed service
5. **Flink Event Enrichment:** Enriches raw events with metadata: actor's role/team (from IAM lookup), resource classification (PII/sensitive/public from data catalog), geo-location from IP. Deduplicates by `event_id`
6. **Flink Suspicious Activity Detector:** Real-time detection of concerning patterns: (a) privilege escalation (user accesses resource they've never accessed before), (b) bulk data export (same actor queries >1000 records in 10 minutes), (c) off-hours access (access from unusual time zone or outside business hours), (d) impossible travel (same user accesses from two distant geos within minutes). Alerts fire to SIEM
7. **Flink Compliance Policy Engine:** Evaluates events against compliance rules: (a) PCI-DSS: all access to cardholder data must be logged, (b) HIPAA: all access to patient records must be logged, (c) SOX: all financial data modifications must have dual approval. Flags violations for compliance dashboard
8. **Flink PII Redaction:** Extracts PII fields into a separate PII mapping store. Replaces PII in the audit event with anonymized tokens. Classification uses the data catalog metadata from the enrichment stage
9. **Storage (Hot):** Elasticsearch receives enriched, PII-redacted events. Index per day (`audit-2024-01-15`), ILM: hot (SSDs, 30 days) -> delete (events already in S3). Optimized for search, not long-term storage
10. **Storage (Immutable):** Flink also writes events to S3 as Parquet files with S3 Object Lock (Compliance mode, 7-year retention). Hash of each event batch is recorded in the Merkle Tree Hash Store. After 1 year, S3 Lifecycle transitions objects to Glacier
11. **Batch Integrity Audit:** Weekly Spark job reads events from S3, recomputes hash chain, and verifies against the Merkle Tree store. Any discrepancy triggers a critical alert and is reported to the compliance team
12. **Batch Retention Management:** Daily job manages ILM transitions: S3 Standard (0-1 year) -> S3 Glacier (1-7 years). After 7 years, Object Lock expires and objects are auto-deleted by lifecycle policy. The deletion itself is audit-logged

## Partitioning Strategy

- **Kafka `audit-events`:** Partition by `service_name` — groups all events from one service together, enabling per-service processing order and per-service rate limiting. This prevents one noisy service from starving others of Kafka throughput
- **Elasticsearch indices:** Time-based (`audit-YYYY-MM-DD`). This aligns with the most common query pattern (investigations focus on specific date ranges) and enables efficient ILM (drop entire indices when aging out). Shard count: total hot data / 30GB per shard, with 1 replica
- **S3 Parquet:** Partitioned by `service_name/year/month/day`. Parquet columnar format enables efficient Athena queries on specific columns (e.g., "all events by actor X") without scanning all columns. Predicate pushdown on partition columns avoids scanning irrelevant data
- **Merkle Tree:** One hash chain per `(service_name, day)`. This limits the scope of integrity verification — a tampering attempt in one service's logs doesn't require re-verifying all logs

## Failure Handling

- **Audit SDK buffer overflow:** If the SDK's in-memory buffer fills (Kafka unavailable for extended time), overflow to local disk (configurable max: 1GB). If disk overflows, the SDK logs a critical alert and starts counting dropped events (a compliance violation). The application continues serving, but the audit gap must be investigated and reported
- **Kafka failure:** RF=3, `min.insync.replicas=2` ensures no acknowledged writes are lost. If the entire Kafka cluster is unavailable, the SDK buffers locally. If Kafka is down for more than the SDK buffer capacity, events are lost — this is a critical incident requiring immediate response
- **Flink failure:** Checkpoints to S3 every 30 seconds. Exactly-once semantics for S3 writes (Flink's Iceberg/Parquet sink with atomic commits). On restart, replays from Kafka. Deduplication by `event_id` prevents double-writes to Elasticsearch and S3
- **Elasticsearch failure:** Replica shards handle read availability. If an index write fails, the event is retried. If Elasticsearch is down entirely, events still land in S3 (the source of truth). Elasticsearch can be rebuilt from S3 data — it's a search index, not the primary store
- **DLQ:** Events that fail schema validation or PII redaction go to DLQ. These are investigated immediately because a DLQ audit event is a compliance gap. DLQ events include the original event + the error reason + a timestamp, enabling post-mortem replay after the issue is fixed
- **Integrity check failure:** If the batch integrity audit detects a hash chain break, this is a critical security incident: someone may have tampered with the audit log. The response protocol: (1) freeze the affected S3 prefix (additional Object Lock), (2) notify the security team, (3) compare the S3 data against Kafka (if still within retention) to identify which records were altered, (4) file a regulatory incident report

## Key Trade-offs

- **Completeness vs performance:** Logging every operation adds overhead. At 500K events/sec, even 1KB per event is 500MB/sec of audit traffic. Sampling (logging only 10% of events) reduces cost but creates compliance gaps — regulators expect 100% coverage. Compromise: log all state-changing operations (writes) at full fidelity; log read operations at reduced fidelity (count per resource per hour, not individual reads) unless the resource is classified as sensitive
- **Immutability vs GDPR right-to-deletion:** True immutability conflicts with GDPR's right to erasure. Our PII separation strategy resolves this: audit logs are immutable and anonymized; PII mappings are mutable and deletable. When a user requests deletion, we delete the PII mapping. The audit log retains `"actor_id: a3f2..."` which is no longer linkable to the individual. This is a well-established pattern accepted by EU regulators
- **Search speed vs storage cost:** Elasticsearch provides sub-second search but costs ~$50/TB/month. S3 + Athena provides 5-30 second search at ~$2/TB/month. For 130TB of historical data, the difference is $6500/month vs $260/month. We use Elasticsearch only for the hot tier (30 days, 1.5TB) and Athena for everything else
- **Cryptographic signing vs throughput:** Signing each event adds ~0.5ms of CPU time per event. At 500K events/sec, this requires significant CPU capacity in the signing service. Alternative: sign batches (hash of 1000 events) instead of individual events. This reduces signing overhead by 1000x but means individual event verification requires the entire batch. We use batch signing (per-second batches) as the default, with individual signing available for high-sensitivity events (permission changes, admin actions)

## What Fails First

**Elasticsearch ingestion throughput** is the first bottleneck. At 500K events/sec with 1KB per event and Elasticsearch's indexing overhead (inverted index, doc values, translog), each Elasticsearch node can handle ~30K events/sec. You need ~17 data nodes just for ingestion, plus replicas. During an incident (services emit 10x normal audit events), Elasticsearch falls behind. Consumer lag grows in Kafka.

Solutions: (1) Elasticsearch is a search index, not the source of truth. S3 is the source of truth. If Elasticsearch falls behind, search is delayed but data is not lost. (2) Separate Elasticsearch clusters for different service tiers (customer-facing services get priority; internal tooling can tolerate lag). (3) Pre-aggregate low-value audit events in Flink before indexing (instead of 1000 individual "item viewed" events, index one aggregate "user viewed 1000 items in 5 minutes").

Second failure: **S3 small file problem.** Flink writing every 30 seconds (checkpoint interval) across 128 partitions creates 256 Parquet files per minute = 370K files per day. After 7 years: ~1 billion files. S3 handles this, but Athena query planning slows down (too many files to scan). Solution: daily compaction job merges small files into larger ones (target: 128MB per file). After compaction, daily file count drops from 370K to ~400.

## v1 vs v2

### v1 — Ship in 3 weeks
- Audit SDK writes directly to Kafka (no local buffer — simpler but risks loss during Kafka outage)
- Single Flink job: enrichment + dedup, writes to Elasticsearch and S3
- No PII redaction (store as-is, handle GDPR manually)
- No cryptographic signing or Merkle tree verification
- No suspicious activity detection
- S3 without Object Lock (rely on IAM policies for immutability — weaker guarantee)
- Elasticsearch only, no Athena (query historical via ES)
- Manual retention management

### v2 — Compliance-grade audit platform
- Audit SDK with local buffer (memory + disk overflow) and at-least-once delivery
- Cryptographic signing service with key rotation
- Multi-stage Flink pipeline: enrichment, suspicious activity detection, compliance policy enforcement, PII redaction
- S3 Object Lock (Compliance mode) for true immutability
- Merkle tree hash chain for tamper detection
- Weekly batch integrity verification
- Tiered storage: Elasticsearch (30 days) -> S3 (1 year) -> Glacier (7 years) with automated ILM
- PII separation (anonymized audit logs + mutable PII mapping for GDPR compliance)
- Athena for cost-effective historical queries
- Compliance dashboard with real-time policy violation tracking
- SIEM integration for security incident detection
- Regulatory export service for audit report generation
- Self-monitoring: the audit system's own operations are audit-logged by a separate instance
