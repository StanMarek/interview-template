# Time-Series Databases

## What They Are
Databases optimized for storing and querying time-stamped data points. They handle massive write throughput, time-range queries, and downsampling/aggregation efficiently.

## Characteristics
- **Write-heavy**: Constant stream of new data points (metrics, events, IoT sensors)
- **Append-mostly**: Data is rarely updated or deleted; mostly inserts
- **Time-ordered queries**: "Give me CPU usage for the last hour" (range scans)
- **Aggregation**: Roll-ups (avg, max, min, percentiles) over time windows
- **Downsampling**: Store high-resolution recent data, compress older data (1s resolution for last day, 1min for last month, 1hr for last year)
- **TTL/Retention policies**: Automatically delete data older than N days

## Use Cases
- **Infrastructure monitoring**: CPU, memory, disk, network metrics (Prometheus, Datadog)
- **Application metrics**: Request latency, error rates, throughput
- **IoT / sensor data**: Temperature, pressure, location readings
- **Financial data**: Stock prices, trading volumes (tick data)
- **Real-time analytics**: Event counts, session tracking

## Implementations

| Database | Notes |
|----------|-------|
| **InfluxDB** | Purpose-built TSDB, SQL-like query language (Flux/InfluxQL) |
| **Prometheus** | Pull-based metrics collection, PromQL, Kubernetes standard |
| **TimescaleDB** | PostgreSQL extension — full SQL with time-series optimizations |
| **OpenTSDB** | Built on HBase, good for Hadoop environments |
| **QuestDB** | High-performance, SQL-compatible, columnar storage |
| **Amazon Timestream** | Managed, serverless TSDB |
| **ClickHouse** | Columnar OLAP database, excellent for time-series analytics |
| **VictoriaMetrics** | Prometheus-compatible, long-term storage |

## Storage Model
Most TSDBs use **columnar storage** and **compression**: since values in the same column (e.g., "temperature") are similar, they compress very well (10-20x compression ratios). Combined with LSM trees or append-only logs for write performance.

## Possible Interview Questions
1. "How would you store and query metrics for 10,000 servers?"
2. "Design a monitoring system for a microservices architecture."
3. "How do time-series databases achieve high write throughput?"
4. "How would you handle querying data that spans hot (recent) and cold (archived) storage?"
