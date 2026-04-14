# Stream Processing System for Anomaly Detection — Architecture Design

## Requirements

### Functional
- Detect anomalies in real-time across multiple data streams: application metrics (latency, error rate, throughput), infrastructure metrics (CPU, memory, disk I/O), network flows, and business KPIs (revenue, conversion rate, sign-ups)
- Support multiple detection methods: statistical (z-score, MAD), ML-based (Isolation Forest, autoencoders), and rule-based (static thresholds)
- Maintain per-metric baselines that adapt to daily/weekly seasonality and gradual trends
- Correlate anomalies across related metrics to reduce alert fatigue (group correlated anomalies into "incidents")
- Support human feedback loop: analysts label anomalies as true/false positives to improve detection accuracy
- Enable auto-remediation: trigger predefined runbooks for known anomaly patterns

### Non-Functional
- Detection latency: anomaly detected within 60 seconds of the metric deviating
- Handle 10M metrics time series simultaneously (typical for a large platform with thousands of microservices)
- False positive rate below 5% (alert fatigue is the primary enemy of anomaly detection systems)
- Support seasonal baselines: metrics that are normal at 3pm but anomalous at 3am
- Hot model swap: update detection models without pipeline downtime
- Self-monitoring: the anomaly detection system must detect anomalies in itself

## Scale Estimates
- **Time series:** 10M active time series (each microservice exposes ~1000 metrics, 10K microservices)
- **Data points/sec:** 100M data points/sec (10M series * 10-second resolution)
- **Anomaly rate:** ~0.1% of data points are genuinely anomalous = 100K anomalies/sec raw, ~1000/sec after correlation
- **Baseline storage:** 10M series * ~1KB per baseline model = ~10GB (fits in memory across a Flink cluster)
- **Training data:** ~1TB/month of labeled anomaly data (from feedback loop)
- **Alert volume:** Target: fewer than 100 actionable alerts/day after correlation and deduplication

## Architecture Decisions

### EWMA + Seasonal Decomposition for Baselines Over Static Thresholds
Static thresholds ("alert if CPU > 80%") create massive false positive rates because they ignore context. 80% CPU at 2am is anomalous; at 2pm during peak traffic, it's normal. Our baseline calculator uses Exponentially Weighted Moving Average (EWMA) combined with seasonal decomposition: `expected_value = trend + seasonal_component + noise`. The EWMA adapts to gradual trend shifts, while the seasonal component (7-day cycle with 24-hour sub-cycles) captures recurring patterns. Anomaly = |actual - expected| / estimated_std_dev > threshold. This reduces false positives by 80% compared to static thresholds.

### Multi-Layer Detection: Statistical + ML + Correlation
Each layer serves a different purpose:
- **Statistical detector (z-score, MAD):** Fast, interpretable, works well for univariate anomalies (single metric deviates from its own baseline). Median Absolute Deviation (MAD) is more robust than standard deviation for metrics with heavy tails (latency distributions). Detects ~60% of real anomalies
- **ML detector (Isolation Forest):** Catches multivariate anomalies that statistical methods miss — when no single metric is individually anomalous, but the *combination* is unusual (CPU normal + memory normal + disk I/O 3x normal = anomalous). Trained on historical feature vectors (window of multiple metrics), updated weekly
- **Cross-metric correlator:** Groups simultaneous anomalies across related metrics into a single incident. If CPU, memory, latency, and error rate all spike together, that's one incident, not four independent alerts. Uses temporal correlation (anomalies within a 2-minute window) and service topology (metrics from the same service or dependent services)

### Flink Over Kafka Streams for Anomaly Detection
Kafka Streams processes each record independently with limited windowing support. Anomaly detection requires maintaining per-series state (baselines, rolling statistics) and performing windowed joins across metrics. Flink's keyed state (one state per metric series, stored in RocksDB), event-time windowing, and process functions provide the expressiveness needed. A single Flink cluster can maintain baselines for 10M series using RocksDB state backend, while Kafka Streams would require managing this state across application instances with more manual coordination.

### Alert Deduplication and Incident Correlation as a Separate Stage
Raw anomaly signals are noisy — a cascading failure produces hundreds of related anomalies across dependent services. Without correlation, the on-call engineer receives 200 alerts instead of 1. The correlator stage groups anomalies by: (1) time proximity (anomalies within 2 minutes), (2) service dependency graph (anomalies in services with known call relationships), (3) shared infrastructure (anomalies in services running on the same node). The output is a ranked list of *incidents*, not individual anomalies. The highest-severity incident is the one affecting the most downstream services.

