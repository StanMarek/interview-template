# Payment Processing System -- Architecture Design

## Requirements

### Functional
- Accept payment requests from clients (credit card, debit, digital wallets)
- Process charges through external payment providers (Stripe, Adyen, Braintree)
- Support refunds, partial refunds, and chargebacks
- Maintain a double-entry ledger for all financial transactions
- Send payment confirmation/failure notifications
- Provide transaction history and status queries
- Support multiple currencies

### Non-Functional
- **Consistency:** Strong consistency for payment state transitions -- a payment must never be charged twice or lost
- **Availability:** 99.99% uptime target (52 minutes downtime/year)
- **Durability:** Zero data loss for financial records -- every transaction must be recoverable
- **Latency:** p99 < 2 seconds for payment processing
- **Idempotency:** Every payment request must be idempotent -- network retries must not cause double charges

## Scale Estimates
- 10,000 payment transactions per second at peak
- 500M transactions per month
- ~50TB of transaction data per year (with full audit trail)
- Single primary region with warm standby for disaster recovery

## Architecture Decisions

### Decision 1: Idempotency Key at the Gateway Layer

Every payment request carries a client-generated idempotency key. The API gateway checks this key in a Redis store BEFORE any downstream processing begins. If the key exists, the cached response is returned immediately.

**Why this matters:** Network partitions between client and server are the most common cause of duplicate payments. The client retries because it never received a response, but the server already processed the request. Without idempotency at the entry point, you are relying on the client to not retry -- which is not a reasonable assumption.

**Trade-off:** Redis as the idempotency store introduces a dependency. If Redis is down, we must fail-closed (reject the request) rather than fail-open (risk double charge). This is the correct trade-off for payments -- temporary unavailability is always preferable to financial incorrectness.

### Decision 2: Write-Ahead Log Before External Calls

Before calling any external payment provider, the orchestrator writes the intent to a WAL (write-ahead log). This creates a record of "we intend to charge $X" before the charge actually happens.

**Why this matters:** The most dangerous failure mode in payments is the "uncertainty window" -- you called Stripe, the network timed out, and you don't know if the charge went through. The WAL ensures that even if our process crashes, a recovery worker can pick up the intent and reconcile with the provider.

**Trade-off:** This adds latency (one extra disk write per payment). But for financial correctness, this is non-negotiable. The alternative is potential money loss.

### Decision 3: Saga Pattern for Multi-Step Payments

Complex payment flows (authorize -> capture -> update ledger -> notify) use the Saga pattern with compensating transactions. If step 3 fails, steps 1 and 2 are reversed.

**Why this matters:** Distributed transactions (2PC) don't work well across external providers -- you can't ask Stripe to participate in your two-phase commit. Sagas give us eventual consistency with explicit compensation logic.

**Trade-off:** Sagas require writing compensating transactions for every step, which increases code complexity. They also provide only eventual consistency -- there's a window where the system is in a partially completed state. For payments, this is acceptable because each step is individually idempotent and auditable.

### Decision 4: Circuit Breaker with Provider Failover

External provider calls go through a circuit breaker. When the primary provider (Stripe) fails repeatedly, the circuit opens and traffic is routed to the fallback provider (Braintree).

**Why this matters:** Payment providers have outages. Without a circuit breaker, you would keep sending requests to a dead provider, exhausting your thread pool and cascading the failure to your entire system. The circuit breaker pattern ensures graceful degradation.

**Trade-off:** Provider failover means you need to maintain integrations with multiple providers, which increases operational complexity. It also means a single payment might be split across providers, complicating reconciliation.

### Decision 5: PCI DSS Scope Reduction via Tokenization + 3DS2/SCA

Card data never touches our servers. The browser/mobile SDK collects PAN/CVV directly and exchanges it with the PSP (Stripe Elements, Adyen Web Components) for a network token or PSP token. We store only the token. For European/UK transactions and any in-scope card-present flows, we trigger 3D Secure 2 (3DS2) with frictionless authentication first, falling back to challenge flow when the issuer requires it -- this satisfies PSD2 Strong Customer Authentication (SCA) and shifts liability for fraudulent chargebacks to the issuer.

**Why this matters:** Holding raw PAN data puts us in PCI DSS Level 1 scope -- enormous audit cost, mandatory QSA assessments, network segmentation, and quarterly scans. Tokenization keeps us in SAQ A scope (the lightest tier). 3DS2 is mandatory for PSD2 markets; without it, the issuer rejects the charge or holds us liable for fraud. Network tokens (Visa/Mastercard tokenization) also auto-update on card reissuance, reducing involuntary churn.

**Trade-off:** 3DS2 challenge flow adds friction -- conversion drops 5-10% when challenged. The exemption logic (low-value, recurring, trusted beneficiary, transaction risk analysis) is complex and provider-specific. Network tokens require BIN-level support that is still rolling out across issuers.

## Consistency Model

**Strong consistency for payment state transitions.** A payment moves through states (PENDING -> AUTHORIZED -> CAPTURED -> SETTLED) and each transition is protected by optimistic locking on the payment record. The state machine is enforced at the database level with CHECK constraints.

