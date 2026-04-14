# Centralized Observability for Metrics, Logs, and Tracing -- Architecture Design

## Requirements
### Functional
- Collect, store, and query three signal types: metrics (time series), logs (structured events), traces (distributed request paths)
- Correlate across signals: jump from a metric alert to related traces to relevant logs using shared identifiers (trace ID, service name, timestamp)
- Support OpenTelemetry (OTLP) as the primary ingestion protocol
- Dashboard creation and sharing (Grafana-style)
- Alert rules on metrics (threshold, rate-of-change, anomaly) and logs (pattern match, count)
- SLO tracking with error budgets
- Tail-based sampling for traces (keep interesting traces, sample routine ones)
- Multi-tenant support: each team sees only their own data

### Non-Functional
- Ingest 10M+ data points per second (metrics + logs + trace spans)
- Query latency: < 2s for dashboard panels, < 10s for ad-hoc log searches
- Retention: metrics 90 days at full resolution, 2 years downsampled; logs 30 days hot, 1 year cold; traces 7 days
- 99.9% availability for ingestion (never lose data during incidents -- that is when you need observability most)
- Graceful degradation: if storage is overloaded, buffer in Kafka and catch up

## Scale Estimates
- 500 microservices, 5000 pods emitting telemetry
- Metrics: 5M active time series, 2M data points/sec ingested
- Logs: 500K log lines/sec, 50TB/day raw
- Traces: 1M spans/sec before sampling, 100K/sec after sampling
- Storage: 100TB metrics (90 days), 500TB logs (30 days), 50TB traces (7 days)
- Query load: 10K dashboard queries/min, 500 ad-hoc queries/min

## Architecture Decisions

### Three Pillars, One Correlation Layer
Metrics, logs, and traces have fundamentally different storage and query patterns. Metrics are numeric time series (best stored in columnar/TSDB format). Logs are text events (best stored in inverted-index or column stores). Traces are trees of spans (best stored in graph-aware or wide-column stores). Forcing all three into one store is a bad idea. Instead, use specialized stores but correlate them via shared labels: trace ID, service name, pod name, timestamp. The correlation layer (Grafana) links between stores seamlessly.

### Kafka as a Signal Bus
Placing Kafka between collectors and storage backends provides: (1) Backpressure handling -- if a storage backend is slow, data buffers in Kafka rather than being dropped. (2) Fan-out -- one ingested signal can go to multiple consumers (real-time alerting + storage). (3) Replay -- if a storage backend fails, replay from Kafka after recovery. Trade-off: Kafka adds latency (seconds) and operational complexity. Worth it for the reliability at this scale.

### Tail-Based Sampling for Traces
Head-based sampling (decide at request start whether to trace) is simple but misses interesting traces. Tail-based sampling (collect all spans, then decide after the trace completes) keeps traces that are slow, errored, or otherwise interesting. Trade-off: tail-based requires buffering all spans until the trace is complete (seconds to minutes), consuming significant memory. The sampling service must be horizontally scalable.

### OTel Collector as the Universal Agent
Instead of running separate agents for metrics (Prometheus scraper), logs (Fluentd), and traces (Jaeger agent), use a single OpenTelemetry Collector. It receives all three signal types, processes (enrich, filter, batch), and exports to the appropriate backend. This reduces per-node overhead and provides a uniform configuration model. Trade-off: OTel Collector is a single point of failure per node, though it has built-in retry and persistence.

## Component Breakdown

