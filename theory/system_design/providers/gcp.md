# GCP (Google Cloud Platform) — Service Mapping

## Compute
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Virtual machines | **Compute Engine** | Predefined and custom machine types, preemptible/spot VMs |
| Containers (managed) | **Cloud Run** | Serverless containers, auto-scaling to zero, pay-per-request |
| Kubernetes (managed) | **GKE** (Google Kubernetes Engine) | Most mature managed K8s (Google invented Kubernetes) |
| Serverless functions | **Cloud Functions** | Event-driven, Node/Python/Go/Java |
| Batch processing | **Batch** | Managed batch jobs on Compute Engine |
| App hosting (PaaS) | **App Engine** | Fully managed PaaS (Standard or Flexible) |

## Storage
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Object storage | **Cloud Storage (GCS)** | Standard, Nearline, Coldline, Archive classes |
| Block storage | **Persistent Disk** | SSD or HDD, attached to VMs, regional option |
| File storage (NFS) | **Filestore** | Managed NFS |
| Archival | **Cloud Storage Archive** | Cheapest, 365-day min storage |

## Databases
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Managed MySQL/PostgreSQL/SQL Server | **Cloud SQL** | Automated backups, replicas, HA; Enterprise Plus edition |
| Cloud-native distributed SQL | **Cloud Spanner** | Globally distributed, strongly consistent, horizontally scalable SQL. Uses TrueTime. Now supports PostgreSQL dialect, vector search, full-text search, and Spanner Graph (2024). |
| Managed PostgreSQL (advanced) | **AlloyDB** | PostgreSQL-compatible, 4x faster OLTP, 100x faster analytics vs standard PostgreSQL; AlloyDB Omni runs anywhere; AlloyDB AI for vector/embeddings |
| Managed NoSQL (document) | **Firestore** | Serverless, real-time sync; Firestore with MongoDB compatibility (2024/2025) |
| Managed wide-column | **Cloud Bigtable** | HBase-compatible, massive scale, low latency. Powers Gmail, Maps, Search. SQL support added. |
| Managed Redis/Memcached/Valkey | **Memorystore** | Memorystore for Redis Cluster, Memcached, and Valkey (added 2024) |
| Data warehouse | **BigQuery** | Serverless, petabyte-scale, SQL, separated storage+compute. BigLake, BigQuery ML, vector search. |
| Search | **Vertex AI Search** | Enterprise search, recommendations, grounded GenAI answers |
| Vector database | **AlloyDB AI**, **Cloud SQL pgvector**, **Spanner vector**, **BigQuery vector**, **Vertex AI Vector Search** | Vector is now native across most data services |

## Networking
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Virtual network | **VPC** | Global VPC (subnets are regional, VPC is global — unique to GCP) |
| DNS | **Cloud DNS** | 100% uptime SLA |
| CDN | **Cloud CDN** | Integrated with HTTP(S) LB |
| L4 Load Balancer | **Network Load Balancer** | TCP/UDP, regional or global passthrough |
| L7 Load Balancer | **HTTP(S) Load Balancer** | Global, single Anycast IP, auto-scaling, URL maps |
| API Gateway | **API Gateway** or **Apigee** | Simple serverless or full enterprise API lifecycle |
| Service mesh | **Anthos Service Mesh** | Managed Istio |
| Private connectivity | **Private Service Connect** | Private access to Google services |

## Messaging & Streaming
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Pub/Sub (queue + fan-out) | **Pub/Sub** | Serverless, at-least-once, global. Unifies queuing and pub/sub. |
| Event streaming (Kafka) | **Managed Service for Apache Kafka** | Fully managed Kafka |
| Stream processing | **Dataflow** | Managed Apache Beam (batch + stream unified) |
| Workflow orchestration | **Workflows** | Serverless workflow orchestration |
| Task queue | **Cloud Tasks** | Async task execution with rate control |

