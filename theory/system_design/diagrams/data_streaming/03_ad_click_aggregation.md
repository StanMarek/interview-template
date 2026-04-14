# Ad Click Aggregation Pipeline — Architecture Design

## Requirements

### Functional
- Count ad clicks and impressions per ad campaign, per advertiser, per time window (1min, 5min, 1hr, 1day)
- Compute CTR (click-through rate) = clicks / impressions in near real-time
- Support advertiser-facing dashboards showing real-time campaign performance
- Feed billing system with accurate click/impression counts for CPC/CPM charging
- Detect and filter click fraud (bot clicks, click farms)
- Support retroactive corrections when fraud is detected after initial aggregation

### Non-Functional
- Exactly-once semantics for billing-related counts (money is involved)
- Sub-minute aggregation latency for dashboard metrics
- Billing reconciliation within 1 hour (batch path corrects streaming inaccuracies)
- Handle 1M+ click events/sec at peak (global ad network)
- Event ordering within a campaign must be preserved for accurate CTR calculation
- 99.99% durability — losing click events means losing revenue

## Scale Estimates
- **Events/sec:** 1M clicks/sec + 10M impressions/sec at peak
- **Unique campaigns:** ~10M active ad campaigns
- **Storage:** Raw events ~5TB/day, aggregated metrics ~50GB/day
- **Query volume:** 100K dashboard queries/sec (heavily cached at Druid level)
- **Billing precision:** Must match to within 0.1% of batch-reconciled counts

## Architecture Decisions

### Lambda Architecture (Streaming + Batch Reconciliation)
For ad clicks, money accuracy trumps all. The streaming path provides real-time approximate counts for dashboards. The batch path (hourly Spark jobs) reprocesses raw events from S3 to produce "ground truth" counts used for billing. The batch counts override streaming counts when they diverge. This dual-path approach is worth the complexity because: (1) streaming can lose events during failures or double-count during rebalancing, (2) fraud detection runs as batch ML and retroactively adjusts counts, (3) advertisers trust hourly reconciled numbers over real-time approximations.

### Exactly-Once via Kafka Transactions + Flink Checkpointing
For the streaming path feeding billing, we use Kafka's exactly-once semantics: **idempotent + transactional producers** (write atomically across partitions) and consumers configured with `isolation.level=read_committed` (only read committed transactions). This is combined with Flink's `TwoPhaseCommitSinkFunction` — checkpoints coordinate the pre-commit and commit phases so Kafka offsets + external sink writes are atomic across recovery. Adds ~50-100ms end-to-end latency (transaction boundary aligned with checkpoint interval) but ensures no double-counting in the billing path. For the dashboard path (OLAP), at-least-once with deduplication is sufficient — slight over-counting on a real-time dashboard is acceptable.

### Click-Impression Interval Join
To compute CTR, we must join click events with their corresponding impression events. This is a temporal join: a click should match the impression that was served within the last N minutes (click attribution window, typically 30 minutes). Flink's interval join handles this efficiently by keeping impression state for the attribution window and matching incoming clicks against buffered impressions. The alternative (joining in the database after the fact) doesn't work at 10M events/sec.

### Apache Druid for OLAP Over ClickHouse
Druid is optimized for time-series aggregation with sub-second query latency on pre-aggregated rollups. It natively ingests from Kafka (real-time ingestion) and supports exact/approximate queries with configurable granularity. ClickHouse is faster for ad-hoc queries but lacks Druid's native Kafka ingestion and built-in tiered storage. For a dashboard-heavy workload with known query patterns, Druid's rollup strategy is more cost-effective.

## Pipeline Stages

1. **Event Sources:** Web/Mobile SDKs emit click and impression events with `click_id`, `impression_id`, `campaign_id`, `advertiser_id`, `timestamp`, `user_agent`, `ip_hash`. Ad serving logs provide the impression-to-campaign mapping
2. **Kafka Ingestion:** Separate topics: `ad-clicks` (partitioned by `campaign_id`) and `ad-impressions` (partitioned by `campaign_id`). Same partition key enables co-partitioned joins in Flink
3. **Flink Deduplication:** First stage deduplicates by `click_id` using a Bloom filter with 24-hour TTL. This catches SDK retries and network-level duplicates. False positive rate of 0.1% is acceptable (undercounting by 0.1% is better than overcounting for billing)
4. **Flink Aggregation:** 1-minute tumbling windows aggregate clicks and impressions per `(campaign_id, ad_group_id, geo)`. Emits aggregated counts to Druid for real-time dashboards
5. **Flink Click-Impression Join:** Interval join with a 30-minute attribution window. Matched pairs produce a `click_with_impression` event used for CTR computation and billing attribution
6. **Storage:** Druid ingests aggregated metrics (real-time dashboards). PostgreSQL stores billing-ready joined events. S3 stores raw events as Parquet for batch reprocessing
7. **Batch Reconciliation:** Hourly Spark job reads raw events from S3, performs exact deduplication, exact joins, and fraud filtering. Produces reconciled counts that overwrite streaming counts in the billing database
8. **Fraud Detection:** Batch ML pipeline (daily) scores click events for fraud probability. Flagged clicks are excluded from billing reconciliation. Feedback loop sends fraud patterns to streaming pipeline for real-time blocking

