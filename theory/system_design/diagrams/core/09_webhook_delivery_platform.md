# Webhook Delivery Platform -- Architecture Design

## Requirements

### Functional
- Accept events from internal services and deliver them as HTTP POST requests to customer-configured endpoints
- Customers register webhook subscriptions: URL, event types, secret key for signature verification
- Automatic retries with exponential backoff on delivery failure (5xx, timeout, connection error)
- Dead Letter Queue (DLQ) for events that exhaust all retries
- HMAC-SHA256 signature on every payload so customers can verify authenticity
- Delivery log: every attempt recorded with status code, latency, response body
- Manual replay: customers can trigger redelivery of any event from the dashboard
- Event ordering: best-effort ordering per subscription (not strict -- too expensive)

### Non-Functional
- **Delivery latency:** < 5 seconds from event ingestion to first delivery attempt (p95)
- **Throughput:** 100K events/minute sustained, 1M/minute burst
- **Durability:** Zero event loss -- every accepted event must be delivered or land in DLQ
- **Retry policy:** Up to 8 retries over 24 hours (exponential backoff: 30s, 1m, 5m, 30m, 2h, 6h, 12h, 24h)
- **Availability:** 99.9% for event ingestion; delivery depends on customer endpoint availability

## Scale Estimates
- **Events/day:** 50M
- **Subscriptions:** 100K active webhook endpoints
- **Average payload size:** 2 KB
- **Delivery attempts/day:** 60M (50M first attempts + retries)
- **Delivery log storage:** 60M * 500 bytes = 30 GB/day, retain 30 days = ~900 GB
- **Retry queue depth:** At any time, ~5M events waiting for retry (depends on customer reliability)

## Architecture Decisions

### Asynchronous Delivery (Never Inline)
The producing service (e.g., payment service) should never block on webhook delivery. The webhook platform accepts the event synchronously (fast -- just a queue write), then delivers asynchronously.

**Why?** Customer webhook endpoints are untrusted third-party infrastructure. They can be:
- Slow (5+ second response times)
- Unreliable (frequent 5xx errors)
- Completely down

If the payment service waited for webhook delivery, a slow customer endpoint would delay payment processing. The async architecture means the producing service is completely decoupled from delivery reliability.

### Exponential Backoff with Jitter
The retry schedule is exponential to avoid overwhelming a recovering endpoint:
```
Retry 1: 30 seconds
Retry 2: 1 minute
Retry 3: 5 minutes
Retry 4: 30 minutes
Retry 5: 2 hours
Retry 6: 6 hours
Retry 7: 12 hours
Retry 8: 24 hours
```

**With jitter:** Each delay has +/- 20% randomization to prevent "retry storms" when many webhooks to the same endpoint fail simultaneously and would otherwise all retry at exactly the same time.

**Why 8 retries over 24 hours?** This balances persistence with practicality. A 24-hour window gives the customer time to fix their endpoint (deploy a fix, scale up, unblock an IP). Beyond 24 hours, the event is stale and should be manually replayed if needed.

### Dead Letter Queue (DLQ) Design
Events that exhaust all retries move to the DLQ. The DLQ is NOT a black hole -- it's a recoverable staging area:
- Events in the DLQ are visible in the customer dashboard
- Customers can manually trigger redelivery of individual events or bulk-replay all DLQ events
- DLQ events are retained for 30 days, then archived to cold storage
- Automated alerting: if a subscription's DLQ grows beyond a threshold, notify the customer via email

**Key insight:** The DLQ must preserve the FULL event payload, not just a reference. If the original event queue has rolled over, the DLQ is the only copy.

### HMAC Signature for Security
Every webhook delivery includes a signature header:
```
X-Webhook-Signature: sha256=<HMAC-SHA256(payload, secret)>
```

The customer verifies: `HMAC-SHA256(received_body, their_secret) == received_signature`.

**Why HMAC over asymmetric signatures?** HMAC is simpler and faster. The shared secret model works because each subscription has its own secret, and the secret is only shared with one customer. Asymmetric signatures (RSA) are overkill and add latency.

**Timestamp protection:** Include a `X-Webhook-Timestamp` header and incorporate it into the signature to prevent replay attacks. Customers should reject webhooks with timestamps older than 5 minutes.

### Per-Endpoint Rate Limiting
A single customer with a slow endpoint shouldn't consume all delivery worker capacity. Each subscription endpoint has a delivery rate limit (configurable, default 100 events/sec).

**Why per-endpoint, not per-customer?** A customer might have multiple endpoints with different capacities. Their production endpoint handles 1000/sec; their staging endpoint handles 10/sec.

**Implementation:** Token bucket per endpoint URL in Redis. Workers check before delivering. If the bucket is empty, the event is delayed (not dropped).

