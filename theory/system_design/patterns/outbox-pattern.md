# Transactional Outbox Pattern

## What It Is
The Outbox Pattern reliably publishes events/messages from a service whose source of truth is a database. Instead of writing to the DB and then publishing to a broker (two independent systems), the service writes the event to an **outbox table** in the **same DB transaction** as the business data. A separate relay process reads the outbox and publishes to Kafka/RabbitMQ/SNS.

## Why It Matters — The Dual-Write Problem
A naive microservice flow:

```
1. BEGIN TX
2. INSERT INTO orders ...
3. COMMIT
4. kafka.publish("OrderCreated", ...)   ← network call, can fail
```

If step 4 fails (broker down, network partition, pod crash between 3 and 4), the order exists in the DB but downstream services never hear about it. Conversely, publishing before commit risks publishing a rollback. There is **no way** to atomically write to two separate systems without a distributed transaction — and 2PC is too heavyweight (see [saga-pattern.md](saga-pattern.md)).

The Outbox Pattern sidesteps this by making the event part of the single local transaction.

## How It Works

```
┌──────────────────────────────────┐
│  Service DB                      │
│                                  │
│  ┌──────────┐   ┌─────────────┐ │
│  │ orders   │   │ outbox      │ │   Same transaction
│  │          │   │             │ │   ── writes both or
│  │ INSERT   │   │ INSERT evt  │ │      neither
│  └──────────┘   └─────────────┘ │
│                        │         │
└────────────────────────┼─────────┘
                         │
                    ┌────▼─────┐
                    │  Relay   │  (poller OR Debezium CDC)
                    └────┬─────┘
                         │
                    ┌────▼─────┐
                    │  Kafka   │  ← at-least-once delivery
                    └──────────┘
```

### Outbox Table Schema

```sql
CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,      -- e.g. order_id, used as Kafka key
    event_type   VARCHAR(100) NOT NULL,      -- "OrderCreated", "PaymentCaptured"
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  DEFAULT NOW(),
    published_at TIMESTAMPTZ                  -- NULL until published (polling only)
);
CREATE INDEX idx_outbox_unpublished ON outbox(id) WHERE published_at IS NULL;
```

## Relay Strategies

### 1. Polling Publisher
A background worker periodically queries unpublished rows and forwards them to the broker.

```sql
SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED;
-- publish to Kafka
UPDATE outbox SET published_at = NOW() WHERE id IN (...);
```

- **Pros**: Trivial to deploy, no extra infra.
- **Cons**: Polling latency (~100ms to seconds); DB load scales with poll rate.
- **Use when**: Small/medium scale, no real-time latency requirement.

### 2. Change Data Capture (CDC) — Debezium
Debezium reads the database's write-ahead log (PostgreSQL logical replication, MySQL binlog) and streams changes directly to Kafka. No polling.

- **Pros**: Near-zero latency, zero load on the application, works with existing tables.
- **Cons**: Operational complexity (Kafka Connect, replication slots, schema registry), WAL retention tuning, lag monitoring.
- **Use when**: High scale, latency-sensitive, existing Kafka Connect infrastructure.

Debezium supports an **Outbox Event Router** SMT that extracts the event payload and routes by `event_type`, making the pattern first-class.

## Guarantees

| Property | Guarantee |
|----------|-----------|
| Atomicity of DB state + event | **Yes** (single local TX) |
| Delivery to broker | At-least-once (duplicates possible on relay crash) |
| Ordering | Per-aggregate (use `aggregate_id` as Kafka partition key) |
| Exactly-once processing | Not automatic — consumers must be idempotent |

## Cleanup
The outbox table grows forever if unattended. Strategies:
- **TRUNCATE after publish**: Polling relay deletes rows on successful publish. Simplest.
- **TTL/cron**: `DELETE FROM outbox WHERE created_at < NOW() - INTERVAL '7 days'` (keep for replay debugging).
- **Partitioning**: Daily partitions, drop old partitions.

## Inbox Pattern (The Consumer Side)
Analogous concept for the consumer: record every processed event's ID in an `inbox` table within the same transaction that applies the side effect. Duplicate events are no-ops. Provides effectively-exactly-once processing.

## Variants & Alternatives
- **Listen-to-yourself**: Publish the event, and the service subscribes to its own topic to update its DB. Inverts the trust direction but has its own dual-write issue.
- **Event Sourcing**: The event store IS the source of truth; no separate outbox needed. See [cqrs-event-sourcing.md](cqrs-event-sourcing.md).
- **Kafka Transactions**: If the producer is consuming from Kafka AND producing to Kafka with no DB side effects, use Kafka's built-in transactional producer + `read_committed` consumer for exactly-once within Kafka.

## When NOT to Use It
- The event is fire-and-forget telemetry (loss acceptable).
- You use event sourcing natively (events are your data).
- All writes go through a single system that already provides transactional messaging (e.g., Kafka Streams processor using Kafka as both source and sink).

## Possible Interview Questions
1. "You update a database and publish a Kafka event. How do you guarantee both happen or neither?"
2. "What's the dual-write problem and how does the outbox pattern solve it?"
3. "Compare polling vs CDC for the outbox relay."
4. "How do you prevent duplicate event processing with the outbox pattern?"
5. "Your outbox table has 500 million rows. How do you keep it healthy?"
6. "Design reliable event publishing for an order service using PostgreSQL and Kafka."