- **Application Services**: Instrumented with OTel SDK. Emit metrics (counters, histograms, gauges), structured logs, and trace spans. Auto-instrumentation for common frameworks (HTTP, gRPC, database clients).
- **Infrastructure**: Kubernetes, databases, load balancers emit metrics via Prometheus exporters and logs via stdout/syslog. Node-level metrics (CPU, memory, disk, network) from node exporters.
- **OTel SDK (Instrumentation)**: Library embedded in each service. Creates spans, records metrics, and formats logs. Exports via OTLP to the collector. Supports context propagation (W3C Trace Context headers).
- **Node Agent (OTel Collector)**: Runs as a DaemonSet on each node. Collects logs from container stdout (file-based or journald), receives OTLP from local services, and forwards to the gateway collector. Also scrapes Prometheus endpoints on the node.
- **OTel Collector (Gateway)**: Centralized collector fleet. Receives from all node agents. Processes: enrichment (add k8s labels, region), filtering (drop debug logs in production), batching. Routes signals to Kafka topics by type.
- **Kafka (Signal Bus)**: Three topic groups: metrics, logs, traces. Partitioned by service name for ordered processing. Retention: 24-48 hours (enough to survive backend outages). Consumer groups for each backend.
- **Tail-Based Sampling**: Consumes trace spans from Kafka. Buffers until trace is complete (or timeout). Applies sampling rules: keep 100% of error traces, 100% of slow traces (> p99), 10% of normal traces. Outputs sampled traces to the trace store.
- **Enrichment (Labels, Tags)**: Adds contextual labels: k8s namespace, deployment name, region, availability zone. Normalizes label names across services. Critical for effective querying and alerting.
- **Metrics Store (Prometheus/Mimir)**: Time-series database. Supports PromQL queries. Mimir provides horizontal scalability and long-term storage. Stores at full resolution for 90 days, downsampled (5m, 1h aggregates) for 2 years.
- **Log Store (Loki/OpenSearch)**: Loki stores logs indexed by labels (not full-text indexed), using object storage for the actual log content. OpenSearch provides full-text search. Loki is cheaper, OpenSearch is more flexible for ad-hoc queries.
- **Trace Store (Tempo/Jaeger)**: Stores sampled traces. Tempo uses object storage (S3) and is very cost-effective. Jaeger uses Elasticsearch or Cassandra. Must support trace ID lookup (get one trace) and service dependency graph queries.
- **Long-Term Storage (S3/GCS)**: Cheap, durable storage for downsampled metrics, cold logs, and old traces. Query via Thanos or Mimir for metrics, Loki for logs.
- **Grafana (Dashboards)**: Unified UI for all three signal types. Data source plugins for each backend. Supports variables, drill-down, and cross-signal linking (click on a metric spike -> see traces from that time -> see logs from those traces).
- **Trace Explorer**: Waterfall view of distributed traces. Shows span duration, errors, and service dependencies. Links to logs via trace ID.
- **Log Explorer**: Search and filter logs by labels and content. Links to traces via trace ID embedded in log lines.
- **SLO Tracker**: Defines SLOs (99.9% success rate, p99 latency < 500ms). Tracks error budget burn rate. Alerts when error budget is being consumed too fast.
- **Alert Rules Engine**: Evaluates PromQL rules on metrics every 15-60 seconds. Fires alerts when conditions are met for a configured duration. Multi-window alerting for burn rate.
- **Alertmanager**: Receives alerts, deduplicates, groups related alerts, routes to appropriate channels (PagerDuty for critical, Slack for warning). Supports silencing and inhibition.
- **Runbook Automation**: Links alerts to runbooks. Can auto-execute simple remediations (restart pod, scale up, revert deploy) for known failure patterns.

## Operational Concerns
- **Deploying observability changes safely**: The observability stack is itself a critical service. Use canary deploys for collector changes (route 5% of traffic to new version). Test alert rules in "dry-run" mode before activating. Dashboard changes are low-risk (they only affect visualization).
- **Blast radius of a bad collector config**: A misconfigured collector can drop all telemetry from a node. Mitigation: validate collector config in CI, canary deploy, and monitor collector self-metrics (number of spans/logs exported). If export count drops, auto-rollback.
- **Cardinality explosion**: A new label with high cardinality (e.g., user ID as a metric label) can explode the metrics store. Mitigation: cardinality limits per metric, label validation at the collector level, and alerts on cardinality growth.
- **Cost management**: Observability storage is often the largest infrastructure cost. Control costs via: sampling traces, dropping debug logs in production, downsampling old metrics, and using tiered storage (hot SSD for recent data, cold object storage for old data).

