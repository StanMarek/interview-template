# Microsoft Azure — Service Mapping

## Compute
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Virtual machines | **Azure VMs** | Various series (general, compute, memory, GPU optimized) |
| Containers (managed) | **Azure Container Instances (ACI)** | Serverless containers |
| Kubernetes (managed) | **AKS** (Azure Kubernetes Service) | Managed K8s, tight Azure integration |
| Serverless functions | **Azure Functions** | Event-driven, consumption or premium plan |
| Batch processing | **Azure Batch** | Large-scale parallel and HPC workloads |
| App hosting (PaaS) | **App Service** | Managed web app hosting (.NET, Java, Node, Python) |

## Storage
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Object storage | **Blob Storage** | Hot, Cool, Cold, Archive tiers |
| Block storage | **Managed Disks** | Attached to VMs; Ultra, Premium SSD, Standard SSD/HDD |
| File storage (SMB/NFS) | **Azure Files** | SMB and NFS shares, mountable from VMs |
| Data Lake storage | **ADLS Gen2** (Azure Data Lake Storage) | Hadoop-compatible, hierarchical namespace over Blob Storage |
| Archival | **Blob Storage Archive tier** | Cheapest storage, hours to rehydrate |

## Databases
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Managed SQL Server | **Azure SQL Database** | Managed SQL Server, hyperscale, serverless options |
| Managed MySQL/PostgreSQL | **Azure Database for MySQL/PostgreSQL** | Flexible Server deployment option |
| Cloud-native distributed SQL | **Cosmos DB (SQL API)** | Multi-model, global distribution, tunable consistency |
| Managed NoSQL (multi-model) | **Cosmos DB** | Document, graph, table, column-family APIs; 5 consistency levels |
| Managed Redis | **Azure Cache for Redis** | Enterprise tiers available (Redis Enterprise) |
| Search | **Azure AI Search** (formerly Cognitive Search) | Full-text search, AI enrichment, vector search |
| Data warehouse | **Azure Synapse Analytics** | Unified analytics (SQL pools + Spark + Pipelines) |
| Graph database | **Cosmos DB (Gremlin API)** | Graph API on Cosmos DB |
| Time-series | **Azure Data Explorer (Kusto)** | Fast analytics on time-series and log data |

## Networking
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Virtual network | **VNet** (Virtual Network) | Subnets, NSGs, peering |
| DNS | **Azure DNS** | Public and private DNS zones |
| CDN | **Azure CDN** (or Azure Front Door) | Global CDN; Front Door adds L7 LB + WAF |
| L4 Load Balancer | **Azure Load Balancer** | Regional, high performance |
| L7 Load Balancer | **Application Gateway** | HTTP routing, SSL termination, WAF |
| Global L7 LB + CDN | **Azure Front Door** | Global load balancing, CDN, WAF, SSL offload |
| API Gateway | **Azure API Management (APIM)** | Full API lifecycle: gateway, developer portal, analytics |
| Service mesh | **Open Service Mesh** or **Istio on AKS** | AKS add-ons |
| Private connectivity | **Private Link** | Access services over private endpoint |

## Messaging & Streaming
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Message queue | **Azure Queue Storage** | Simple, cheap, 7-day retention |
| Enterprise message broker | **Service Bus** | Queues + Topics, sessions, transactions, DLQ, FIFO |
| Event streaming (Kafka-compatible) | **Event Hubs** | Kafka protocol support, millions of events/sec |
| Event routing | **Event Grid** | Reactive event-driven, push-based, serverless |
| Workflow orchestration | **Logic Apps** | Visual workflow designer; **Durable Functions** for code-first |

## Security & Identity
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Identity management | **Entra ID** (formerly Azure AD) | SSO, MFA, RBAC, B2B/B2C |
| Secrets management | **Key Vault** | Secrets, keys, certificates |
| DDoS protection | **Azure DDoS Protection** | Standard plan for enhanced protection |
| WAF | **WAF** (on Application Gateway or Front Door) | OWASP rule sets |
| Encryption keys | **Key Vault (Keys)** | HSM-backed keys |

## Monitoring & Observability
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Metrics & monitoring | **Azure Monitor** | Metrics, alerts, autoscale |
| Logging | **Log Analytics (Azure Monitor Logs)** | KQL (Kusto Query Language) for log queries |
| Distributed tracing | **Application Insights** | APM, request tracking, dependency mapping |
| Audit logging | **Activity Log** | Control plane operations audit |

## Data & Analytics
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Unified analytics | **Synapse Analytics** | SQL + Spark + Pipelines in one service |
| ETL / Data integration | **Azure Data Factory** | Managed ETL/ELT pipelines |
| Real-time analytics | **Stream Analytics** | SQL over streaming data |
| BI / visualization | **Power BI** | Industry-leading BI tool |
| Databricks | **Azure Databricks** | Managed Spark + Delta Lake |

## Cosmos DB — 5 Consistency Levels
This is unique to Azure and frequently asked about:

| Level | Description | Latency | Use Case |
|-------|------------|---------|----------|
| **Strong** | Linearizable reads | Highest | Financial transactions |
| **Bounded Staleness** | Reads lag by at most K versions or T time | High | Score keeping |
| **Session** | Read-your-writes within a session | Medium | User profiles (most popular default) |
| **Consistent Prefix** | Reads never see out-of-order writes | Low | Social media updates |
| **Eventual** | No ordering guarantees | Lowest | Vote counts, likes |

## Key Azure Concepts for Interviews
- **Regions & Availability Zones**: Similar to AWS. Paired regions for geo-redundancy.
- **Resource Groups**: Logical container for related resources (billing, access control).
- **Azure Front Door**: Combines global LB + CDN + WAF — often the first entry point.
- **Cosmos DB**: Azure's flagship globally-distributed database. Know the 5 consistency levels.
- **Service Bus + Azure Functions**: Go-to pattern for async processing.
- **Event Grid + Functions**: Reactive event-driven architecture.
- **ADLS Gen2 + Synapse**: The Azure data lakehouse pattern.

## Common Architecture Pattern on Azure
```
Azure Front Door (CDN + WAF + Global LB) → Application Gateway → AKS (app)
    → Azure SQL / Cosmos DB (data) + Azure Cache for Redis (cache)
    → Service Bus → Azure Functions (async processing)
    → Blob Storage (files)
    → Event Hubs → Synapse Analytics (analytics)
```
