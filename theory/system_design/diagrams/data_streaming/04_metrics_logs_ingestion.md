# Metrics and Logs Ingestion Platform — Architecture Design

## Requirements

### Functional
- Ingest metrics (counters, gauges, histograms) from thousands of microservices and infrastructure components
- Ingest structured and unstructured logs from applications, containers, and system processes
- Support multi-dimensional metric queries (filter by service, region, instance, custom labels)
- Full-text search across logs with structured field filtering
- Alerting on metric thresholds and log pattern detection
- Configurable retention: 15 days hot (fast queries), 90 days warm, 1 year cold (archive)

### Non-Functional
- Ingestion latency: metrics visible in dashboards within 15 seconds, logs searchable within 30 seconds
- Query latency: sub-second for recent data (last 1 hour), sub-5 seconds for historical queries (last 30 days)
- Handle 5M metrics data points/sec and 2M log lines/sec
- Back-pressure handling: producers must not fail when ingestion pipeline is slow
- Multi-tenant isolation: per-team quotas and namespace separation

## Scale Estimates
- **Metrics:** 5M data points/sec, ~100K unique time series per service, ~500 services = 50M active time series
- **Logs:** 2M log lines/sec, average 500 bytes per line = 1GB/sec = 86TB/day raw
- **Storage:** Hot tier (15 days): ~50TB metrics + 1.3PB logs; with compression ~200TB total
- **Queries:** 10K dashboard loads/sec, 500 ad-hoc log searches/sec
- **Cardinality concern:** High-cardinality labels (user_id, request_id) on metrics can explode time series count

## Architecture Decisions

### OpenTelemetry Collector as the Universal Agent
Rather than running separate agents for metrics (Prometheus node_exporter), logs (Fluentd), and traces (Jaeger agent), use the OpenTelemetry Collector as a unified agent on every node. It handles all three signals, supports multiple output backends, and provides built-in sampling, batching, and retry. The key advantage is the OTLP protocol — a single standardized wire format that every modern observability tool understands.

### Kafka as a Buffer Between Collection and Storage
The critical insight: observability pipelines must never drop data during ingestion spikes (deploy storms, incident cascades), but storage backends have finite write throughput. Kafka acts as a shock absorber — it can buffer hours of data at ingestion rate while downstream consumers catch up. Without Kafka, a spike of error logs during an incident would overwhelm Elasticsearch exactly when you need it most.

### Mimir/Thanos Over Prometheus for Metric Storage at Scale
Standalone Prometheus hits a wall at ~10M active time series per instance and has no native horizontal scaling. Mimir (Grafana) or Thanos provides horizontally scalable, long-term metric storage with global query federation. Mimir's architecture uses S3 for block storage with in-memory caching of recent blocks, giving us unlimited retention at object storage prices while maintaining fast query performance for recent data.

### Elasticsearch Over Loki for Logs (at This Scale)
At 2M lines/sec, Grafana Loki's index-free design is cheaper to run but provides slower ad-hoc searches (it must scan chunks). Elasticsearch's inverted index provides sub-second full-text search, which is critical during incidents. The trade-off is 3-5x higher storage cost (indices are expensive). Compromise: Elasticsearch for last 15 days (hot), Loki or S3+Athena for 15-365 days (warm/cold).

### Downsampling as a First-Class Pipeline Stage
Raw 10-second resolution metrics are needed for last-hour debugging but wasteful for last-month trend analysis. The Flink downsampling job pre-aggregates raw metrics into 1-minute, 5-minute, and 1-hour rollups. This reduces storage by 10-60x for historical data without losing analytical value. The key is to downsample with the right aggregation functions: preserve min/max/avg/count/sum so that dashboards can compute percentiles from the rollups.

## Pipeline Stages

1. **Producers:** Application metrics via OTLP SDK, infrastructure metrics via OTLP Collector scraping (Prometheus-style), application logs via stdout/stderr captured by container runtime, K8s events via kube-state-metrics
2. **Collection Layer:** OTel Collectors run as DaemonSets (one per node). They batch, compress, and forward telemetry to Kafka. Rate limiter at the collector prevents runaway services from flooding the pipeline (per-namespace quotas)
3. **Kafka Buffer:** Separate topics for metrics (`metrics-raw`, 64 partitions) and logs (`logs-raw`, 128 partitions). Partitioned by `service_name` for metrics locality and `namespace` for logs. Retention: 48 hours for replay
4. **Flink Processing (Metrics):** Consumes `metrics-raw`, performs label validation (reject high-cardinality abuse), downsamples to configurable resolutions, detects anomalies (EWMA-based threshold alerting), writes to Mimir via remote_write API
5. **Flink Processing (Logs):** Consumes `logs-raw`, enriches with K8s metadata (pod name, node, namespace), extracts structured fields from unstructured logs (regex/grok patterns), routes ERROR/FATAL logs to anomaly detector, writes to Elasticsearch
6. **Anomaly Detection:** Receives metric alerts and error log spikes, correlates them (error rate spike + latency spike = likely incident), fires alerts to Alertmanager which routes to PagerDuty/Slack
7. **Compaction & Retention:** Batch job (daily) compacts Mimir blocks, manages Elasticsearch ILM (Index Lifecycle Management) policies, archives expired data to S3 as Parquet

