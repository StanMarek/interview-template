# System Design Interview - Comprehensive Study Guide

## 📂 Structure

### Cloud-Agnostic Design Patterns (`/patterns`)
Fundamental architectural patterns that appear in nearly every system design interview.

| # | Pattern | File |
|---|---------|------|
| 1 | Load Balancing | [load-balancing.md](patterns/load-balancing.md) |
| 2 | Caching | [caching.md](patterns/caching.md) |
| 3 | Database Sharding & Partitioning | [sharding-partitioning.md](patterns/sharding-partitioning.md) |
| 4 | Replication | [replication.md](patterns/replication.md) |
| 5 | Consistent Hashing | [consistent-hashing.md](patterns/consistent-hashing.md) |
| 6 | Message Queues & Event-Driven Architecture | [message-queues.md](patterns/message-queues.md) |
| 7 | API Gateway | [api-gateway.md](patterns/api-gateway.md) |
| 8 | CDN (Content Delivery Network) | [cdn.md](patterns/cdn.md) |
| 9 | Rate Limiting & Throttling | [rate-limiting.md](patterns/rate-limiting.md) |
| 10 | Database Indexing | [database-indexing.md](patterns/database-indexing.md) |
| 11 | CAP Theorem & Consistency Models | [cap-theorem.md](patterns/cap-theorem.md) |
| 12 | Microservices vs Monolith | [microservices-vs-monolith.md](patterns/microservices-vs-monolith.md) |
| 13 | Pub/Sub Pattern | [pub-sub.md](patterns/pub-sub.md) |
| 14 | CQRS & Event Sourcing | [cqrs-event-sourcing.md](patterns/cqrs-event-sourcing.md) |
| 15 | Circuit Breaker & Resilience | [circuit-breaker.md](patterns/circuit-breaker.md) |
| 16 | Heartbeat & Health Checks | [heartbeat.md](patterns/heartbeat.md) |
| 17 | Leader Election | [leader-election.md](patterns/leader-election.md) |
| 18 | Bloom Filters & Probabilistic Data Structures | [bloom-filters.md](patterns/bloom-filters.md) |
| 19 | Proxy (Forward & Reverse) | [proxy.md](patterns/proxy.md) |
| 20 | Data Lakes & Data Warehouses | [data-lakes-warehouses.md](patterns/data-lakes-warehouses.md) |
| 21 | Back-of-the-Envelope Estimation | [back-of-envelope.md](patterns/back-of-envelope.md) |
| 22 | Service Discovery | [service-discovery.md](patterns/service-discovery.md) |
| 23 | Distributed Locking | [distributed-locking.md](patterns/distributed-locking.md) |
| 24 | Saga Pattern (Distributed Transactions) | [saga-pattern.md](patterns/saga-pattern.md) |
| 25 | Idempotency | [idempotency.md](patterns/idempotency.md) |
| 26 | Transactional Outbox Pattern | [outbox-pattern.md](patterns/outbox-pattern.md) |
| 27 | Two-Phase Commit (2PC) & Distributed Transactions | [two-phase-commit.md](patterns/two-phase-commit.md) |
| 28 | Consensus & Quorums (Raft, Paxos, quorum math) | [consensus-and-quorums.md](patterns/consensus-and-quorums.md) |
| 29 | Concurrency Control (OCC, pessimistic, MVCC) | [concurrency-control.md](patterns/concurrency-control.md) |
| 30 | Streaming Semantics (delivery, windowing, watermarks) | [streaming-semantics.md](patterns/streaming-semantics.md) |
| 31 | API Design & Pagination (REST/RPC/GraphQL, cursors, versioning) | [api-design-and-pagination.md](patterns/api-design-and-pagination.md) |

### Core Services Deep Dives (`/services`)
Detailed theoretical coverage of every service type you might need to discuss.

