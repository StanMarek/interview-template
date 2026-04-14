# Smart City Sensor Ingestion Platform — Architecture Design

## Requirements

### Functional
- Ingest data from heterogeneous IoT sensors: traffic counters, air quality monitors, smart meters (energy/water), CCTV/ANPR cameras, weather stations, parking sensors, noise monitors
- Normalize disparate sensor data formats into a unified event schema
- Provide real-time city operations dashboard with geospatial visualization
- Support threshold-based and anomaly-based alerting (air quality exceeds safe limits, traffic congestion exceeds capacity)
- Enable cross-sensor correlation (e.g., traffic congestion correlated with air quality degradation in the same zone)
- Provide historical analytics for urban planning decisions
- Expose public open-data API for citizen applications and researchers

### Non-Functional
- Ingest 1M+ sensor readings/sec from 500K+ devices
- Handle unreliable network connectivity (sensors go offline, reconnect, send buffered data)
- Sub-30-second latency for real-time dashboard and alerting
- Handle out-of-order and late-arriving events (sensors with intermittent connectivity may send data minutes late)
- Data sovereignty: all data stored within city/country boundaries
- 10-year retention for historical urban planning analytics

## Scale Estimates
- **Devices:** 500K sensors across the city, each reporting every 1-60 seconds depending on type
- **Events/sec:** 1M sustained, 3M peak (rush hour, all traffic sensors at max rate)
- **Event size:** 100-500 bytes per reading (sensor_id, timestamp, location, measurements)
- **Daily volume:** ~100TB raw, ~10TB after aggregation and compression
- **Geospatial queries:** 5K concurrent dashboard users querying arbitrary city zones
- **Historical data:** ~30PB over 10 years (compressed, tiered)

## Architecture Decisions

