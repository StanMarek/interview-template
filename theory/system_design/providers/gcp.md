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
| Managed MySQL/PostgreSQL/SQL Server | **Cloud SQL** | Automated backups, replicas, HA |
| Cloud-native distributed SQL | **Cloud Spanner** | Globally distributed, strongly consistent, horizontally scalable SQL. Google's crown jewel. |
| Managed PostgreSQL (advanced) | **AlloyDB** | PostgreSQL-compatible, 4x faster than standard PostgreSQL |
| Managed NoSQL (document) | **Firestore** | Serverless, real-time sync, mobile-first |
| Managed wide-column | **Cloud Bigtable** | HBase-compatible, massive scale, low latency. Powers Gmail, Maps, Search. |
| Managed Redis/Memcached | **Memorystore** | Redis or Memcached clusters |
| Data warehouse | **BigQuery** | Serverless, petabyte-scale, SQL, separated storage+compute. Industry-leading analytics. |
| Search | **Vertex AI Search** | Enterprise search and recommendations |

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
| Data warehouse | **BigQuery** | Serverless SQL, ML built-in (BQML), BI Engine |
| ETL / data integration | **Dataflow** (Apache Beam) | Unified batch + streaming ETL |
| Data orchestration | **Cloud Composer** (Apache Airflow) | Managed Airflow for DAGs |
| Spark / Hadoop | **Dataproc** | Managed Spark/Hadoop clusters |
| BI / visualization | **Looker** | Enterprise BI platform |

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
- **Cloud Run**: Easiest way to deploy containers. Scales to zero.
- **Pub/Sub + Dataflow**: The streaming analytics pattern.
- **GCS + BigQuery**: Data lakehouse — store raw in GCS, query with BigQuery.
- **Pub/Sub + Cloud Functions**: Go-to serverless async processing pattern.

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
| Serverless Functions | Lambda | Cloud Functions | Azure Functions |
| Managed K8s | EKS | GKE | AKS |
| Serverless Containers | Fargate | Cloud Run | Container Instances |
| Managed SQL | RDS / Aurora | Cloud SQL / Spanner | Azure SQL / Cosmos DB |
| NoSQL (Document/KV) | DynamoDB | Firestore | Cosmos DB |
| Cache | ElastiCache | Memorystore | Azure Cache for Redis |
| Message Queue | SQS | Pub/Sub | Service Bus |
| Event Streaming | Kinesis / MSK | Pub/Sub / Managed Kafka | Event Hubs |
| Data Warehouse | Redshift | BigQuery | Synapse Analytics |
| CDN | CloudFront | Cloud CDN | Azure CDN / Front Door |
| DNS | Route 53 | Cloud DNS | Azure DNS |
| L7 LB | ALB | HTTP(S) LB | Application Gateway |
| Search | OpenSearch | Vertex AI Search | Azure AI Search |
| Secrets | Secrets Manager | Secret Manager | Key Vault |
| Monitoring | CloudWatch | Cloud Monitoring | Azure Monitor |
