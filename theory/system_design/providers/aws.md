# AWS (Amazon Web Services) — Service Mapping

## Compute
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Virtual machines | **EC2** (Elastic Compute Cloud) | On-demand, reserved, spot instances |
| Containers (managed) | **ECS** (Elastic Container Service) | AWS-native container orchestration |
| Kubernetes (managed) | **EKS** (Elastic Kubernetes Service) | Managed K8s control plane |
| Serverless containers | **Fargate** | No server/cluster management for ECS/EKS |
| Serverless functions | **Lambda** | Event-driven, pay-per-invocation, 15 min max |
| Batch processing | **AWS Batch** | Managed batch computing jobs |

## Storage
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Object storage | **S3** (Simple Storage Service) | 11 nines durability; Standard, IA, Glacier tiers |
| Block storage | **EBS** (Elastic Block Store) | Attached to EC2, SSD/HDD options |
| File storage (NFS) | **EFS** (Elastic File System) | Shared filesystem for multiple EC2 instances |
| Archival storage | **S3 Glacier / Glacier Deep Archive** | Minutes to hours retrieval |

## Databases
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Managed SQL (MySQL/PostgreSQL) | **RDS** | Multi-AZ, read replicas, automated backups |
| Cloud-native SQL | **Aurora** | MySQL/PostgreSQL compatible, 5x throughput, storage auto-scales |
| Serverless distributed SQL (multi-region) | **Aurora DSQL** (GA 2025) | Active-active multi-region, strongly consistent, PostgreSQL-compatible. AWS's answer to Cloud Spanner. |
| Managed NoSQL (key-value/document) | **DynamoDB** | Single-digit ms latency, auto-scaling, global tables |
| Managed Redis/Memcached/Valkey | **ElastiCache** | Redis OSS, Valkey, or Memcached clusters (Valkey added 2024) |
| Managed Elasticsearch | **OpenSearch Service** | Fork of Elasticsearch, includes serverless option and vector search |
| Graph database | **Neptune** | Property graph + RDF; Neptune Analytics for graph analytics |
| Time-series database | **Timestream** (LiveAnalytics + InfluxDB) | Serverless TSDB; Timestream for InfluxDB added 2024 |
| Data warehouse | **Redshift** | Columnar, petabyte-scale analytics, Serverless option |
| Managed Cassandra | **Keyspaces** | Serverless Cassandra-compatible |
| In-memory database | **MemoryDB** | Durable, Redis/Valkey-compatible, multi-AZ |
| Iceberg tables on S3 | **S3 Tables** (GA 2024) | Fully managed Apache Iceberg tables with automatic compaction |

## Networking
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Virtual network | **VPC** (Virtual Private Cloud) | Isolated network with subnets, route tables, NACLs |
| DNS | **Route 53** | DNS + health checks + traffic routing (geo, latency, weighted, failover) |
| CDN | **CloudFront** | Global CDN, Lambda@Edge for edge compute |
| L4 Load Balancer | **NLB** (Network Load Balancer) | Millions of requests/sec, ultra-low latency |
| L7 Load Balancer | **ALB** (Application Load Balancer) | HTTP/HTTPS routing, path/host-based, WebSocket |
| Classic Load Balancer | **CLB** | Legacy, avoid for new designs |
| API Gateway | **API Gateway** | REST, HTTP, WebSocket APIs; throttling, auth, caching |
| Service mesh | **App Mesh** | Envoy-based service mesh |
| Private connectivity | **PrivateLink** | Access services without public internet |

## Messaging & Streaming
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Message queue | **SQS** (Simple Queue Service) | Standard (at-least-once, best-effort ordering) or FIFO (exactly-once, strict ordering) |
| Pub/Sub | **SNS** (Simple Notification Service) | Fan-out to SQS, Lambda, HTTP, email, SMS |
| Event streaming (Kafka) | **MSK** (Managed Streaming for Apache Kafka) | Fully managed Kafka |
| Serverless event streaming | **Kinesis Data Streams** | Real-time streaming, 1-365 day retention |
| Event bus | **EventBridge** | Serverless event bus, rule-based routing |
| Workflow orchestration | **Step Functions** | State machine for coordinating Lambda/services |