### MQTT as the IoT Protocol Over HTTP/Kafka Direct
IoT sensors are constrained devices: limited CPU, memory, battery, and often unreliable network. MQTT is designed for this: tiny packet overhead (2 bytes minimum header vs HTTP's ~500 bytes), built-in QoS levels (QoS 1 = at-least-once delivery with persistent sessions), and native support for intermittent connectivity (the broker stores messages for offline devices). Sensors publish to MQTT topics organized by `city/zone/sensor_type/sensor_id`. An MQTT-Kafka bridge then forwards to Kafka for stream processing. This two-layer approach lets edge devices use a lightweight protocol while the backend uses Kafka's full capabilities.

### Edge Gateways for Local Pre-Aggregation
With 500K sensors each sending data every 1-60 seconds, raw event volume can overwhelm the central ingestion pipeline. Edge gateways (small servers deployed in city districts, one per ~5000 sensors) perform local aggregation: averaging 1-second traffic counts into 10-second windows, deduplicating retransmissions, filtering noise (reject readings outside physically plausible ranges). This reduces central ingestion volume by 5-10x. Edge gateways also provide local buffering during network outages between the edge and central cloud.

### TimescaleDB for Hot Metrics Over Pure InfluxDB
TimescaleDB is PostgreSQL with time-series extensions, which means it supports both time-series queries (fast aggregation over time ranges) AND relational queries (join sensor readings with device metadata, zone information). InfluxDB is faster for pure time-series but lacks relational joins. For smart city data, queries like "average air quality in zones where traffic count exceeds 5000/hr" require joining time-series with geospatial data. TimescaleDB handles both in one query.

### PostGIS for Geospatial Aggregation
City dashboard queries are inherently spatial: "show me all sensor readings within this polygon" or "aggregate traffic flow along this corridor." PostGIS provides spatial indexing (R-tree) for efficient geospatial queries. The Flink geospatial aggregation job pre-computes aggregates per city zone (defined as H3 hexagonal grid cells), so dashboard queries don't need to scan raw sensor data.

### Watermarks and Allowed Lateness for Out-of-Order Events
Sensors with intermittent connectivity send buffered data out of order. Flink's event-time processing with watermarks handles this: watermark = max_event_time - allowed_lateness. Events arriving after the watermark are either (a) included in a late-firing window update, or (b) sent to a side output for separate processing. For smart city data, we allow 5 minutes of lateness — a sensor that reconnects after a 5-minute outage will have its data correctly windowed. Beyond 5 minutes, data goes to a "late arrivals" side output that updates historical aggregates in batch.

## Pipeline Stages

1. **Edge/IoT Layer:** 500K sensors publish readings via MQTT to the nearest edge gateway. Edge gateways perform local validation, deduplication, and pre-aggregation, then forward to the central MQTT broker
2. **Ingestion Layer:** Central MQTT broker (EMQX cluster) receives from all edge gateways. MQTT-Kafka bridge forwards to `sensor-events` topic. Device Registry authenticates devices and maps sensor_id to metadata (location, type, zone). Schema Registry enforces the unified sensor event schema
3. **Data Cleaning (Flink):** First processing stage: validate physical plausibility (temperature between -50 and +60C, humidity 0-100%), fill missing fields from device registry (location, zone), convert units to standard (all temperatures in Celsius, all distances in meters), assign event-time watermarks
4. **Geospatial Aggregation (Flink):** Assigns each sensor reading to an H3 hex cell (hierarchical geospatial index). Aggregates readings per (zone, sensor_type, 1-minute window): average, min, max, count. Writes to PostGIS for spatial queries and TimescaleDB for time-series queries
5. **Anomaly & Threshold Alerts (Flink):** Monitors predefined thresholds (air quality index > 150, traffic congestion > 90% capacity) and statistical anomalies (reading deviates more than 3 standard deviations from the 7-day rolling average). Fires alerts to the emergency alert system
6. **Cross-Sensor Correlation (Flink):** Joins traffic data with air quality data in the same zone and time window. Detects causal patterns (traffic spike followed by air quality degradation 10-15 minutes later). Powers the city planning analytics dashboard
7. **Batch ML Predictions:** Daily Spark job trains predictive models: traffic congestion forecasting (next 2 hours), energy demand prediction (next 24 hours), air quality forecasting. Predictions written to Redis for real-time serving in the dashboard
8. **Applications:** City operations dashboard (Grafana with PostGIS map overlays), traffic signal management system (consumes real-time traffic aggregates to optimize signal timing), emergency alert system (fires SMS/push alerts for critical threshold breaches), public open-data API (rate-limited, anonymized, delayed by 5 minutes)

## Partitioning Strategy

- **MQTT topics:** `city/{zone_id}/traffic/+`, `city/{zone_id}/air_quality/+` — hierarchical topic structure enables zone-level subscriptions for edge gateways
- **Kafka `sensor-events`:** Partition by `zone_id` — co-locates all sensors in a zone for efficient spatial aggregation in Flink. With 200 city zones and 256 Kafka partitions, each partition handles 1-2 zones
- **TimescaleDB:** Hypertables partitioned by time (1-day chunks) with automatic compression after 7 days. Continuous aggregates pre-compute hourly/daily rollups
- **PostGIS:** Partitioned by zone_id with spatial indices (GIST) on geography columns. Each zone's data fits in one partition for efficient spatial joins
- **S3 Data Lake:** Partitioned by `sensor_type/year/month/day/zone_id` for efficient scanning by both time and space dimensions

## Failure Handling

- **Sensor offline:** MQTT QoS 1 with persistent sessions. When a sensor reconnects, the MQTT broker replays unacknowledged messages. Edge gateway buffers locally (up to 1 hour of data on local disk). When the gateway reconnects, it sends buffered data with original timestamps. Flink's event-time processing and allowed lateness handle the late arrivals correctly
- **Edge gateway failure:** Sensors fall back to publishing directly to the central MQTT broker (higher latency, no pre-aggregation). Other edge gateways in the zone are unaffected. Failed gateway restarts and resumes processing from its local buffer
- **MQTT broker failure:** EMQX cluster with 3+ nodes and automatic failover. MQTT persistent sessions are replicated. Sensors reconnect to another broker node automatically
- **Flink failure:** Checkpoints to S3 every 60 seconds. Watermark-based event-time processing ensures correct aggregation after replay. Side output for late arrivals catches data that missed its window during downtime
- **DLQ:** Sensor events failing validation (impossible readings, unknown sensor_id) go to DLQ. Common causes: faulty sensor hardware, uncalibrated new sensor. Operations team investigates and either recalibrates the sensor or updates the device registry

## Key Trade-offs

- **Edge aggregation vs raw data:** Pre-aggregating at the edge reduces bandwidth and central processing load by 5-10x, but loses raw granularity. Once 1-second readings are averaged into 10-second windows at the edge, the raw data is gone. For most analytics, 10-second resolution is sufficient. For forensic analysis (reconstructing a traffic incident second-by-second), raw data would be needed. Compromise: edge gateways send both aggregated data (normal path) and raw data for a configurable subset of sensors (forensic path, 10% of sensors)
- **Event-time vs processing-time:** Event-time is essential for correctness (out-of-order sensor data) but adds complexity (watermark management, allowed lateness configuration). For real-time alerting, we also check processing-time — if an alert condition persists for 5 minutes of wall-clock time, fire the alert even if event-time watermarks haven't advanced (which can happen if some sensors go silent)
- **Centralized vs federated processing:** A single central cluster is simpler to operate but requires all data to traverse the WAN. A federated architecture (processing at each district edge) reduces latency and bandwidth but multiplies operational complexity. We use central processing with edge pre-aggregation as a middle ground
- **Open data access vs privacy:** Traffic camera data (ANPR) and smart meter data contain personally identifiable information. The public API must anonymize and aggregate data. Trade-off: finer granularity is more useful for researchers but more privacy-invasive. We expose 15-minute aggregates at the zone level (no individual sensor readings) in the public API

## What Fails First

**MQTT broker throughput** is the first bottleneck. At 3M messages/sec peak, even a clustered MQTT broker struggles with the connection management overhead (500K persistent sessions, each with QoS state). Each MQTT PUBLISH requires a PUBACK response, creating 6M message operations/sec. The broker's TCP connection handling becomes the bottleneck before CPU or memory.

Solutions: (1) Increase edge aggregation aggressively — reduce the number of messages reaching the central broker by pre-aggregating more at the edge, (2) Use MQTT QoS 0 (fire-and-forget) for non-critical sensor types (weather stations, noise monitors) and reserve QoS 1 for critical sensors (air quality, emergency), (3) Shard the MQTT broker by zone — each zone's sensors connect to a dedicated MQTT broker instance, eliminating the single-cluster bottleneck.

Second failure: **TimescaleDB write amplification.** At 1M inserts/sec, TimescaleDB's hypertable chunking + indexing creates significant write amplification. The WAL grows rapidly, replication lags, and query performance degrades as the database spends more time on writes than reads. Solution: batch inserts (accumulate 1 second of data in Flink, write as a single batch INSERT), and use continuous aggregates to pre-compute common queries so the dashboard doesn't hit raw data.

## v1 vs v2

### v1 — Ship in 6 weeks
- Single sensor type: traffic sensors only
- Direct MQTT to Kafka bridge (no edge gateways)
- Single Flink job: cleaning + basic aggregation (5-minute windows)
- TimescaleDB for storage, Grafana for dashboard
- Simple threshold alerting (static thresholds, no anomaly detection)
- No geospatial aggregation (sensors identified by lat/long, no zone mapping)
- No public API

### v2 — Full smart city platform
- All sensor types with unified schema and device registry
- Edge gateway network with local pre-aggregation and buffering
- Multi-stage Flink pipeline: cleaning, geospatial aggregation, anomaly detection, cross-sensor correlation
- PostGIS + TimescaleDB + S3 tiered storage with 10-year retention
- ML-based predictions (traffic forecasting, air quality forecasting)
- City operations dashboard with real-time geospatial map overlays
- Traffic signal management integration (closed-loop optimization)
- Emergency alert system with SMS/push notifications
- Public open-data API with rate limiting, anonymization, and delayed access
- Edge-to-cloud failover with automatic reconnection and data recovery
