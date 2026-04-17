# Data Lakes & Data Warehouses

## Data Warehouse
Structured, schema-on-write storage optimized for analytical queries (OLAP). Data is cleaned, transformed, and loaded (ETL) before storage.
- **Schema**: Star/Snowflake schemas, predefined
- **Data**: Structured, curated, business-ready
- **Users**: Business analysts, BI tools
- **Query engine**: SQL, columnar storage
- **Examples**: Snowflake, BigQuery, Redshift, Synapse

## Data Lake
Store raw data in any format (structured, semi-structured, unstructured) at any scale. Schema-on-read — you define the structure when you query.
- **Schema**: Applied at read time
- **Data**: Raw, unprocessed, any format (JSON, Parquet, images, logs)
- **Users**: Data scientists, ML engineers
- **Storage**: Cheap object storage (S3, GCS, ADLS)
- **Examples**: S3 + Athena, HDFS + Spark, Delta Lake

## Data Lakehouse
**Lakehouse** is an architecture pattern combining data lake storage with warehouse-grade ACID semantics. Enabled by open table formats: **Delta Lake** (Databricks origin), **Apache Iceberg** (Netflix origin, now Linux Foundation), **Apache Hudi** (Uber origin). These provide ACID transactions, schema evolution, time travel over parquet files in S3/GCS/ADLS.

Delta Lake, Iceberg, and Hudi are **table formats**, not lakehouses themselves — a lakehouse is the architecture built on top of them.

### Medallion Architecture
Bronze (raw ingest) → Silver (cleaned/conformed) → Gold (business-ready/aggregated). Common Databricks convention; applies to any lakehouse.

## Warehouse Architecture Comparison
- **Snowflake** — separates storage/compute, multi-cluster shared data.
- **BigQuery** — serverless, Dremel-based columnar, tree execution.
- **Redshift** — MPP shared-nothing (RA3 instances separate storage via Managed Storage).

## ETL vs ELT
- **ETL** (Extract, Transform, Load): Transform before loading into warehouse. Traditional approach.
- **ELT** (Extract, Load, Transform): Load raw data first, transform inside the warehouse/lake. Modern approach enabled by cheap storage and powerful compute.

## OLTP vs OLAP

| Feature | OLTP | OLAP |
|---------|------|------|
| Purpose | Transaction processing | Analytical queries |
| Queries | Short, simple (INSERT, UPDATE) | Complex aggregations (GROUP BY, JOIN) |
| Data model | Normalized (3NF) | Denormalized (star/snowflake) |
| Latency | Milliseconds | Seconds to minutes |
| Examples | MySQL, PostgreSQL | BigQuery, Redshift |

## Possible Interview Questions
1. "Where would you store analytics data for a high-traffic application?"
2. "Explain the difference between OLTP and OLAP."
3. "When would you choose a data lake over a data warehouse?"
4. "How would you design a real-time analytics pipeline?"
