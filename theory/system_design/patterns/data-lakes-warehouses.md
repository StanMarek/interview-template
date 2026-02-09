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
Combines the best of both: raw storage of a data lake with the structured querying and ACID transactions of a warehouse.
- **Examples**: Databricks (Delta Lake), Apache Iceberg, Apache Hudi

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