## Security & Identity
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Identity management | **Cloud IAM** | Roles, service accounts, organization policies |
| Secrets management | **Secret Manager** | Versioned secrets with IAM access control |
| DDoS + WAF | **Cloud Armor** | DDoS protection + WAF, integrates with HTTP(S) LB |
| Certificate management | **Certificate Manager** | Managed SSL/TLS certificates |
| Encryption keys | **Cloud KMS** | Managed or customer-managed encryption keys |

## Monitoring & Observability
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Metrics & monitoring | **Cloud Monitoring** | Metrics, dashboards, alerting |
| Logging | **Cloud Logging** | Centralized logging, log-based metrics |
| Distributed tracing | **Cloud Trace** | Latency analysis |
| Error tracking | **Error Reporting** | Automatic error grouping |
| Audit logging | **Audit Logs** | Admin and data access audit trails |

## Data & Analytics
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Data warehouse | **BigQuery** | Serverless SQL, ML built-in (BQML), BI Engine, Gemini-in-BigQuery assistant |
| Cross-cloud analytics | **BigQuery Omni** | Run BigQuery over data in AWS S3 and Azure Blob without moving it |
| Lakehouse table format | **BigLake** | Unifies BigQuery + open formats (Iceberg, Delta, Hudi) over GCS; BigLake Managed Tables for Iceberg |
| ETL / data integration | **Dataflow** (Apache Beam) | Unified batch + streaming ETL |
| Data orchestration | **Cloud Composer** (Apache Airflow) | Managed Airflow for DAGs |
| Spark / Hadoop | **Dataproc** / **Dataproc Serverless** | Managed Spark/Hadoop; serverless Spark option |
| BI / visualization | **Looker** / **Looker Studio** | Enterprise BI (Looker) + free self-serve (Looker Studio) |

## AI / ML
| Concept | GCP Service | Notes |
|---------|------------|-------|
| Unified ML / GenAI platform | **Vertex AI** | End-to-end: training, tuning, deployment, Model Garden, Agent Builder, Agent Engine |
| Foundation models | **Vertex AI — Gemini** (1.5/2.0/2.5 families), **Imagen**, **Veo**, partner models (Claude, Llama, Mistral) via Model Garden | Gemini is Google's flagship multimodal LLM |
| Agent development | **Vertex AI Agent Builder** / **Agent Engine** / **ADK** (Agent Development Kit) | Build, deploy, orchestrate agents; A2A protocol |
| AI coding assistant | **Gemini Code Assist** (formerly Duet AI for Developers) | IDE, Cloud Shell, Cloud Workstations integration |
| Vector search (ANN) | **Vertex AI Vector Search** (formerly Matching Engine) | Billion-scale ANN search |
| Speech / Vision / NLP | **Speech-to-Text, Text-to-Speech, Vision AI, Translation, Document AI, Natural Language** | Pre-trained APIs |
| Notebooks | **Colab Enterprise** / **Vertex AI Workbench** | Managed Jupyter with GPU/TPU |

## GCP Differentiators for Interviews

### Cloud Spanner
Globally distributed, strongly consistent, horizontally scalable SQL. Uses TrueTime (atomic clocks + GPS) for global consistency without sacrificing performance.

### BigQuery
Serverless data warehouse. No infrastructure to manage. SQL interface. Processes petabytes in seconds. Streaming inserts for real-time data. Slot-based or pay-per-query pricing.

### Global HTTP(S) Load Balancer
Single Anycast IP routes globally. Automatically distributes traffic to nearest healthy backend. Simpler than AWS multi-region setup.

### Global VPC
VPC is global (not regional like AWS). Simplifies multi-region networking.

### Cloud Pub/Sub
Unifies message queuing and pub/sub. Serverless, no partitions to manage. Automatic scaling.

