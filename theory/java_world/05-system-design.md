# System Design & Architecture — Senior Engineer Interview Preparation

---

## 1. System Design Interview Framework

### Step-by-Step Approach

1. **Requirements Clarification (3-5 min)**: Functional requirements, non-functional (scale, latency, availability), constraints.
2. **Estimation (2-3 min)**: QPS, storage, bandwidth.
3. **High-Level Design (10-15 min)**: Draw major components.
4. **Deep Dive (10-15 min)**: Interviewer-selected components.
5. **Wrap-Up (3-5 min)**: Bottlenecks, monitoring, improvements.

### Key Numbers to Memorize

| Metric | Value |
|--------|-------|
| QPS single web server | 1K-10K |
| QPS single SQL DB | 5K-10K |
| QPS Redis | 100K+ |
| L1 cache reference | 0.5 ns |
| Branch mispredict | 5 ns |
| L2 cache reference | 7 ns |
| Mutex lock/unlock | 25 ns |
| Main memory reference | 100 ns |
| Compress 1KB (Snappy) | 2 μs |
| Send 2KB over 1 Gbps | 20 μs |
| SSD random read | 150 μs |
| Read 1MB sequential (memory) | 250 μs |
| Read 1MB sequential (SSD) | 1 ms |
| HDD seek | 10 ms |
| Read 1MB sequential (disk) | 20 ms |
| Same-DC round trip | 0.5 ms |
| Cross-region round trip (same continent) | 10-30 ms |
| Cross-continent round trip | 150 ms |
| 1 million users × 1KB | 1 GB |

### Back-of-the-Envelope Calculations

**Typical conversions**:
- 1 day ≈ 100K seconds (actually 86,400 — round up for capacity planning)
- 1 month ≈ 2.5M seconds
- 100M DAU × 10 actions/day ÷ 100K s = **10K QPS average**. Peak ≈ 3× = **30K QPS**
- Storage: 100M users × 1 KB/user × 365 days = **36 GB/year** (hot) vs PB-scale (analytics/logs)
- Bandwidth: 10K QPS × 10 KB payload = **100 MB/s = 800 Mbps**

**Worked example — Twitter-scale feed**:
- 300M DAU, 200 follows avg, 2 tweets/day → 600M tweets/day ≈ 7K writes/sec
- Read QPS: 300M × 5 feed views = 1.5B/day ≈ 20K reads/sec (peak 60K)
- Storage (10 years): 600M × 365 × 10 × 280 bytes ≈ 600 TB text + media PB
- Fan-out: 7K writes × 200 followers avg = 1.4M writes/sec into feed stores (pre-compute model)

---

## 2. Distributed Systems Fundamentals

### CAP Theorem

During a **network partition**, you must choose between **Consistency** and **Availability**. You cannot have both. Partition tolerance is non-negotiable in any real distributed system.

| Class | Behavior on Partition | Examples |
|-------|----------------------|----------|
| CP | Reject requests to preserve consistency | ZooKeeper, etcd, HBase, MongoDB (default) |
| AP | Serve stale data to preserve availability | DynamoDB, Cassandra, CouchDB, Riak |
| CA (only non-partitioned) | Single-node RDBMS | PostgreSQL on one host |

### PACELC (more complete model)

**If Partition** → trade Availability vs Consistency. **Else** → trade Latency vs Consistency.

| System | P | E |
|--------|---|---|
| DynamoDB | PA | EL (DynamoDB offers only strong vs eventually-consistent reads — not fully tunable quorum like Cassandra) |
| Cassandra | PA | EL |
| MongoDB | PC | EC (CP only with `writeConcern: majority`; default `w:1` can lose acknowledged writes on primary failover) |
| Spanner | PC | EC (uses TrueTime) |
| CockroachDB | PC | EC |

### Consistency Models (Strongest → Weakest)

| Model | Guarantee | Example |
|-------|-----------|---------|
| Strict consistency | Theoretical only — requires instantaneous global propagation | — |
| Linearizability | Per Herlihy/Wing — strongest practically-achievable model, each operation appears to take effect at a single point between invocation and response | Spanner, etcd, single-leader DB |
| Sequential | Same order seen by all, but not real-time | — |
| Causal | Causally-related ops ordered; concurrent ops may differ | Riak, CRDTs |
| Read-your-writes | Client sees its own writes | Session consistency |
| Monotonic reads | Never read older than previously read | — |
| Eventual | All replicas converge eventually | DynamoDB, Cassandra default |