## Partitioning Strategy

- **Kafka:** Both `ad-clicks` and `ad-impressions` partitioned by `campaign_id`. This is critical for the interval join in Flink — co-partitioned topics allow the join to be performed locally on each Flink subtask without a network shuffle. Trade-off: hot campaigns (Super Bowl ad) create hot partitions. Mitigation: add a random suffix for the top 1% of campaigns and merge downstream
- **Druid:** Segment by time (hourly) with `campaign_id` as the primary dimension for rollup. This matches the query pattern (advertisers always filter by campaign and time range)
- **S3:** Partitioned by `date/hour/campaign_id_prefix` for efficient Spark scanning during batch reconciliation

## Failure Handling

- **Flink job failure:** Checkpoints to S3 every 30 seconds using Flink's exactly-once barrier mechanism. On restart, replays from Kafka offsets stored in the checkpoint. The interval join state (buffered impressions) is restored from the checkpoint, so no joins are lost
- **Kafka broker failure:** Replication factor 3 across availability zones. ISR (in-sync replica) threshold of 2 ensures no acknowledged writes are lost. Producers use `acks=all` for billing-critical topics
- **DLQ strategy:** Events failing schema validation or causing processing exceptions go to `ad-clicks-dlq`. Fraud-flagged events also go to a separate `fraud-flagged` topic for investigation. DLQ is monitored with alerts if depth exceeds 10K events
- **Billing reconciliation:** If the streaming count deviates from batch count by >1%, an alert fires. The batch count always wins for billing. Advertisers see a "provisional" badge on real-time numbers and "finalized" badge after hourly reconciliation
- **Replay:** Full replay from S3 (indefinite retention). Kafka retention is 72 hours for quick reprocessing

## Key Trade-offs

- **Exactly-once vs throughput:** Enabling Kafka transactions reduces throughput by ~30% (from 1M to 700K events/sec per partition). Worth it for billing accuracy but means we need more partitions
- **Real-time vs batch accuracy:** Streaming provides ~99.5% accurate counts within seconds. Batch provides 99.99% accurate counts within 1 hour. Advertisers see both, clearly labeled. This dual-view builds trust while still providing real-time feedback
- **Attribution window size:** A 30-minute interval join window means Flink must hold up to 300M impression events in state (10M/sec * 30 min * 1 fraction that's active). This is the dominant memory cost. Reducing the window to 10 minutes saves 2/3 of state but misses late-attributed clicks
- **Fraud detection latency:** Real-time fraud detection (in the streaming pipeline) catches simple patterns (same IP, rapid clicks) but misses sophisticated fraud (coordinated bot networks). Batch ML catches more fraud but the advertiser has already been billed for those clicks. Compromise: charge provisionally, adjust after reconciliation

## What Fails First

**Flink state size from the interval join** is the first bottleneck. At 10M impressions/sec with a 30-minute window, the join operator must buffer ~18B impression records. Even at 100 bytes per record, that's ~1.8TB of state, far exceeding memory. The solution is to use Flink's RocksDB state backend (disk-based) with SSD storage, but this trades latency (disk reads for each lookup) for capacity. Further optimization: only buffer impressions for active campaigns (drop impressions for expired/paused campaigns before the join).

Second failure point: **Kafka partition hot spots** during major advertising events. A single campaign running a Super Bowl ad might generate 100K clicks/sec, all landing on one partition. This overwhelms one Flink subtask while others are idle. Mitigation: salted partitioning with downstream aggregation merge.

## v1 vs v2

### v1 — Ship in 3 weeks
- Single Kafka topic for all ad events (clicks + impressions)
- At-least-once semantics with application-level deduplication
- No interval join — compute CTR from separate click and impression counts (less accurate but simpler)
- Single Flink job: dedup + aggregate
- Druid for dashboards, no billing integration yet
- No fraud detection — manual review of flagged advertisers
- Basic DLQ with manual inspection

### v2 — Billing-grade accuracy
- Separate topics for clicks and impressions, co-partitioned by campaign_id
- Exactly-once with Kafka transactions + Flink checkpointing
- Interval join for click-impression attribution (accurate CTR)
- Batch reconciliation pipeline (hourly Spark jobs)
- ML-based fraud detection (daily batch + real-time rules)
- PostgreSQL billing integration with provisional/finalized states
- Advertiser-facing dashboard with real-time + reconciled views
- Salted partitioning for hot campaigns
- S3 data lake with Parquet for long-term analytics and auditing