## Partitioning Strategy

- **Kafka metrics:** Partition by `service_name` — this groups all metrics from one service on one partition, enabling Flink to efficiently apply per-service downsampling rules and anomaly baselines without cross-partition state
- **Kafka logs:** Partition by `namespace` — K8s namespaces roughly map to teams, enabling per-team processing quotas
- **Mimir:** Shards data by metric name hash across ingesters. Query-frontend splits large queries by time range across queriers for parallelism
- **Elasticsearch:** Time-based indices (`logs-2024-01-15`) with ILM policies: hot (SSDs, 2 replicas, 15 days), warm (HDDs, 1 replica, 90 days), delete after 365 days. Shard count per index = total data size / 30GB (Elasticsearch sweet spot)

## Failure Handling

- **OTel Collector failure:** Collectors are stateless DaemonSets — K8s restarts them immediately. During downtime (~30 seconds), metrics and logs buffer in the application's OTLP exporter queue (configurable 5K items). If the queue fills, newest data is dropped (prefer freshness over completeness for observability data)
- **Kafka failure:** Standard 3x replication. If a broker dies, producers retry to the new leader. Collector's Kafka exporter handles retries with exponential backoff. Maximum data loss: the last un-replicated write (~1 second of data)
- **Flink failure:** Checkpoints every 60 seconds. Metric downsampling is idempotent (writing the same aggregated data point twice is fine). Log enrichment is also idempotent. Anomaly detector may re-fire an alert on replay, but Alertmanager deduplicates within its grouping window
- **Elasticsearch failure:** Replica shards provide read availability during node failure. Auto-rebalancing recovers after node replacement. During recovery, log ingestion may slow (Elasticsearch becomes write-heavy rebuilding shards). Flink applies back-pressure to Kafka, which absorbs the spike
- **DLQ:** Malformed metrics/logs (schema violations) go to `telemetry-dlq`. Volume is monitored. Common cause: application emitting metrics with unregistered label names. Resolution: update the label validation whitelist

## Key Trade-offs

- **Push vs pull for metrics:** OTLP uses push (applications send to collector), while Prometheus uses pull (Prometheus scrapes applications). Push is better for ephemeral containers (short-lived pods may die before Prometheus scrapes them) but requires back-pressure handling. We use push with admission control at the collector
- **High cardinality control vs flexibility:** Rejecting high-cardinality labels (e.g., `request_id` as a metric label) prevents time series explosion but limits what engineers can query. Balance: allow high cardinality in logs (free-form), restrict it in metrics (max 1000 unique values per label)
- **Compression vs query speed:** Aggressively compressing log data reduces storage costs but increases query latency (decompression overhead). Elasticsearch's `best_speed` codec for hot tier, `best_compression` for warm
- **Alerting on streams vs alerting on stored data:** Stream-based alerting (Flink anomaly detector) gives sub-minute detection but has limited context. Storage-based alerting (Prometheus alert rules querying Mimir) can use complex PromQL expressions but adds 1-2 minutes of latency. We use both: stream for simple threshold alerts, storage for complex multi-metric correlation alerts

## What Fails First

**Elasticsearch write throughput** during log storms. When an incident causes a cascade of ERROR logs, the log volume can spike 10-100x. Elasticsearch's write path (index, analyze, replicate) becomes the bottleneck. Symptoms: Kafka consumer lag grows, Flink back-pressures, eventually Kafka retention expires and logs are lost.

Solutions: (1) Tail sampling in the OTel Collector — during storms, sample only 10% of repetitive error logs, (2) Flink-side rate limiting — cap writes to Elasticsearch at its sustainable throughput and overflow to S3, (3) Separate Elasticsearch clusters for high-volume and low-volume log sources, (4) Consider Loki for high-volume sources where full-text search is less critical.

Second failure point: **metric cardinality explosion**. One service adding `user_id` as a metric label creates millions of time series overnight. Mimir's ingester runs out of memory, and the TSDB compactor falls behind. Prevention is better than cure: enforce cardinality limits at the Flink processing stage with circuit-breaker patterns (if a metric's cardinality exceeds 10K, drop the offending label and alert the owning team).

## v1 vs v2

### v1 — Ship in 3 weeks
- OTel Collectors pushing directly to Mimir (metrics) and Elasticsearch (logs) — no Kafka buffer
- Basic Prometheus alerting rules in Mimir, no streaming anomaly detection
- No downsampling — store raw resolution, rely on Mimir's built-in compaction
- Single Elasticsearch cluster, uniform indexing (no hot/warm/cold tiers)
- Grafana dashboards for metrics, Kibana for logs (separate UIs)
- Manual per-service rate limits configured in OTel Collector

### v2 — Production-grade observability
- Kafka buffer between collection and storage for spike absorption
- Flink-based metric downsampling (10s raw, 1m/5m/1h rollups)
- Flink-based log enrichment with K8s metadata and grok parsing
- Streaming anomaly detection with metric-log correlation
- Elasticsearch ILM with hot/warm/cold tiers + S3 archival
- Unified query API (Grafana as single pane for metrics + logs + traces)
- Multi-tenant isolation with per-namespace quotas enforced in Flink
- Automatic high-cardinality detection and circuit breaking
- Compaction and retention management as automated batch jobs