| # | Service | File |
|---|---------|------|
| 1 | SQL Databases (RDBMS) | [sql-databases.md](services/sql-databases.md) |
| 2 | NoSQL Databases | [nosql-databases.md](services/nosql-databases.md) |
| 3 | In-Memory Caches (Redis, Valkey, Memcached) | [in-memory-caches.md](services/in-memory-caches.md) |
| 4 | Object / Blob Storage | [object-storage.md](services/object-storage.md) |
| 5 | Search Engines (Elasticsearch, OpenSearch, Solr) | [search-engines.md](services/search-engines.md) |
| 6 | Message Brokers (Kafka, RabbitMQ) | [message-brokers.md](services/message-brokers.md) |
| 7 | Load Balancers (L4 vs L7) | [load-balancers-service.md](services/load-balancers-service.md) |
| 8 | DNS | [dns.md](services/dns.md) |
| 9 | Coordination Services (ZooKeeper, etcd) | [coordination-services.md](services/coordination-services.md) |
| 10 | Graph Databases | [graph-databases.md](services/graph-databases.md) |
| 11 | Time-Series Databases | [timeseries-databases.md](services/timeseries-databases.md) |
| 12 | Containerization & Orchestration | [containers-orchestration.md](services/containers-orchestration.md) |
| 13 | Vector Databases (RAG / Embeddings) | [vector-databases.md](services/vector-databases.md) |

### Cloud Provider Mappings (`/providers`)
Map every concept to the real service name for each provider.

| Provider | File |
|----------|------|
| AWS | [aws.md](providers/aws.md) |
| Azure | [azure.md](providers/azure.md) |
| GCP | [gcp.md](providers/gcp.md) |

### Practice Tracks
Focused drill material for mock interviews and whiteboard sessions.

| Topic | File |
|-------|------|
| System Design Interview Practice Tasks | [interview-practice-tasks.md](interview-practice-tasks.md) |

### Worked Solution Diagrams (`/diagrams`)
Each practice task from `interview-practice-tasks.md` has a matching markdown solution write-up. Architecture PNGs live alongside the markdown in the same folder but are **not** embedded inline in the write-ups — open the PNG next to the `.md` when reading, or use the markdown as a standalone whiteboard redraw prompt. The categories below line up 1:1 with the sections of the practice tasks file.

#### Core Interview Staples (`/diagrams/core`)
| # | System | File |
|---|--------|------|
| 1 | URL Shortener | [01_url_shortener.md](diagrams/core/01_url_shortener.md) |
| 2 | Rate Limiter | [02_rate_limiter.md](diagrams/core/02_rate_limiter.md) |
| 3 | Distributed Cache | [03_distributed_cache.md](diagrams/core/03_distributed_cache.md) |
| 4 | Notification Service | [04_notification_service.md](diagrams/core/04_notification_service.md) |
| 5 | File Upload & Image Processing | [05_file_upload_image_processing.md](diagrams/core/05_file_upload_image_processing.md) |
| 6 | Search Autocomplete | [06_search_autocomplete.md](diagrams/core/06_search_autocomplete.md) |
| 7 | API Gateway | [07_api_gateway.md](diagrams/core/07_api_gateway.md) |
| 8 | Feature Flag Service | [08_feature_flag_service.md](diagrams/core/08_feature_flag_service.md) |
| 9 | Webhook Delivery Platform | [09_webhook_delivery_platform.md](diagrams/core/09_webhook_delivery_platform.md) |
| 10 | Distributed Config Service | [10_distributed_config_service.md](diagrams/core/10_distributed_config_service.md) |