## Failure Modes
- **Kafka cluster failure**: All telemetry ingestion stops. Node agents buffer locally (OTel Collector has persistent queue on disk, typically 1GB per node). When Kafka recovers, agents flush buffers. Data is delayed but not lost, as long as the outage is shorter than the local buffer capacity.
- **Metrics store failure**: Dashboards show gaps, alerts stop evaluating. Running services are unaffected. This is the worst time to lose observability (during an incident). Mitigation: multi-zone deployment for the metrics store, and Kafka buffering to prevent data loss.
- **Log store overloaded during incident**: During incidents, log volume spikes (error logs, debug logging enabled). The log store cannot keep up. Mitigation: Kafka absorbs the burst, and the log store catches up. Also, rate limiting per service at the collector level.
- **Trace sampling drops critical trace**: A slow or error trace is incorrectly sampled out. Mitigation: always keep 100% of error traces and traces with explicit "force sample" flags. The tail-based sampler should err on the side of keeping too much rather than too little.

## Key Trade-offs
- **Storage cost vs Query flexibility**: Full-text indexing (OpenSearch) for logs is expensive but enables ad-hoc searches. Label-only indexing (Loki) is 10x cheaper but requires knowing the labels to query by. Start with Loki for known queries, add OpenSearch if ad-hoc search becomes critical.
- **Sampling rate vs Trace completeness**: Higher sampling keeps more data but increases cost. Lower sampling saves cost but misses interesting traces. Tail-based sampling is the best compromise: keep all interesting traces, sample the boring ones.
- **Agent overhead vs Telemetry richness**: More instrumentation means richer data but higher CPU/memory overhead per service. Auto-instrumentation adds ~1-2% CPU overhead. Manual instrumentation for critical paths gives the best signal-to-noise ratio.
- **Real-time alerting vs Kafka latency**: Kafka adds 1-5 seconds of latency. For real-time alerting on metrics, you might want to bypass Kafka and send directly from collector to the alerting pipeline. Trade-off: two paths to maintain, but critical for time-sensitive alerts.

## What Fails First
Log storage during an incident. When something goes wrong, every service starts logging errors at a much higher rate than normal. This 10-100x spike in log volume overwhelms the log store's write capacity. Queries become slow or fail. Engineers cannot search logs during the incident when they need them most. The fix: aggressive rate limiting at the collector level (cap log volume per service), pre-provisioned burst capacity in the log store, and ensuring that the most recent logs are always available (even if old logs are being processed slowly).

## v1 vs v2
### v1 (Minimum Viable Observability)
- Prometheus for metrics (single instance, 30-day retention)
- Loki for logs (single instance, 14-day retention)
- Jaeger for traces (in-memory, 48-hour retention)
- Grafana for dashboards and exploration
- OTel Collector as DaemonSet
- Basic Prometheus alerting rules with Alertmanager -> Slack
- Head-based sampling at 10% for traces
- No Kafka (direct collector -> backend)

### v2 (Production Grade)
- Mimir for horizontally scalable metrics with long-term storage
- Loki with S3 backend and 90-day retention
- Tempo for scalable trace storage with S3
- Kafka as signal bus with backpressure handling
- Tail-based sampling with error/latency-aware rules
- OTel Collector gateway fleet with enrichment pipeline
- SLO tracking with error budget alerting
- Cross-signal correlation (metrics -> traces -> logs)
- Cardinality management and per-tenant quotas
- Runbook automation for common alert responses
- Multi-region deployment for observability stack itself
- Cost allocation dashboards (observability cost per team/service)