**Why strong consistency:** This is a financial system. Eventual consistency for payment states would mean a customer could see "payment successful" while the actual charge is still in-flight, or worse, two concurrent requests could both think they are the first to process a payment.

We use PostgreSQL with serializable isolation for the ledger operations. This is slower than read-committed, but the correctness guarantee is worth it. The double-entry ledger ensures that debits and credits always balance -- if they don't, the transaction is rejected.

**Read replicas** are used for non-critical reads (transaction history, reporting) where a few seconds of staleness is acceptable. Critical reads (payment status during processing) always hit the primary.

## Failure Modes

### External provider timeout
The most common failure. The WAL records the intent, the circuit breaker tracks failures, and a reconciliation worker periodically checks with the provider to determine the actual outcome. The payment stays in PENDING until resolved.

### Database primary failure
PostgreSQL streaming replication with synchronous commit to at least one replica. On primary failure, automatic failover promotes the replica. The RPO is zero (no data loss). RTO is ~30 seconds.

### Idempotency store (Redis) failure
Fail-closed: reject all new payment requests. This causes temporary unavailability but prevents double charges. Redis Sentinel provides automatic failover within seconds.

### Payment orchestrator crash mid-saga
The saga coordinator persists step completion in the database. On restart, it resumes from the last completed step. Compensating transactions are triggered for any steps that were started but not confirmed.

### Network partition between services
All inter-service calls use timeouts with exponential backoff. The dead letter queue captures messages that cannot be delivered after max retries. A reconciliation worker processes the DLQ periodically.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **API Gateway** | Entry point, rate limiting, authentication, idempotency key check |
| **Idempotency Store (Redis)** | Stores idempotency keys with TTL (24-48 hours) to prevent duplicate processing |
| **Payment Orchestrator** | Coordinates the payment flow, manages state machine transitions |
| **Ledger Service** | Double-entry bookkeeping -- every debit has a corresponding credit |
| **Write-Ahead Log** | Records payment intent before external calls for crash recovery |
| **Primary DB (PostgreSQL)** | Source of truth for payment state, ledger entries, and saga state |
| **Read Replica** | Serves non-critical reads (history, reporting) |
| **Payment Provider** | External service that actually moves money (Stripe, Adyen) |
| **Fallback Provider** | Secondary provider for circuit breaker failover |
| **Circuit Breaker** | Prevents cascading failures from provider outages |
| **Saga Coordinator** | Manages multi-step payment flows with compensating transactions |
| **Notification Service** | Sends payment confirmations, failure alerts to customers |
| **Audit Log** | Immutable, append-only log of every payment event for compliance |
| **Dead Letter Queue** | Captures failed messages for manual review and retry |
| **Reconciliation Worker** | Periodic job that compares our records with provider records |
| **Monitoring & Alerting** | Tracks payment success rates, latency, error rates |

## Key Trade-offs

### Consistency vs. Availability
We choose **consistency** for payment state transitions. During a network partition, we would rather reject a payment (return 503) than risk processing it incorrectly. This is the "CP" side of CAP, and it is the correct choice for financial systems. The idempotency guarantee means the client can safely retry when we return 503.

### Latency vs. Durability
The WAL adds ~5ms per payment. Synchronous replication adds ~2ms. These are acceptable costs for zero data loss. We do NOT use async commit for financial data.

### Complexity vs. Correctness
The saga pattern, idempotency keys, WAL, reconciliation workers, and circuit breakers create a complex system. But each component exists to handle a specific failure mode. Removing any one of them opens a hole where money can be lost or duplicated.

## What Fails First

**The reconciliation worker becomes the bottleneck.** As payment volume grows, the number of "uncertain" payments (those where the provider response was ambiguous) grows proportionally. Each uncertain payment requires an API call to the provider to determine its status. At scale, this worker falls behind, creating a growing backlog of payments in PENDING state.

**Mitigation:** Partition the reconciliation work by payment provider and time window. Use multiple workers with leader election to prevent duplicate reconciliation.

## v1 vs v2

### v1 (Ship first)
- Single payment provider (no failover)
- Single-region deployment
- Basic idempotency with Redis
- Simple state machine (PENDING -> SUCCESS/FAILED)
- Manual reconciliation via admin dashboard
- PostgreSQL with async replication

### v2 (Scale and harden)
- Multi-provider with circuit breaker failover
- Multi-region active-passive deployment
- Saga pattern for complex flows (authorize/capture split)
- Double-entry ledger with automated reconciliation
- Synchronous replication with zero RPO
- Real-time fraud detection integration
- PCI DSS SAQ-A scope via PSP tokenization (no PAN ever touches our servers)
- 3D Secure 2 (3DS2) integration with SCA exemption engine for PSD2 markets
- Network tokens (Visa/Mastercard) for auto-updating credentials on reissuance
- Webhook delivery for merchant notifications (with HMAC signing + replay protection)
- Currency conversion service