## Pipeline Stages

1. **Data Sources:** Application metrics via OTLP, infrastructure metrics via Prometheus exporters, network flows via NetFlow/sFlow, business KPIs via application events. All land in Kafka
2. **Kafka Ingestion:** `metrics-stream` (partitioned by `metric_name+tags_hash` for per-series processing), `event-stream` (structured events like deployments, config changes, which serve as context for anomaly root-cause analysis)
3. **Baseline Calculator (Flink):** Maintains per-series state: EWMA level, trend, seasonal components (168 hourly slots for weekly seasonality). On each data point, updates the baseline and computes expected value + prediction interval. Writes baselines to TSDB for visualization. State stored in RocksDB (10M series * 1KB = 10GB, well within SSD capacity)
4. **Statistical Detector (Flink):** Computes z-score: `z = |actual - expected| / estimated_std`. Uses MAD-based robust standard deviation. Flags points with z > 3.5 as anomalies. Also detects rate-of-change anomalies (metric changed more in the last 5 minutes than in the preceding hour). Outputs raw anomaly events to `anomalies-output` topic
5. **ML Detector (Flink):** Applies Isolation Forest model (loaded from MLflow) to a feature vector: the last 10 data points for the metric + correlated metrics from the same service. Isolation Forest scores each point on a 0-1 scale; points above 0.7 are flagged. The model is pre-trained on historical data and hot-swapped via Flink's broadcast pattern (model updates broadcast to all subtasks)
6. **Cross-Metric Correlator (Flink):** Collects anomaly events in a 2-minute event-time window. Within each window, groups anomalies by service dependency graph (loaded as broadcast state from a configuration topic). Computes incident severity based on: number of affected metrics, number of affected services, downstream blast radius. Deduplicates by suppressing anomalies that are already part of a higher-severity incident
7. **Batch Retrain (Spark):** Weekly Spark job retrains Isolation Forest on the latest 30 days of data, incorporating analyst feedback (true/false positive labels). Evaluates new model on holdout set. If precision improves, publishes to MLflow and broadcasts to Flink. Also recomputes seasonal decomposition parameters for all 10M series
8. **Feedback Ingestion:** Analysts label anomalies as true/false positive via the investigation UI. Labels are stored in Elasticsearch alongside the original anomaly and fed back to the training pipeline. Precision/recall metrics are tracked per detection method to identify which method needs improvement
9. **Action Layer:** Alertmanager routes incidents to the right on-call team based on service ownership. Auto-remediation triggers predefined runbooks for known patterns (e.g., "high memory + increasing GC time" -> "restart the service and page the team"). Investigation UI (Grafana) shows the anomaly timeline with related metrics, recent deployments, and suggested root causes

## Partitioning Strategy

- **Kafka `metrics-stream`:** Partition by `hash(metric_name + sorted_tags)` — this ensures all data points for the same time series land on the same partition, enabling correct per-series baseline computation in Flink without shuffle. With 10M series across 256 partitions, each partition handles ~40K series
- **Flink keyed state:** Key by `(metric_name, tags_hash)`. Each key maintains its own baseline model, rolling statistics, and detection state. RocksDB state backend with SSD storage handles the 10GB state
- **`anomalies-output` topic:** Partition by `service_name` — groups all anomalies from one service together for efficient correlation in the downstream correlator
- **TSDB (baselines):** Standard time-based sharding. Each series identified by `metric_name{tags}`. Baseline values stored alongside actual values for comparison in dashboards

## Failure Handling