## Security & Identity
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Identity management | **IAM** | Users, roles, policies |
| Secrets management | **Secrets Manager** | Rotate, manage, retrieve secrets |
| Parameter store | **Systems Manager Parameter Store** | Config and secrets (simpler than Secrets Manager) |
| Certificate management | **ACM** (AWS Certificate Manager) | Free SSL/TLS certificates |
| DDoS protection | **Shield** | Standard (free) and Advanced |
| WAF | **WAF** | Web Application Firewall |
| Encryption / Key management | **KMS** (Key Management Service) | Managed encryption keys |

## Monitoring & Observability
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Metrics & monitoring | **CloudWatch** | Metrics, alarms, dashboards |
| Logging | **CloudWatch Logs** | Centralized log aggregation |
| Distributed tracing | **X-Ray** | Trace requests across services |
| Audit logging | **CloudTrail** | Who did what, when (API call logging) |

## Data & Analytics
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Data lake query engine | **Athena** | Serverless SQL over S3 (Presto/Trino), supports Iceberg |
| ETL | **Glue** | Managed Spark ETL + data catalog |
| Real-time analytics | **Managed Service for Apache Flink** (formerly Kinesis Data Analytics) | Flink over streaming data |
| Data lake formation | **Lake Formation** | Centralized data lake governance |
| BI / visualization | **QuickSight** | Managed BI; includes Q (NLQ) and Generative BI features |
| Unified analytics platform | **SageMaker Unified Studio / Lakehouse** (2024) | Merges Glue, EMR, Redshift, Athena, SageMaker into one workbench |

## AI / ML
| Concept | AWS Service | Notes |
|---------|------------|-------|
| Foundation models (GenAI) | **Amazon Bedrock** | Managed access to Claude, Llama, Titan, Mistral, etc. Agents, Knowledge Bases, Guardrails. |
| AI coding assistant | **Amazon Q Developer** (formerly CodeWhisperer) | IDE + CLI assistant, /dev agent, code transformation |
| Business AI assistant | **Amazon Q Business** | RAG over enterprise data |
| ML platform (end-to-end) | **SageMaker AI** | Build, train, tune, deploy models; SageMaker Studio |
| Vector search | **OpenSearch Serverless (vector)**, **Aurora pgvector**, **MemoryDB vector** | Multiple vector options |
| Speech / Vision / NLP | **Polly, Transcribe, Rekognition, Comprehend, Textract** | Purpose-built AI services |

## Key AWS Concepts for Interviews
- **Regions & Availability Zones**: A region has 2-6 AZs. Each AZ is an isolated data center. Design for multi-AZ (HA) and multi-region (DR).
- **Auto Scaling Groups (ASG)**: Automatically scale EC2 instances based on metrics/schedule.
- **Elastic IP**: Static public IP address that can be remapped between instances.
- **S3 + CloudFront**: The go-to pattern for serving static content globally.
- **SQS + Lambda**: The go-to serverless async processing pattern.
- **SNS + SQS**: Fan-out pattern — SNS publishes to multiple SQS queues.
- **DynamoDB Accelerator (DAX)**: In-memory cache for DynamoDB (microsecond reads).
- **Global Accelerator**: Anycast-based global traffic routing to the nearest region.
- **Aurora Global Database**: Cross-region replication with <1 second lag.
- **Aurora DSQL vs Spanner**: Aurora DSQL is AWS's multi-region strongly consistent SQL (PostgreSQL wire-compat), competitor to Google Spanner.
- **Bedrock + Knowledge Bases + Agents**: RAG + tool-use pattern for GenAI apps.
- **Deprecated/sunset**: SimpleDB (legacy, not available to new customers), CloudSearch (legacy — use OpenSearch), Classic Load Balancer (avoid), Cloud9 (discontinued 2024), CodeCommit (closed to new customers 2024).

## Common Architecture Patterns on AWS
```
Route 53 → CloudFront (CDN) → ALB → ECS/EKS (app) → Aurora (SQL) + DynamoDB (NoSQL) + ElastiCache (cache)
                                                    → SQS → Lambda (async processing)
                                                    → S3 (object storage)
                                                    → Kinesis → Redshift (analytics)
```