#### Product-Style Systems (`/diagrams/product`)
| # | System | File |
|---|--------|------|
| 1 | Chat System | [01_chat_system.md](diagrams/product/01_chat_system.md) |
| 2 | News Feed | [02_news_feed.md](diagrams/product/02_news_feed.md) |
| 3 | Photo Sharing (Instagram) | [03_photo_sharing.md](diagrams/product/03_photo_sharing.md) |
| 4 | Video Streaming (YouTube-lite) | [04_video_streaming.md](diagrams/product/04_video_streaming.md) |
| 5 | Collaborative Editing (Google Docs) | [05_collaborative_editing.md](diagrams/product/05_collaborative_editing.md) |
| 6 | Ticket Booking | [06_ticket_booking.md](diagrams/product/06_ticket_booking.md) |
| 7 | Ride-Hailing | [07_ride_hailing.md](diagrams/product/07_ride_hailing.md) |
| 8 | E-commerce Cart & Checkout | [08_ecommerce_cart.md](diagrams/product/08_ecommerce_cart.md) |
| 9 | Restaurant Delivery | [09_restaurant_delivery.md](diagrams/product/09_restaurant_delivery.md) |
| 10 | Calendar Scheduling | [10_calendar_scheduling.md](diagrams/product/10_calendar_scheduling.md) |

#### Big-Scale Data & Streaming (`/diagrams/data_streaming`)
| # | System | File |
|---|--------|------|
| 1 | Real-time Leaderboard | [01_realtime_leaderboard.md](diagrams/data_streaming/01_realtime_leaderboard.md) |
| 2 | Trending Hashtags / Top-K | [02_trending_hashtags.md](diagrams/data_streaming/02_trending_hashtags.md) |
| 3 | Ad Click Aggregation | [03_ad_click_aggregation.md](diagrams/data_streaming/03_ad_click_aggregation.md) |
| 4 | Metrics & Logs Ingestion | [04_metrics_logs_ingestion.md](diagrams/data_streaming/04_metrics_logs_ingestion.md) |
| 5 | Recommendation Events Pipeline | [05_recommendation_events.md](diagrams/data_streaming/05_recommendation_events.md) |
| 6 | Fraud Detection | [06_fraud_detection.md](diagrams/data_streaming/06_fraud_detection.md) |
| 7 | Smart City Sensor Ingestion | [07_smart_city_sensors.md](diagrams/data_streaming/07_smart_city_sensors.md) |
| 8 | CDC-Based Analytics | [08_cdc_analytics.md](diagrams/data_streaming/08_cdc_analytics.md) |
| 9 | Anomaly Detection | [09_anomaly_detection.md](diagrams/data_streaming/09_anomaly_detection.md) |
| 10 | Audit Logging | [10_audit_logging.md](diagrams/data_streaming/10_audit_logging.md) |

#### Reliability & Senior-Level (`/diagrams/reliability`)
| # | System | File |
|---|--------|------|
| 1 | Payment Processing | [01_payment_processing.md](diagrams/reliability/01_payment_processing.md) |
| 2 | Reservation (Anti-Double-Booking) | [02_reservation_system.md](diagrams/reliability/02_reservation_system.md) |
| 3 | Distributed Lock Service | [03_distributed_lock_service.md](diagrams/reliability/03_distributed_lock_service.md) |
| 4 | Object Storage (S3-like) | [04_object_storage.md](diagrams/reliability/04_object_storage.md) |
| 5 | Multi-Region Replication | [05_multi_region_replication.md](diagrams/reliability/05_multi_region_replication.md) |
| 6 | Distributed Denylist | [06_distributed_denylist.md](diagrams/reliability/06_distributed_denylist.md) |
| 7 | Secrets Management | [07_secrets_management.md](diagrams/reliability/07_secrets_management.md) |
| 8 | Identity & Session Management | [08_identity_session_management.md](diagrams/reliability/08_identity_session_management.md) |
| 9 | Idempotent API Execution | [09_idempotent_api.md](diagrams/reliability/09_idempotent_api.md) |
| 10 | Global Inventory Management | [10_global_inventory_management.md](diagrams/reliability/10_global_inventory_management.md) |

