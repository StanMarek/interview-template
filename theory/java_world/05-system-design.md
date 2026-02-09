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
| Main memory reference | 100 ns |
| SSD random read | 150 μs |
| HDD seek | 10 ms |
| Same-DC round trip | 0.5 ms |
| Cross-continent round trip | 150 ms |
| 1 million users × 1KB | 1 GB |

---

## 2. Key Architecture Patterns

### Rate Limiting Algorithms

| Algorithm | Description | Pros | Cons |
|-----------|-------------|------|------|
| Token Bucket | Tokens added at fixed rate | Allows bursts | Memory per user |
| Leaky Bucket | Fixed-rate queue drain | Smooth output | No burst handling |
| Fixed Window | Count per time window | Simple | 2× burst at window boundary |
| Sliding Window Counter | Weighted current + previous | Good accuracy, efficient | Approximate |

### Consistent Hashing

Distributes keys across nodes with minimal redistribution on node changes. Standard `hash(key) % N` redistributes ~100% of keys on adding a node. Consistent hashing only redistributes `K/N` keys. Use **virtual nodes** (100-200 per physical node) for even distribution.

### Message Queue Patterns

- **Competing Consumers**: One message → one consumer. Work distribution.
- **Fan-Out (Pub/Sub)**: One message → all subscribers. Event notification.
- **Dead Letter Queue (DLQ)**: Failed messages after N retries → separate queue for investigation.
- **Exactly-Once**: Practically achieved via at-least-once delivery + idempotent consumer.

### CQRS + Event Sourcing

```
Commands ──→ Write Model (event store) ──events──→ Read Model (projections)
                                                          ↑
Queries ──────────────────────────────────────────────────┘
```

Benefits: Independent scaling of reads/writes, full audit trail, temporal queries. Risks: Eventual consistency, event schema evolution, operational complexity.

---

## 3. Kafka Deep Dive

### Architecture

```
Producer → Topic (partitioned) → Consumer Group
          P0: [msg1, msg3, ...]    Consumer 1 ← P0
          P1: [msg2, msg5, ...]    Consumer 2 ← P1
          P2: [msg4, msg7, ...]    Consumer 3 ← P2
```

Each partition is an ordered, immutable append-only log. Messages identified by offset. Partitions are replicated across brokers (ISR = In-Sync Replicas).

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
```

### Consumer Group Rebalancing

When a consumer joins/leaves a group, partitions are reassigned. During rebalancing, no messages are consumed (stop-the-world).

**Cooperative Sticky Assignor** (Kafka 3+): Incremental rebalancing — only reassigns affected partitions, others continue consuming. Always use this over the eager rebalancing.

### Common Risks

- **Consumer lag**: Consumer falls behind. Monitor with Burrow or Kafka lag exporter. Scale consumers (max = number of partitions).
- **Partition skew**: Uneven key distribution → some partitions much larger. Choose keys carefully.
- **Large messages**: Kafka default max is 1MB. For large payloads, store in S3/blob storage and send reference in Kafka.
- **Exactly-once across systems**: Use Kafka Transactions for Kafka-to-Kafka. For Kafka-to-DB, use idempotent consumer with deduplication table.

---

## 4. API Design

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

---

## 5. Security Fundamentals

### Authentication vs Authorization

- **Authentication (AuthN)**: WHO are you? (login, token validation)
- **Authorization (AuthZ)**: WHAT can you do? (permissions, roles)

### OAuth 2.0 / OIDC Flows

| Flow | Use Case |
|------|----------|
| Authorization Code + PKCE | Web apps, mobile apps, SPAs (recommended) |
| Client Credentials | Machine-to-machine (service accounts) |
| Device Code | Smart TVs, CLI tools |

**Token Types**:
- **Access Token**: Short-lived (5-60 min), sent with each request. JWT or opaque.
- **Refresh Token**: Long-lived, used to get new access tokens. Store securely (httpOnly cookie or encrypted storage).
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

### OWASP Top 10 Awareness

| Vulnerability | Prevention |
|--------------|------------|
| Injection (SQL, NoSQL) | Parameterized queries, ORM, input validation |
| Broken Authentication | MFA, secure session management, account lockout |
| Sensitive Data Exposure | Encryption at rest/transit, don't log PII |
| XXE | Disable external entities in XML parsers |
| Broken Access Control | Server-side enforcement, deny by default, RBAC |
| Security Misconfiguration | Hardened defaults, remove unused features |
| XSS | Output encoding, CSP headers |
| Insecure Deserialization | Don't deserialize untrusted data, type whitelists |
| SSRF | Allowlist outbound URLs, validate/sanitize URLs |

---

## 6. Infrastructure & Deployment

### CI/CD Pipeline

```
Code Push → Build → Unit Tests → Integration Tests → Security Scan
    → Docker Build → Push to Registry → Deploy to Staging
    → Smoke Tests → Canary Deploy (5% traffic) → Monitor
    → Progressive Rollout (25% → 50% → 100%)
```

### Deployment Strategies

| Strategy | Description | Risk | Rollback |
|----------|-------------|------|----------|
| Rolling | Replace instances one by one | Mixed versions during deploy | Slow (reverse rolling) |
| Blue-Green | Two identical environments, switch traffic | Double infrastructure cost | Instant (switch back) |
| Canary | Route small % to new version | Requires good monitoring | Instant (route to old) |
| Feature Flags | Code deployed but feature toggled | Flag management complexity | Toggle off |

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

---

## 7. Performance & Scalability

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

---

## 8. Common System Design Interview Questions

**Q: Design a chat system (WhatsApp-like)**
Key components: WebSocket gateway for real-time messaging, message queue for delivery, message storage (Cassandra for write-heavy), presence service (Redis), push notification service. Group chats: fan-out on write vs fan-out on read trade-off.

**Q: Design a feed system (Twitter/Instagram)**
Fan-out on write (push model): Pre-compute feeds on write. Fast reads, expensive writes. Bad for users with millions of followers.
Fan-out on read (pull model): Compute feed at read time. Cheap writes, slower reads.
Hybrid: Push for normal users, pull for celebrities.

**Q: Design a distributed task scheduler**
Components: API for task submission, task store (DB), scheduler (leader-elected), worker pool, DLQ for failed tasks. Consider: at-least-once execution, task deduplication, priority queues, delayed execution (scheduled time), retry with backoff, task timeout and heartbeat.

**Q: How do you handle a system that's getting 10× more traffic than expected?**
Immediate: Auto-scaling kicks in, cache aggressively, enable rate limiting, shed non-critical load, activate circuit breakers. Short-term: Add read replicas, scale cache cluster, optimize hot queries. Long-term: Re-architect bottlenecks, introduce async processing, consider sharding.