- **Flink baseline calculator failure:** Checkpoints to S3 every 60 seconds. Baselines are deterministic functions of the data stream, so replaying from checkpoint produces identical baselines. However, during the ~60 seconds of downtime, anomalies may be missed. For critical metrics (availability, error rate), a redundant detection path runs on a secondary Flink cluster with independent checkpointing
- **Model serving failure:** If the Isolation Forest model fails to load or crashes, the ML detector falls back to statistical-only detection (which is always available as it doesn't depend on external models). This degrades multivariate detection but maintains univariate anomaly detection
- **Alert storm handling:** If the correlator produces more than 50 incidents in a 5-minute window, it triggers "alert storm mode": only the top 5 highest-severity incidents are forwarded to Alertmanager, the rest are logged but not paged. This prevents on-call fatigue during cascading failures
- **DLQ:** Malformed metric points (NaN values, negative timestamps) go to DLQ. These often indicate a broken exporter in one service. The DLQ consumer identifies the source service and opens a ticket
- **Self-monitoring:** The anomaly detection system monitors its own metrics: Kafka consumer lag, Flink checkpoint duration, anomaly rate, alert rate, false positive rate. A SEPARATE, simpler monitoring system (Prometheus + static threshold alerts) monitors these meta-metrics. Never use a system to monitor itself

## Key Trade-offs

- **Sensitivity vs specificity:** Lower detection threshold catches more anomalies (fewer false negatives) but generates more alerts (more false positives). In production, false positives are worse than false negatives — engineers who learn to ignore alerts will ignore the real ones too. We bias toward specificity: better to miss a minor anomaly than to cry wolf. The feedback loop continuously calibrates the threshold per metric based on accumulated true/false positive labels
- **Baseline adaptation speed:** Fast adaptation (high EWMA alpha) quickly adjusts to new normals (post-deployment traffic increase) but also adapts to genuine anomalies (a persistent performance degradation becomes the "new normal" within hours). Slow adaptation (low alpha) maintains stable baselines but triggers false positives during legitimate changes. Compromise: use fast adaptation (alpha=0.1) for trend, slow adaptation (alpha=0.01) for seasonality, and accept that the first few hours after a major change will produce some false positives. Alternatively, integrate with deployment events to suppress alerts during change windows
- **Per-metric models vs global model:** Training separate baselines per metric (10M models) is accurate but expensive. A global model that learns general anomaly patterns from all metrics is cheaper but misses metric-specific behavior. We use per-metric statistical baselines (cheap, O(1) state per metric) + a global ML model (one Isolation Forest for feature vectors, re-used across all series). This gives us both specificity and generalization
- **Real-time vs batch detection accuracy:** Streaming detection with 60-second windows has limited context (small sample size). Batch detection with daily analysis has complete context but 24-hour latency. For operational anomalies (outages), real-time is essential. For slow degradation (gradual performance decline), daily batch analysis is more accurate. We run both

## What Fails First

**Flink state size** grows as the number of monitored time series increases. At 10M series with 1KB state each, total state is 10GB — manageable. But if services start exporting high-cardinality metrics (one series per user or per request), the series count can explode to 100M+, pushing state to 100GB+. RocksDB handles this but with degraded performance (more cache misses, longer checkpoint times).

Solutions: (1) Enforce cardinality limits at the ingestion layer — reject metrics with more than 10K unique tag combinations, (2) Use metric pre-aggregation (aggregate per-instance metrics into per-service metrics) to reduce series count, (3) Tier the detection — run full baseline modeling for top 1M critical metrics, run simpler threshold-based detection for the remaining 9M.

Second failure: **false positive fatigue.** Even at 5% false positive rate, with 100K anomalies/sec (0.1% of 100M data points), that's 5000 false positive anomalies per second. After correlation, this might still be 50 false incidents per day. If engineers start ignoring alerts, the system has failed regardless of its detection accuracy. The feedback loop is not a nice-to-have — it's essential for the system's long-term effectiveness.

## v1 vs v2

### v1 — Ship in 3 weeks
- Statistical detection only (z-score with EWMA baseline)
- No seasonal decomposition (baselines adapt via EWMA only)
- No ML models — statistical z-score for all metrics
- No cross-metric correlation — each metric alerts independently
- Basic deduplication (suppress repeated alerts for the same metric within 10 minutes)
- Alertmanager integration with simple routing (alert -> PagerDuty)
- Grafana dashboard showing anomaly overlay on metric charts
- No feedback loop — thresholds configured manually

### v2 — Production anomaly detection
- EWMA + seasonal decomposition for context-aware baselines (7-day seasonality)
- Multi-layer detection: statistical (z-score/MAD) + ML (Isolation Forest) + rules
- Cross-metric correlation with service dependency graph for incident grouping
- Feedback loop: analyst labels integrated into model retraining
- Auto-remediation for known patterns (runbook triggers)
- Alert storm protection (top-N incidents only during cascading failures)
- Hot model swap via Flink broadcast pattern
- Self-monitoring via independent secondary system
- Deployment event integration (suppress alerts during change windows)
- Cardinality control and metric tiering