#### Infrastructure-Focused (`/diagrams/infrastructure`)
| # | System | File |
|---|--------|------|
| 1 | CDN | [01_cdn.md](diagrams/infrastructure/01_cdn.md) |
| 2 | Load Balancing | [02_load_balancer.md](diagrams/infrastructure/02_load_balancer.md) |
| 3 | Service Discovery | [03_service_discovery.md](diagrams/infrastructure/03_service_discovery.md) |
| 4 | Container Orchestration Control Plane | [04_container_orchestration.md](diagrams/infrastructure/04_container_orchestration.md) |
| 5 | Distributed Job Scheduler | [05_distributed_job_scheduler.md](diagrams/infrastructure/05_distributed_job_scheduler.md) |
| 6 | Schema Migration Platform | [06_schema_migration.md](diagrams/infrastructure/06_schema_migration.md) |
| 7 | Centralized Observability | [07_centralized_observability.md](diagrams/infrastructure/07_centralized_observability.md) |
| 8 | Multi-Tenant SaaS Architecture | [08_multi_tenant_saas.md](diagrams/infrastructure/08_multi_tenant_saas.md) |
| 9 | Zero-Downtime Deployment | [09_zero_downtime_deployment.md](diagrams/infrastructure/09_zero_downtime_deployment.md) |
| 10 | Backup & Disaster Recovery | [10_backup_disaster_recovery.md](diagrams/infrastructure/10_backup_disaster_recovery.md) |

Standalone reference diagrams also live in `/diagrams`: `caching.png`, `cap_theorem.png`, `db_scaling.png`.

---

## 🧠 Interview Framework (Use This for Every Question)

1. **Clarify Requirements** (2-3 min) — functional, non-functional, scale
2. **Back-of-Envelope Estimation** (3-5 min) — QPS, storage, bandwidth
3. **High-Level Design** (10 min) — draw the boxes
4. **Deep Dive** (15 min) — pick 2-3 components and go deep
5. **Bottlenecks & Trade-offs** (5 min) — what breaks, how to fix

## 🔢 Key Numbers to Memorize

### Time & Traffic

| Metric | Value |
|--------|-------|
| 1 day | 86,400 seconds (~100K) |
| 1 month | ~2.5 million seconds |
| 1 year | ~31.5 million seconds |
| QPS from DAU | DAU × avg_requests / 86400 |
| Peak QPS | ~2-5× average QPS |
| 1 million requests/day | ~12 QPS |
| 1 billion requests/day | ~12K QPS |

### Latency (Memory Hierarchy → Disk → Network)

| Operation | Latency | Notes |
|-----------|---------|-------|
| L1 cache reference | ~1 ns | ~4 CPU cycles |
| L2 cache reference | ~3 ns | ~12 CPU cycles |
| L3 / LLC cache reference | ~10-12 ns | ~26 CPU cycles |
| Main memory (DRAM) reference | ~100 ns | ~200× slower than L1 |
| Compress 1 KB with zstd/snappy | ~2 μs | |
| Send 1 KB over 10 Gbps network | ~1 μs | |
| SSD random read (NVMe) | ~20 μs | Modern NVMe; SATA SSD is ~100 μs |
| Read 1 MB sequentially from memory | ~50 μs | |
| Round trip within same data center | ~0.5 ms | |
| Round trip same region (cross-AZ) | ~1-2 ms | |
| Read 1 MB sequentially from NVMe SSD | ~1 ms | |
| HDD seek | ~10 ms | Still relevant for cold/archival storage |
| Round trip cross-region (US East ↔ US West) | ~60-70 ms | |
| Round trip cross-continent (US ↔ EU) | ~80-100 ms | |
| Round trip cross-continent (US ↔ Asia) | ~150-180 ms | |

### Size / Encoding

| Unit | Value |
|------|-------|
| 1 char | 1 byte (ASCII) / 2-4 bytes (UTF-8) |
| UUID | 16 bytes (binary) / 36 bytes (string) |
| 1 KB | 1,000 bytes |
| 1 MB | 1 million bytes |
| 1 GB | 1 billion bytes |
| 1 TB | 1 trillion bytes |
| Typical disk block / SSD page | 4 KB |
| Typical HTTP request header | ~500 B - 2 KB |
| Typical JSON API response | ~1-10 KB |
