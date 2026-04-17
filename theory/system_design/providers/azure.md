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
| Distributed/horizontally-scaled PostgreSQL | **Cosmos DB for PostgreSQL** (formerly Hyperscale/Citus) | **Separate service from the Cosmos DB NoSQL engine** — Citus-based sharded Postgres; shares the "Cosmos DB" brand but is not one of the Cosmos DB APIs below |
| Cloud-native NoSQL (global distribution) | **Cosmos DB for NoSQL** (renamed from SQL API) | Multi-model, global distribution, 5 tunable consistency levels |
| Multi-API NoSQL (single engine) | **Cosmos DB** | APIs on the NoSQL engine: NoSQL, MongoDB (incl. vCore), Cassandra, Gremlin, Table. Cosmos DB for PostgreSQL is branded Cosmos DB but is a separate Citus-based product. |
| Vector database | **Cosmos DB for MongoDB vCore** / **Azure AI Search (vector)** | Native vector search for RAG |
| Managed Redis | **Azure Managed Redis** (GA 2025) / **Azure Cache for Redis** | New Managed Redis uses Redis Enterprise tech; legacy Cache for Redis still available |
| Search | **Azure AI Search** (formerly Cognitive Search) | Full-text, semantic ranking, vector search, integrated vectorization |
| Data warehouse | **Microsoft Fabric — Warehouse / Synapse Analytics** | Fabric (GA 2023) unifies Synapse, Data Factory, Power BI, ADLS on OneLake. Synapse still supported but Fabric is the strategic direction. |
| Graph database | **Cosmos DB for Apache Gremlin** | Graph API on Cosmos DB |
| Time-series / log analytics | **Azure Data Explorer (Kusto)** / **Fabric Real-Time Intelligence** | Fast analytics on time-series and log data |

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
| Service mesh | **Istio-based service mesh on AKS** | Managed Istio add-on. Open Service Mesh reached end-of-life **April 30, 2025** (announced retirement in 2024). Migrate to Istio, Linkerd, or Azure Service Mesh (Istio-based add-on for AKS). |
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
| Unified analytics platform (SaaS) | **Microsoft Fabric** (GA 2023) | End-to-end SaaS: Data Factory, Synapse, Power BI, Real-Time Intelligence — all on OneLake (Delta/Parquet open format). Strategic successor to Synapse. |
| Enterprise data warehouse | **Synapse Analytics** / **Fabric Warehouse** | SQL + Spark + Pipelines; Fabric is the newer unified experience |
| ETL / Data integration | **Azure Data Factory** / **Fabric Data Factory** | Managed ETL/ELT pipelines |
| Real-time analytics | **Stream Analytics** / **Fabric Real-Time Intelligence** | SQL over streaming data |
| BI / visualization | **Power BI** | Industry-leading BI, integrated into Fabric |
| Databricks | **Azure Databricks** | Managed Spark + Delta Lake + Unity Catalog |

## AI / ML
| Concept | Azure Service | Notes |
|---------|-------------|-------|
| Foundation models (GenAI) | **Azure OpenAI Service** / **Azure AI Foundry** | GPT-4, GPT-5, o-series, DALL-E, Whisper; AI Foundry (GA 2024) is the unified dev portal for agents, models, evaluations |
| Model catalog (multi-provider) | **Azure AI Foundry Models** | Llama, Mistral, Cohere, DeepSeek, Phi + OpenAI models |
| AI coding assistant | **GitHub Copilot** (first-party integration) | Copilot in IDE, CLI, and Azure Portal |
| ML platform (end-to-end) | **Azure Machine Learning** | Build, train, deploy, MLOps |
| Speech / Vision / NLP | **Azure AI Services** (formerly Cognitive Services) | Speech, Vision, Language, Document Intelligence, Content Safety |
| Business AI assistants | **Microsoft 365 Copilot** / **Copilot Studio** | Enterprise copilots; Copilot Studio for custom agents |

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
- **Cosmos DB**: Azure's flagship globally-distributed database. Know the 5 consistency levels. APIs renamed in 2023 (e.g., SQL API → NoSQL API).
- **Service Bus + Azure Functions**: Go-to pattern for async processing.
- **Event Grid + Functions**: Reactive event-driven architecture.
- **OneLake + Fabric**: "OneDrive for data" — single SaaS data estate across the org. Big strategic shift from Synapse.
- **Azure OpenAI + AI Search (vector) + Cosmos DB**: Canonical RAG stack on Azure.
- **Renamed services to remember**: Azure AD → **Entra ID**; Cognitive Services → **Azure AI Services**; Cognitive Search → **Azure AI Search**; Cosmos DB SQL API → **Cosmos DB for NoSQL**; Hyperscale (Citus) → **Cosmos DB for PostgreSQL**.

## Common Architecture Pattern on Azure
```
Azure Front Door (CDN + WAF + Global LB) → Application Gateway → AKS / Container Apps (app)
    → Azure SQL / Cosmos DB (data) + Azure Managed Redis (cache)
    → Service Bus → Azure Functions (async processing)
    → Blob Storage / ADLS Gen2 (files)
    → Event Hubs → Microsoft Fabric / Synapse (analytics)
    → Azure OpenAI + AI Search (vector) → RAG app
```
