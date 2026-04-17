# Message Queues & Event-Driven Architecture

## What It Is
A message queue is an asynchronous communication mechanism where producers send messages to a queue, and consumers process them independently. Event-driven architecture (EDA) is a broader paradigm where components communicate by producing and consuming events.

## Why It Matters
Decoupling producers from consumers is fundamental to scalable, resilient systems. Nearly every system design interview involves some form of async processing.

## Core Concepts

### Message Queue vs Event Stream
| Feature | Message Queue (RabbitMQ, SQS) | Event Stream (Kafka, Kinesis) |
|---------|------|------|
| Consumption | Message consumed once then deleted | Events retained, multiple consumers can replay |
| Ordering | Per-queue (approximately) | Per-partition (strict) |
| Consumer model | Competing consumers (load distribution) | Consumer groups (each group gets all messages) |
| Retention | Until consumed | Time or size-based (days to forever) |
| Use case | Task queues, job processing | Event sourcing, analytics, CDC |

### Delivery Guarantees
| Guarantee | Meaning | Trade-off |
|-----------|---------|-----------|
| **At-most-once** | Message may be lost, never duplicated | Fastest, least reliable |
| **At-least-once** | Message never lost, but may be duplicated | Most common; requires idempotent consumers |
| **Exactly-once** | Message processed exactly once | Hardest to achieve; typically requires transactions + deduplication |

In practice, **at-least-once + idempotent consumers** is the standard approach.

## RabbitMQ Exchanges
Exchanges route messages to queues:
- **direct** — exact routing key match
- **topic** — wildcard routing key match (`*` = one word, `#` = zero or more)
- **fanout** — broadcast to all bound queues
- **headers** — match on header values

Exchange type is the primary design lever.

## SQS Limits
- **SQS Standard**: 256 KB max message size, 14-day max retention, visibility timeout 30s default.
- **SQS FIFO**: 300 TPS without batching, 3000 TPS with batching.
- Both support DLQ via redrive policy.

## Kafka Consumer Group Mapping
Within a consumer group: one partition → at most one consumer. More consumers than partitions = idle consumers. Repartitioning breaks key → partition mapping (critical gotcha).

## Key Patterns

### Work Queue (Competing Consumers)
Multiple consumers pull from the same queue. Each message is processed by exactly one consumer. Load is distributed.
- Use case: Image processing, email sending, order fulfillment

### Fan-Out
One message is delivered to multiple queues/consumers (via topic exchange or SNS).
- Use case: Order placed → notify inventory service AND billing service AND email service

### Dead Letter Queue (DLQ)
Messages that fail processing after N retries are moved to a DLQ for investigation.
- **Critical**: Always configure a DLQ. Without one, poison messages loop forever.

### Priority Queue
Messages with higher priority are consumed first.
- Implementation: Multiple queues with consumers prioritizing the high-priority queue.

### Delayed/Scheduled Messages
Message becomes available for consumption only after a specified delay.
- Use case: "Send reminder email 24 hours after signup"

## Backpressure
When consumers can't keep up with producers, the queue grows. Strategies:
- **Queue size limits**: Reject/block producers when queue is full
- **Consumer autoscaling**: Spin up more consumers when queue depth increases
- **Rate limiting producers**: Throttle at the source
- **Dropping messages**: For non-critical telemetry data

## Ordering Guarantees
- **Global ordering**: All messages in order. Single queue/partition. Doesn't scale.
- **Partition ordering**: Messages with the same key are ordered. Different keys can be parallel. Standard for Kafka.
- **No ordering**: Maximum parallelism. Consumer must handle out-of-order.

## Idempotency with Message Queues
Since at-least-once delivery means duplicates, consumers must be idempotent:
- Use a **deduplication table**: Store processed message IDs, skip duplicates
- Design operations to be naturally idempotent: `SET balance = 100` is idempotent; `ADD 10 to balance` is not
- **Kafka transactions + exactly-once semantics (EOS)**: Kafka supports effectively-exactly-once for `consume → process → produce` pipelines via transactional producers and `read_committed` consumers. It does NOT extend to arbitrary external side effects (e.g., HTTP calls, non-Kafka DB writes) — for those, combine idempotency keys with the Outbox pattern.

## Reliable Publishing: The Outbox Pattern
A common bug: service commits to its DB, then tries to publish to Kafka/RabbitMQ — but the broker is unreachable and the event is lost (dual-write problem). Solution: write the event to an `outbox` table in the same DB transaction. A relay process (poller or Debezium CDC on the WAL) publishes outbox rows to the broker. See [outbox-pattern.md](outbox-pattern.md).

## Poison Messages
A message that causes the consumer to crash every time it tries to process it. Without handling, the message is retried infinitely. Solution: DLQ after N retries + monitoring/alerting on DLQ depth.

## Possible Interview Questions
1. "How would you design a notification system that sends emails, push, and SMS?"
2. "What happens if a consumer crashes mid-processing? How do you prevent message loss?"
3. "How do you handle messages that always fail processing?"
4. "When would you use a message queue vs a direct HTTP call?"
5. "How do you ensure ordering in a distributed message queue?"
6. "Explain the difference between a message queue and an event stream."
7. "Your queue depth is growing faster than consumers can process. What do you do?"
8. "How would you design a job scheduling system using queues?"