### Delivery Ordering Guarantees
Strict ordering (event 1 must be delivered before event 2) is extremely expensive in a distributed system because:
- It requires single-threaded delivery per subscription (kills throughput)
- If event 1 fails, all subsequent events are blocked

**Decision: Best-effort ordering with ordering keys.** Events with the same ordering key (e.g., `payment_id`) are routed to the same Kafka partition, ensuring in-order delivery by a single consumer. Different ordering keys can be delivered concurrently. If a delivery fails, subsequent events with the same key are paused until the retry succeeds or the event moves to DLQ.

## Component Breakdown

| Component | Role |
|---|---|
| **Event Producers** | Internal services (payment, order, user) that emit events |
| **Webhook Ingestion API** | Accepts events, validates schema, fans out to subscriptions, enqueues |
| **Subscription Manager** | CRUD for webhook subscriptions: URL, event types, secret, rate limit |
| **Delivery Queue (Kafka)** | Primary queue for first delivery attempts. Partitioned by ordering key |
| **Retry Queue (Delayed)** | Holds failed events until their next retry time. Implemented with SQS delay or custom scheduler |
| **Dead Letter Queue** | Events that exhausted all retries. Browsable, replayable, retained 30 days |
| **Delivery Workers** | Consume from queue, sign payload, POST to customer endpoint, log result |
| **Payload Signer** | Computes HMAC-SHA256 signature using the subscription's secret key |
| **Per-Endpoint Rate Limiter** | Token bucket in Redis, prevents overwhelming slow customer endpoints |
| **Customer Endpoints** | Third-party HTTP endpoints that receive webhook payloads |
| **Delivery Log (Cassandra)** | Every delivery attempt: timestamp, status_code, latency, response body snippet |
| **Subscription DB (PostgreSQL)** | Subscription definitions, secrets, event type filters, rate limit configs |

## Key Trade-offs

- **At-least-once vs exactly-once:** Webhooks are at-least-once by design. If the customer endpoint processes the event but the response is lost (network issue), we retry and they get a duplicate. Solution: include an idempotency key in every payload so customers can deduplicate
- **Timeout duration:** Too short (2 seconds) and you falsely retry healthy endpoints. Too long (30 seconds) and a single slow endpoint ties up a worker thread. **Decision: 10 second timeout** as default, configurable per subscription
- **Payload size limits:** Large payloads increase delivery time, queue size, and storage. **Decision: 256 KB max payload.** For larger data, include a URL in the payload that the customer can fetch (the "thin webhook" pattern)
- **Fan-out complexity:** One event might match 1000 subscriptions (e.g., `order.created` event). The ingestion API must fan this out efficiently. Batch enqueuing and partitioned Kafka handle this, but it creates write amplification (1 event becomes 1000 queue messages)

## What Fails First

**Customer endpoint reliability** is the dominant failure mode, and it's completely outside your control. When a popular customer's endpoint goes down:
- Retry queue fills up with events for that endpoint
- Worker threads are tied up waiting for timeouts on that endpoint
- Other customers' deliveries slow down due to resource contention

**Mitigation:**
- **Per-endpoint circuit breaker:** After 10 consecutive failures, stop trying for 5 minutes. Move events directly to retry queue without consuming worker threads
- **Isolated worker pools:** Partition workers by customer tier (enterprise customers get dedicated workers)
- **Short timeouts:** Don't let a single slow endpoint hog a worker for 30 seconds

**Secondary risk:** Kafka consumer lag during burst events. If a flash sale generates 10x normal events, the delivery queue grows faster than workers can process. Mitigation: auto-scale workers based on consumer lag.

## v1 vs v2

### v1 (Ship in 2 weeks)
- SQS as the delivery queue (simpler than Kafka, built-in retry/DLQ)
- Single retry policy: 3 retries with fixed 1-minute delay
- HMAC-SHA256 signing
- PostgreSQL for delivery log (fine at low scale)
- No per-endpoint rate limiting
- No ordering guarantees
- Dashboard showing delivery status per subscription

### v2 (Production-grade)
- Kafka for delivery queue with ordering key partitioning
- Configurable retry policy per subscription (up to 8 retries, exponential backoff with jitter)
- Sophisticated DLQ with manual replay, bulk retry, and auto-alerting
- Per-endpoint circuit breaker and rate limiter
- Delivery log in Cassandra for high-volume retention
- Event filtering: subscription-level rules (e.g., "only orders > $100")
- Webhook testing: "send test event" button in dashboard
- IP allowlisting: publish our egress IPs so customers can whitelist
- Mutual TLS support for enterprise customers
- Real-time delivery metrics dashboard (success rate, p99 latency, DLQ depth per subscription)
- Event replay by time range ("redeliver all events from the last 6 hours")
