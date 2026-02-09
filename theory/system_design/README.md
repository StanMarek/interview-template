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

### Core Services Deep Dives (`/services`)
Detailed theoretical coverage of every service type you might need to discuss.

| # | Service | File |
|---|---------|------|
| 1 | SQL Databases (RDBMS) | [sql-databases.md](services/sql-databases.md) |
| 2 | NoSQL Databases | [nosql-databases.md](services/nosql-databases.md) |
| 3 | In-Memory Caches (Redis, Memcached) | [in-memory-caches.md](services/in-memory-caches.md) |
| 4 | Object / Blob Storage | [object-storage.md](services/object-storage.md) |
| 5 | Search Engines (Elasticsearch, Solr) | [search-engines.md](services/search-engines.md) |
| 6 | Message Brokers (Kafka, RabbitMQ) | [message-brokers.md](services/message-brokers.md) |
| 7 | Load Balancers (L4 vs L7) | [load-balancers-service.md](services/load-balancers-service.md) |
| 8 | DNS | [dns.md](services/dns.md) |
| 9 | Coordination Services (ZooKeeper, etcd) | [coordination-services.md](services/coordination-services.md) |
| 10 | Graph Databases | [graph-databases.md](services/graph-databases.md) |
| 11 | Time-Series Databases | [timeseries-databases.md](services/timeseries-databases.md) |
| 12 | Containerization & Orchestration | [containers-orchestration.md](services/containers-orchestration.md) |

### Cloud Provider Mappings (`/providers`)
Map every concept to the real service name for each provider.

| Provider | File |
|----------|------|
| AWS | [aws.md](providers/aws.md) |
| Azure | [azure.md](providers/azure.md) |
| GCP | [gcp.md](providers/gcp.md) |

---

## 🧠 Interview Framework (Use This for Every Question)

1. **Clarify Requirements** (2-3 min) — functional, non-functional, scale
2. **Back-of-Envelope Estimation** (3-5 min) — QPS, storage, bandwidth
3. **High-Level Design** (10 min) — draw the boxes
4. **Deep Dive** (15 min) — pick 2-3 components and go deep
5. **Bottlenecks & Trade-offs** (5 min) — what breaks, how to fix

## 🔢 Key Numbers to Memorize

| Metric | Value |
|--------|-------|
| 1 day | 86,400 seconds (~100K) |
| 1 month | ~2.5 million seconds |
| 1 year | ~31.5 million seconds |
| QPS from DAU | DAU × avg_requests / 86400 |
| Peak QPS | ~2-5× average QPS |
| SSD random read | ~100 μs |
| HDD seek | ~10 ms |
| Network round trip (same DC) | ~0.5 ms |
| Network round trip (cross-continent) | ~150 ms |
| 1 char | 1 byte (ASCII) / 2-4 bytes (UTF-8) |
| 1 MB | 1 million bytes |
| 1 GB | 1 billion bytes |
| 1 TB | 1 trillion bytes |
| 1 million requests/day | ~12 QPS |
| 1 billion requests/day | ~12K QPS |