## Key GCP Concepts for Interviews
- **Regions & Zones**: Similar to AWS AZs. Global VPC spans all regions.
- **Cloud Run**: Easiest way to deploy containers. Scales to zero. Now supports GPU and jobs.
- **Pub/Sub + Dataflow**: The streaming analytics pattern.
- **GCS + BigQuery**: Data lakehouse — store raw in GCS, query with BigQuery.
- **Pub/Sub + Cloud Functions** (now **Cloud Run functions**): Go-to serverless async processing. Cloud Functions was rebranded into Cloud Run functions in 2024.
- **Vertex AI + BigQuery + AlloyDB AI**: Canonical RAG/GenAI stack on GCP.
- **Spanner vs AlloyDB**: Spanner for global strong consistency at horizontal scale; AlloyDB for single-region PostgreSQL that's fastest for OLTP + in-database analytics.

## Common Architecture Pattern on GCP
```
Cloud DNS → Global HTTP(S) LB (+ Cloud CDN + Cloud Armor) → GKE / Cloud Run (app)
    → Cloud SQL / Spanner (SQL) + Firestore (NoSQL) + Memorystore (cache)
    → Pub/Sub → Cloud Functions / Dataflow (async processing)
    → Cloud Storage (files)
    → Pub/Sub → Dataflow → BigQuery (analytics)
```

## AWS vs GCP vs Azure Quick Cross-Reference

| Concept | AWS | GCP | Azure |
|---------|-----|-----|-------|
| Object Storage | S3 | Cloud Storage | Blob Storage |
| Serverless Functions | Lambda | Cloud Run functions | Azure Functions |
| Managed K8s | EKS | GKE | AKS |
| Serverless Containers | Fargate / App Runner | Cloud Run | Container Apps / Container Instances |
| Managed SQL (regional) | RDS / Aurora | Cloud SQL / AlloyDB | Azure SQL Database |
| Multi-region distributed SQL (globally consistent) | **Aurora DSQL** | **Cloud Spanner** | **—** (no true globally-distributed linearizable SQL equivalent; closest is Cosmos DB for NoSQL with strong consistency, not SQL) |
| Horizontally-sharded Postgres (not globally consistent) | Aurora Limitless Database | AlloyDB (read scaling) | **Cosmos DB for PostgreSQL** (Citus-based sharding) |
| NoSQL (Document/KV) | DynamoDB | Firestore | Cosmos DB for NoSQL |
| Wide-column NoSQL | Keyspaces | Bigtable | Cosmos DB for Cassandra |
| Cache | ElastiCache / MemoryDB | Memorystore | Azure Managed Redis |
| Message Queue | SQS | Pub/Sub / Cloud Tasks | Service Bus / Queue Storage |
| Event Streaming | Kinesis / MSK | Pub/Sub / Managed Kafka | Event Hubs |
| Event Bus | EventBridge | Eventarc | Event Grid |
| Data Warehouse | Redshift | BigQuery | Fabric Warehouse / Synapse |
| Lakehouse / Iceberg | S3 Tables + Athena | BigLake | Fabric OneLake |
| CDN | CloudFront | Cloud CDN | Front Door / Azure CDN |
| DNS | Route 53 | Cloud DNS | Azure DNS |
| L7 LB | ALB | Global HTTP(S) LB | Application Gateway / Front Door |
| API Gateway | API Gateway | API Gateway / Apigee | API Management |
| Search | OpenSearch | Vertex AI Search | Azure AI Search |
| Secrets | Secrets Manager | Secret Manager | Key Vault |
| Identity | IAM | Cloud IAM | Entra ID (formerly Azure AD) |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor |
| GenAI / Foundation models | Bedrock | Vertex AI (Gemini + Model Garden) | Azure OpenAI / AI Foundry |
| AI coding assistant | Q Developer | Gemini Code Assist | GitHub Copilot |
| ML platform | SageMaker AI | Vertex AI | Azure Machine Learning |
| Vector search | OpenSearch / Aurora pgvector / MemoryDB | Vertex AI Vector Search / AlloyDB AI | AI Search / Cosmos DB for MongoDB vCore |