**Rule of thumb**: Payments, inventory, counters → strong. Social feeds, likes, analytics → eventual.

### Consensus Algorithms

| Algorithm | Notes |
|-----------|-------|
| **Paxos** | Original, notoriously hard to implement correctly. Used in Google Chubby. |
| **Raft** | Designed for understandability. Leader-based, log replication, leader election via terms. Used in etcd, CockroachDB, Consul. |
| **Multi-Paxos / EPaxos** | Optimizations for throughput and leaderless operation. |
| **ZAB** | ZooKeeper atomic broadcast — similar to Raft. |

Consensus requires **quorum** (N/2 + 1 nodes). Tolerates `f` failures with `2f + 1` nodes. Cluster sizes 3, 5, 7 — odd cluster sizes are preferred; even-numbered clusters not only waste a node but also increase split-vote probability during elections.

### Leader Election

Ways to elect a leader:
1. **ZooKeeper/etcd** — ephemeral sequential znodes; lowest-seq node is leader. Heartbeat via session. Safe and battle-tested.
2. **Raft-based** — built into etcd, Consul, CockroachDB. Terms + randomized election timeouts prevent split votes.
3. **DB row lock** — cheap, coarse-grained (`SELECT ... FOR UPDATE SKIP LOCKED` or row with lease timestamp). Good enough for schedulers.
4. **Redis** — `SET key value NX PX ttl`; simple but **not safe for correctness** (Kleppmann's fencing token critique applies). Use only for best-effort jobs.

### Distributed Locks

| Backend | Safe for correctness? | Notes |
|---------|----------------------|-------|
| Redis single-node | No | Process pause > TTL causes two holders. |
| Redlock (multi-Redis) | Debated | Martin Kleppmann: insufficient. Antirez: OK with fencing tokens. |
| ZooKeeper | Yes | Ephemeral znodes auto-release on session death. |
| etcd | Yes | Lease + compare-and-swap; used by Kubernetes. |
| DB row lock | Yes | Simple; scales poorly under contention. |

**Fencing tokens**: Every lock acquisition returns a monotonic token. The resource (e.g., storage) rejects writes with a stale token — guards against pauses, clock skew, network delays.

---

## 3. Architecture Patterns

### Rate Limiting Algorithms

| Algorithm | Description | Pros | Cons |
|-----------|-------------|------|------|
| Token Bucket | Tokens added at fixed rate; consume to serve | Allows bursts | Memory per user |
| Leaky Bucket | Fixed-rate queue drain | Smooth output | No burst handling |
| Fixed Window | Count per time window | Simple | 2× burst at window boundary |
| Sliding Window Log | Store timestamps; remove expired | Exact | Memory grows with QPS |
| Sliding Window Counter | Weighted current + previous bucket | Good accuracy, efficient | Approximate |

**Distributed implementation**: Redis `INCR` with `EXPIRE` (fixed window), Lua script for atomicity (token bucket). For very high QPS, use local in-memory bucket + async sync to Redis (trade precision for throughput).

### Caching Strategies

| Pattern | Write Path | Read Path | Use When |
|---------|-----------|-----------|----------|
| **Cache-Aside** (lazy) | App writes DB, invalidates cache | Miss → DB → populate cache | Default; read-heavy, tolerate stale |
| **Read-Through** | App writes DB directly | Cache loads from DB on miss | Want app to ignore cache logic |
| **Write-Through** | App writes cache; cache writes DB synchronously | Always hit cache | Strict read-after-write, low write QPS |
| **Write-Back** (write-behind) | App writes cache; async flush to DB | Always hit cache | Write-heavy, tolerate loss on crash |
| **Refresh-Ahead** | — | Cache proactively refreshes before TTL | Hot keys with predictable access |

**Common cache failure modes**:
- **Thundering herd / cache stampede**: TTL expires, many requests miss simultaneously. Fix: request coalescing (singleflight), probabilistic early expiration, lock-per-key, or stale-while-revalidate.
- **Cache penetration**: Queries for non-existent keys always miss. Fix: cache negative results (with short TTL) or bloom filter.
- **Cache avalanche**: Many keys expire at once. Fix: jittered TTLs.
- **Hot key**: One key dominates traffic. Fix: local in-process cache (Caffeine) in front of Redis, or replicate key across N shards.

### TTL Strategies

- **Absolute TTL**: Hard expiry after N seconds.
- **Sliding TTL**: Refresh on access (risk: hot keys never expire).
- **SWR (Stale-While-Revalidate)**: Serve stale, refresh async. Common in CDNs.
- **Event-driven invalidation**: Pub/sub when underlying data changes. Best for strict consistency; operational complexity.

### CDN

Edge-cached content close to users. Key mechanics:
- **Pull (origin-fetch)**: CDN fetches on first miss. Simple; requires cache-control headers.
- **Push**: Pre-upload assets (videos, large files). Expensive for dynamic content.
- **Cache key**: URL + vary headers (Accept-Encoding, device type). Misconfigured vary explodes cache size.
- **Purging**: Instant for a few URLs; slow global propagation for wildcards. Prefer versioned URLs (`app.abc123.js`) over purge.
- **Tiered caching**: Edge → regional shield → origin — reduces origin load.

### Consistent Hashing

Distributes keys across nodes with minimal redistribution on node changes. Standard `hash(key) % N` redistributes ~100% of keys on adding a node. Consistent hashing only redistributes `K/N` keys. Use **virtual nodes** (100-200 per physical node) for even distribution. **Rendezvous hashing (HRW)** is a simpler alternative with better load balance guarantees and no ring.

### Load Balancing

**Layer 4 (TCP)**: Fast, protocol-agnostic. Examples: AWS NLB, HAProxy TCP mode, IPVS. ~millions of connections per box.

**Layer 7 (HTTP)**: Content-aware (host, path, headers, cookies). Examples: ALB, NGINX, Envoy, Traefik. Slower but supports routing, retries, TLS termination.

| Algorithm | Behavior |
|-----------|----------|
| Round Robin | Rotate evenly. |
| Weighted RR | Weight by instance capacity. |
| Least Connections | Send to backend with fewest active conns. Good for long-lived. |
| Least Response Time | Pick by EWMA latency. Reacts to slow hosts. |
| Hash (IP / URL) | Sticky routing; cache-friendly. |
| Power of Two Choices (P2C) | Pick 2 random, take the less-loaded. Near-optimal, cheap. |
| Maglev | Google's consistent-hash-based LB; minimal disruption on changes. |

### Message Queue Patterns

- **Competing Consumers**: One message → one consumer. Work distribution.
- **Fan-Out (Pub/Sub)**: One message → all subscribers. Event notification.
- **Dead Letter Queue (DLQ)**: Failed messages after N retries → separate queue for investigation.
- **Outbox pattern**: Write DB + outbox row in one transaction; separate relay publishes to broker. Solves dual-write / "lost message" problem.
- **Saga**: Long-running workflow as a sequence of local transactions with compensating actions. Choreography (events) or orchestration (central coordinator).
- **Exactly-Once**: Practically achieved via at-least-once delivery + idempotent consumer.

### Broker Comparison (2026)

| Feature | Kafka | RabbitMQ | AWS SQS | Pulsar |
|---------|-------|----------|---------|--------|
| Model | Partitioned log | AMQP queues + exchanges | Managed queue | Log + tiered storage |
| Throughput | Very high (~1M msgs/s) | High (~30-50K msgs/s) | High, opaque scale | Very high |
| Latency | Low (~5ms) | Very low (<1ms) | 10-100ms (managed) | Low |
| Ordering | Per partition | Strict FIFO per queue at broker level; consumer-side ordering is best-effort with multiple consumers, requeues, or priority queues | FIFO queues only | Per partition |
| Retention | Long (days-years) | Short (consume & ack) | 14 days max | Infinite (BookKeeper tiered to S3) |
| Delivery | At-least / exactly-once (txn) | At-least / at-most | At-least / FIFO exactly-once | At-least / effectively-once |
| Best for | Event streaming, replay, analytics | Complex routing, RPC, low-latency tasks | Simple AWS workloads | Multi-tenant, geo-replication |

### Idempotency

Essential for at-least-once delivery, retries, and safe POST/write APIs.

**Strategies**:
1. **Idempotency key + dedup table**: Client sends UUID; server stores (key → result) with TTL. On retry, return cached result.
2. **Natural keys**: `(order_id, event_type)` unique index — second insert fails safely.
3. **Upserts**: `INSERT ... ON CONFLICT DO NOTHING` / `MERGE`. Beware non-deterministic side effects.
4. **Conditional updates**: Optimistic concurrency with version/ETag (`UPDATE ... WHERE version = ?`).
5. **State machines**: Only apply transition if current state allows. `pending → confirmed` is idempotent; applying twice has no effect.

Every write API that can be retried (payments, messaging, order creation) **must** be idempotent.

### CQRS + Event Sourcing

```
Commands ──→ Write Model (event store) ──events──→ Read Model (projections)
                                                          ↑
Queries ──────────────────────────────────────────────────┘
```

**Event Sourcing**: Store every state change as an immutable event. Current state = fold over events. Replay → rebuild projections.

**Benefits**: Independent scaling of reads/writes, full audit trail, temporal queries ("what was the balance yesterday?"), easy debugging.

**Risks**: Eventual consistency between read/write, event schema evolution (versioning, upcasting), operational complexity, snapshotting required for long streams.

### Domain-Driven Design (DDD) Basics

- **Bounded Context**: Explicit boundary around a model. Each service owns one. Cross-context translation via anti-corruption layer.
- **Aggregate**: Cluster of entities with consistency boundary. One aggregate = one transaction.
- **Aggregate Root**: Entry point; enforces invariants. External code never touches children directly.
- **Entity vs Value Object**: Entity has identity (Customer). Value Object is immutable, compared by attributes (Money, Address).
- **Domain Event**: Something that happened in the past tense (`OrderPlaced`). Drives integration between bounded contexts.
- **Ubiquitous Language**: Domain terms consistent in code, docs, DB schema, conversations.

**Context map patterns**: Shared Kernel, Customer-Supplier, Conformist, Anti-Corruption Layer, Open Host Service, Published Language.

### Event-Driven Architecture

- **Event notification**: Thin event (`OrderPlaced{id}`) — consumers call back for details. Loose coupling, chattier.
- **Event-carried state transfer**: Fat event (full order payload) — consumers avoid callbacks but tightly coupled to schema.
- **Event sourcing**: Event log is the source of truth (see above).

Patterns to know: **Transactional Outbox**, **CDC (Change Data Capture)** (Debezium → Kafka), **Event Collaboration**, **Choreography vs Orchestration**.

---

## 4. Databases: Sharding & Replication

### Replication Topologies

| Model | Writes | Reads | Use Case |
|-------|--------|-------|----------|
| Leader-Follower (master-slave) | Leader only | Leader + followers (stale OK) | Read-heavy OLTP (Postgres, MySQL) |
| Multi-Leader (master-master) | Any leader | Any | Multi-region active-active, collaborative editing |
| Leaderless (quorum) | Any node, W replicas | R replicas (R+W>N → strong) | Dynamo-style (Cassandra, DynamoDB) |

**Replication modes**:
- **Synchronous**: Leader waits for follower ACK. Strong durability, higher latency.
- **Asynchronous**: Fire-and-forget. Low latency, risk of data loss on failover.
- **Semi-sync**: Wait for at least one follower ACK. Common compromise (MySQL semi-sync, Postgres synchronous_commit=remote_apply).

**Multi-leader conflicts**: Concurrent writes to the same key require resolution — Last-Write-Wins (loses data), CRDTs (auto-merge, e.g., counters, sets), application-level merge, or operational transform (collab editing).

### Sharding Strategies

| Strategy | Pros | Cons |
|----------|------|------|
| Range | Fast range scans | Hotspots (timestamp → newest shard) |
| Hash | Even distribution | No range queries; re-sharding painful |
| Directory (lookup service) | Flexible, can migrate shards | Extra hop; lookup is SPOF |
| Geo (locality) | Low latency per region | Cross-region joins expensive |
| Composite (hash of prefix + range on suffix) | Hot-key-aware | Complex |

**Hot shards** signal bad key choice — include entropy (user_id + timestamp hashed) or use consistent hashing with virtual nodes.

### Picking a Database

| Workload | Fit |
|----------|-----|
| OLTP, strong consistency, relationships | PostgreSQL, MySQL |
| Horizontal OLTP with SQL | CockroachDB, Spanner, Vitess (sharded MySQL) |
| High write, time-series, wide column | Cassandra, ScyllaDB |
| Document, flexible schema | MongoDB, DynamoDB |
| Full-text search | Elasticsearch, OpenSearch |
| Graph traversal | Neo4j, JanusGraph |
| Cache, leaderboards, pub/sub | Redis, Valkey, Dragonfly |
| Vector similarity (RAG/LLM) | Pinecone, Milvus, Weaviate, pgvector |
| Analytics (OLAP) | ClickHouse, BigQuery, Snowflake, Druid |
| Time-series | TimescaleDB, InfluxDB, VictoriaMetrics |

---

## 5. Kafka Deep Dive

### Architecture

```
Producer → Topic (partitioned) → Consumer Group
          P0: [msg1, msg3, ...]    Consumer 1 ← P0
          P1: [msg2, msg5, ...]    Consumer 2 ← P1
          P2: [msg4, msg7, ...]    Consumer 3 ← P2
```

Each partition is an ordered, immutable append-only log. Messages identified by offset. Partitions are replicated across brokers (ISR = In-Sync Replicas). KRaft went GA for new clusters in Kafka 3.3 (Oct 2022); ZooKeeper mode was fully removed in Kafka 4.0 (March 2025). KRaft is an event-driven Raft variant, not vanilla Raft.

### Key Guarantees

- **Ordering**: Guaranteed WITHIN a partition only. Use same key for related messages.
- **Delivery semantics**: At-most-once (auto-commit), at-least-once (manual commit after processing), effectively-once (idempotent producer + transactions).
- **Durability**: `acks=all` waits for all ISR replicas. `min.insync.replicas=2` prevents data loss.

### Producer Configuration

```java
// Critical producer settings
Properties props = new Properties();
props.put("acks", "all");                          // Wait for all replicas
props.put("retries", Integer.MAX_VALUE);            // Retry indefinitely
props.put("max.in.flight.requests.per.connection", 5); // With idempotent=true, safe
props.put("enable.idempotence", true);              // Prevent duplicate messages
props.put("linger.ms", 5);                          // Batch for 5ms
props.put("batch.size", 32768);                     // 32KB batch
props.put("compression.type", "lz4");               // Compress batches
props.put("delivery.timeout.ms", 120_000);          // Fail after 2 min total
```

### Consumer Group Rebalancing

When a consumer joins/leaves a group, partitions are reassigned. During rebalancing, no messages are consumed (stop-the-world) under eager rebalancing.

**Cooperative Sticky Assignor** (Kafka 3+, default in 2026): Incremental rebalancing — only reassigns affected partitions, others continue consuming. Always use this over eager rebalancing.

### Common Risks

- **Consumer lag**: Consumer falls behind. Monitor with Burrow or Kafka lag exporter. Scale consumers (max = number of partitions).
- **Partition skew**: Uneven key distribution → some partitions much larger. Choose keys carefully.
- **Large messages**: Kafka default max is 1MB. For large payloads, store in S3/blob storage and send reference in Kafka (claim-check pattern).
- **Exactly-once across systems**: Use Kafka Transactions for Kafka-to-Kafka. For Kafka-to-DB, use idempotent consumer with deduplication table, or Transactional Outbox.
- **Rebalance storms**: Consumers with slow `poll()` loops exceed `max.poll.interval.ms` → kicked → rebalance. Tune `max.poll.records`.

---

## 6. API Design

### REST Best Practices

```
GET    /api/v1/orders              # List orders (paginated)
GET    /api/v1/orders/{id}         # Get single order
POST   /api/v1/orders              # Create order
PUT    /api/v1/orders/{id}         # Full update
PATCH  /api/v1/orders/{id}         # Partial update
DELETE /api/v1/orders/{id}         # Delete order

# Sub-resources
GET    /api/v1/orders/{id}/items   # List order items
POST   /api/v1/orders/{id}/items   # Add item to order

# Filtering, sorting, pagination
GET    /api/v1/orders?status=pending&sort=-createdAt&page=2&size=20
```

**Idempotency**: GET, PUT, DELETE are naturally idempotent. POST is not — use idempotency keys:
```
POST /api/v1/orders
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

### gRPC vs REST vs GraphQL

| Aspect | REST/JSON | gRPC | GraphQL |
|--------|-----------|------|---------|
| Transport | HTTP/1.1 or 2 | HTTP/2 + Protobuf | HTTP |
| Schema | OpenAPI (optional) | Proto (enforced) | SDL (enforced) |
| Streaming | SSE / WebSocket | Native bi-directional | Subscriptions |
| Browser | Direct | Needs grpc-web proxy | Direct |
| Over-fetching | Common | Shaped by proto | Client-picked fields |
| Best for | Public APIs, broad clients | Internal microservices, low latency | Aggregation, mobile |

### API Versioning Strategies

| Strategy | Example | Pros | Cons |
|----------|---------|------|------|
| URL path | `/api/v1/orders` | Simple, explicit | URL changes |
| Header | `Accept: application/vnd.app.v1+json` | Clean URLs | Hard to test in browser |
| Query param | `/api/orders?version=1` | Easy to test | Pollutes query string |

### Pagination Approaches

| Approach | Pros | Cons |
|----------|------|------|
| Offset-based | Simple, jump to any page | Slow on deep pages, inconsistent with inserts |
| Cursor-based | Fast, consistent | Can't jump to arbitrary page |
| Keyset | Fast, stable | Complex for multi-column sort |

### Resilience Patterns

- **Timeouts**: Every I/O call. Default "infinite" is the #1 cause of cascading failures.
- **Retries**: Exponential backoff + full jitter. Retry only idempotent ops or safely-keyed ones.
- **Circuit breaker**: Closed → Open (fast fail) → Half-Open (probe). Resilience4j in Java.
- **Bulkhead**: Isolate resources (thread pools, connection pools) so one bad dependency doesn't drown the app.
- **Rate limiting / load shedding**: Drop excess traffic at the edge before it queues.
- **Hedged requests**: Fire a second request to replica after p95; use first response. Reduces tail latency.
- **Graceful degradation**: Serve cached/partial result when a dependency is down.

---

## 7. Security Fundamentals

### Authentication vs Authorization

- **Authentication (AuthN)**: WHO are you? (login, token validation)
- **Authorization (AuthZ)**: WHAT can you do? (permissions, roles)

### OAuth 2.0 / OIDC Flows

| Flow | Use Case |
|------|----------|
| Authorization Code + PKCE | Web apps, mobile apps, SPAs (recommended) |
| Client Credentials | Machine-to-machine (service accounts) |
| Device Code | Smart TVs, CLI tools |

**Deprecated (do not use)**: Implicit flow, Resource Owner Password Credentials. Removed in OAuth 2.1.

**Token Types**:
- **Access Token**: Short-lived (5-60 min), sent with each request. JWT or opaque.
- **Refresh Token**: Long-lived, used to get new access tokens. Store securely (httpOnly cookie or encrypted storage). Rotate on each use.
- **ID Token** (OIDC): Contains user identity claims. Should NOT be used as an access token.

### JWT Structure & Risks

```
Header.Payload.Signature
eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ.signature
```

**Risks**:
- **Algorithm confusion attack**: Token specifies `"alg": "none"` → bypass signature. Always validate algorithm server-side.
- **Secret leakage**: Prefer RSA/ECDSA (asymmetric) over HMAC (symmetric). Public key can be shared; private key never leaves auth server.
- **Token size**: JWTs grow with claims. Large JWTs increase bandwidth on every request.
- **Revocation**: JWTs can't be revoked before expiry. Use short expiry + refresh tokens, or maintain a token blocklist (defeats statelessness).

### OWASP Top 10 Awareness (2021 list; the 2025 edition is in release-candidate stage as of early 2026)

| Vulnerability | Prevention |
|--------------|------------|
| Broken Access Control | Server-side enforcement, deny by default, RBAC/ABAC |
| Cryptographic Failures | TLS everywhere, AES-GCM/ChaCha20 at rest, rotate keys |
| Injection (SQL, NoSQL, Cmd) | Parameterized queries, ORM, input validation |
| Insecure Design | Threat modeling, secure-by-default templates |
| Security Misconfiguration | Hardened defaults, remove unused features |
| Vulnerable Components | SCA scans (Snyk, Dependabot), SBOM |
| Identification & Auth Failures | MFA, secure sessions, account lockout |
| Software & Data Integrity | Signed artifacts (Sigstore), verify CI/CD supply chain |
| Logging & Monitoring Failures | Centralized logs, alerts, audit trails |
| SSRF | Allowlist outbound URLs, IMDSv2 on AWS |

---

## 8. Infrastructure & Deployment

### CI/CD Pipeline

```
Code Push → Build → Unit Tests → Integration Tests → Security Scan (SAST, SCA)
    → Docker Build → Sign (cosign/Sigstore) → Push to Registry
    → Deploy to Staging → Smoke Tests → Canary Deploy (5% traffic) → Monitor
    → Progressive Rollout (25% → 50% → 100%)
```

### Deployment Strategies

| Strategy | Description | Risk | Rollback |
|----------|-------------|------|----------|
| Rolling | Replace instances one by one | Mixed versions during deploy | Slow (reverse rolling) |
| Blue-Green | Two identical environments, switch traffic | Double infrastructure cost | Instant (switch back) |
| Canary | Route small % to new version | Requires good monitoring | Instant (route to old) |
| Feature Flags | Code deployed but feature toggled | Flag management complexity | Toggle off |
| Shadow / Dark Launch | Mirror traffic to new version, discard output | No rollback risk | — |

### Infrastructure as Code

```yaml
# Terraform example — Kubernetes deployment
resource "kubernetes_deployment" "order_service" {
  metadata {
    name = "order-service"
  }
  spec {
    replicas = 3
    selector { match_labels = { app = "order-service" } }
    template {
      spec {
        container {
          name  = "order-service"
          image = "order-service:${var.version}"
          resources {
            requests = { memory = "512Mi", cpu = "250m" }
            limits   = { memory = "1Gi",   cpu = "1000m" }
          }
          liveness_probe {
            http_get { path = "/actuator/health/liveness"; port = 8080 }
          }
        }
      }
    }
  }
}
```

### Observability (Three Pillars + Traces)

- **Metrics**: Counters, gauges, histograms. Prometheus / OpenTelemetry. RED (Rate, Errors, Duration) for services; USE (Utilization, Saturation, Errors) for resources.
- **Logs**: Structured JSON, correlation IDs (trace_id, span_id). Loki / ELK / CloudWatch.
- **Traces**: Distributed request tracing via OpenTelemetry (the 2026 standard). Jaeger, Tempo, Honeycomb.
- **Profiles**: Continuous profiling (Pyroscope, Parca) — flamegraphs in production at <1% overhead.

**SLIs/SLOs/SLAs**: SLI = what you measure (latency, error rate). SLO = internal target (99.9% < 200ms). SLA = external contract (with penalties). Error budget = 1 − SLO; spend it on velocity.

---

## 9. Performance & Scalability

### Scaling Strategies

| Layer | Strategy |
|-------|----------|
| DNS | Geographic DNS routing (Route53), CDN |
| Load Balancer | L4 (TCP, fast) vs L7 (HTTP, smarter routing) |
| Application | Horizontal scaling, stateless design |
| Cache | Redis cluster, local cache (Caffeine), multi-tier |
| Database | Read replicas, sharding, connection pooling |
| Async | Message queues for write-heavy paths |

### Performance Optimization Checklist

1. **Measure first**: Profile before optimizing. Use APM (DataDog, New Relic), flame graphs, query logs.
2. **Caching**: CDN → application cache → database cache → query cache
3. **Database**: Indexes, query optimization, connection pooling, read replicas
4. **Async processing**: Queue non-critical work (emails, analytics)
5. **Compression**: Gzip/Brotli responses, compress messages
6. **Connection reuse**: HTTP/2, keep-alive, connection pooling
7. **Batch operations**: Batch DB writes, batch API calls
8. **Data locality**: CDN for static assets, region-aware routing
9. **N+1 elimination**: Data loader pattern, JOIN FETCH, batched lookups

---

## 10. GenAI / LLM System Design (2026 interview staple)

Expect at least one GenAI-flavored prompt in senior interviews: "Design a customer support copilot", "Design a semantic search over our docs", "Design a code review assistant".

### RAG Pipeline Reference Architecture

```
Ingestion:   Docs → Loader → Chunker → Embedding Model → Vector DB (+ BM25 index)
Query path:  User → Rewrite → Embed → Vector search + BM25 (hybrid) → Rerank (cross-encoder)
             → Prompt builder (top-K + instructions) → LLM → Post-process → Cache → User
Async:       Offline eval, index refresh, feedback capture
```

**Key components**:
- **Chunking**: Semantic (sentence splitter), recursive character, by document structure (headings). Overlap 10-20%. Chunk size 256-1024 tokens typical.
- **Embedding model**: `text-embedding-3-large`, Cohere, open (e5, bge). Normalize; store alongside metadata.
- **Vector DB**: Pinecone, Milvus, Weaviate, Qdrant; or pgvector for small scale. ANN indexes: HNSW (most common), IVF, ScaNN.
- **Hybrid retrieval**: Combine dense (vectors) + sparse (BM25/keyword) via Reciprocal Rank Fusion. Critical for recall.
- **Reranker**: Cross-encoder (e.g., Cohere Rerank) scores top-N from retrieval — big quality win.
- **Guardrails**: Prompt injection filters, PII redaction, output classifiers (NeMo Guardrails, Llama Guard).
- **Caching**: Semantic cache on `(embedding, prompt)` hash → skip LLM call for near-duplicate queries.

### Trade-offs to Articulate

- **Latency budget**: Embed (~10ms) + vector search (~10-50ms) + LLM time-to-first-token (TTFT): ~200-500ms for frontier models; full generation can take several seconds depending on output length. LLM dominates.
- **Token cost**: context window × price/token × QPS → $$. Cache aggressively.
- **Hallucinations**: Ground responses in retrieved context; require citations; evaluate with LLM-as-judge + human review.
- **Freshness vs cost**: Re-embed on every doc change (expensive) vs scheduled batch reindex.
- **Privacy**: Tenant isolation in vector DB (namespace per tenant); never mix embeddings across tenants.

### LLM Serving Patterns

- **Synchronous API**: Simple; blocks on LLM. Fine for chat.
- **Streaming (SSE)**: Stream tokens as generated. User-perceived latency drops drastically.
- **Async (queue + webhook)**: Long tasks (agents, deep research). Kafka + result in S3 + notify.
- **Batch inference**: Overnight jobs. Cheaper per token.
- **Model routing**: Cheap model (Haiku-class) for easy queries; expensive (Opus/GPT-class) for hard ones. Use a classifier.

---

## 11. Common System Design Interview Questions

**Q: Design a chat system (WhatsApp-like)**
Key components: WebSocket gateway for real-time messaging, message queue for delivery, message storage (Cassandra for write-heavy), presence service (Redis), push notification service. Group chats: fan-out on write vs fan-out on read trade-off. End-to-end encryption (Signal protocol) is expected at senior level.

**Q: Design a feed system (Twitter/Instagram)**
Fan-out on write (push model): Pre-compute feeds on write. Fast reads, expensive writes. Bad for users with millions of followers.
Fan-out on read (pull model): Compute feed at read time. Cheap writes, slower reads.
Hybrid: Push for normal users, pull for celebrities. Ranking model (ML) for personalization.

**Q: Design a distributed task scheduler**
Components: API for task submission, task store (DB with shard key = scheduled_time bucket), scheduler (leader-elected via ZooKeeper/etcd), worker pool (pulls via `SELECT ... FOR UPDATE SKIP LOCKED`), DLQ for failed tasks. Consider: at-least-once execution + idempotent workers, task deduplication, priority queues, delayed execution, exponential backoff with jitter, task timeout + heartbeat, visibility timeout.

**Q: Design a URL shortener**
Estimate: 1B URLs → base62 on 7 chars = 3.5T space. Generate IDs via counter + base62 (central), Snowflake (distributed), or hash + collision check. KV store (DynamoDB/Redis) for `short → long`. CDN caches redirects. Analytics via async pipeline to warehouse. 301 (permanent) vs 302 (trackable) redirect trade-off.

**Q: Design a rate limiter**
Centralized Redis with Lua script for atomicity (token bucket). For ultra-high QPS, local leaky bucket per edge server + async reconciliation. Return `429` with `Retry-After` header. Shed by tenant, user, endpoint, IP — layer them.

**Q: Design a payment system**
Idempotency on every write (idempotency key → result). Saga for multi-step flows (reserve funds → authorize → capture). Outbox pattern to publish events. Ledger: double-entry, append-only, reconciled nightly. Exactly-once semantics via idempotent consumers; never trust network. PCI scope minimization (tokenization via Stripe).

**Q: Design a semantic search / RAG over company docs**
See Section 10. Emphasize: hybrid retrieval (dense + BM25), reranking, citations, tenant isolation, evaluation harness (precision@k, faithfulness score), incremental reindex on doc updates via CDC, cost controls (semantic cache, model routing).

**Q: How do you handle a system that's getting 10× more traffic than expected?**
Immediate: Auto-scaling kicks in, cache aggressively, enable rate limiting, shed non-critical load, activate circuit breakers, turn off expensive features via feature flags. Short-term: Add read replicas, scale cache cluster, optimize hot queries, pre-warm caches. Long-term: Re-architect bottlenecks, introduce async processing, consider sharding, multi-region.
