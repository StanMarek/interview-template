# Pub/Sub Pattern

## What It Is
Publish/Subscribe (Pub/Sub) is a messaging pattern where publishers send messages to a **topic** (not to specific subscribers), and all subscribers to that topic receive a copy of the message. Publishers and subscribers are completely decoupled — they don't know about each other.

## Pub/Sub vs Message Queue

| Feature | Pub/Sub | Message Queue |
|---------|---------|---------------|
| Delivery | Each subscriber gets a copy | Each message goes to one consumer |
| Pattern | Fan-out (1-to-many) | Load distribution (1-to-1) |
| Use case | Event notification, broadcasting | Task processing, work distribution |
| Example | "Order placed" → notify 5 services | "Process image" → one worker picks it up |

Many systems combine both: Kafka consumer groups are Pub/Sub (between groups) + Queue (within a group).

## Core Components
- **Publisher**: Produces messages, knows nothing about subscribers
- **Topic/Channel**: Named category for messages
- **Subscriber**: Registers interest in topics, receives matching messages
- **Message Broker**: Infrastructure that manages topics and routes messages

## Delivery Models

### At-Most-Once
Fire and forget. Message may be lost. Fastest.

### At-Least-Once
Message guaranteed to be delivered but may be duplicated. Subscribers must be idempotent.

### Exactly-Once (Effectively)
Achieved through idempotent processing + deduplication. Not truly "exactly once" at the network level.

## Filtering
Subscribers may not want every message on a topic. Filtering options:
- **Topic-based**: Subscribe to specific topics (`orders.created`, `orders.cancelled`)
- **Content-based**: Filter by message attributes (e.g., `region=EU`)
- **Hierarchical topics**: `orders.region.eu.created` — subscribe at any level

## Fan-Out Patterns

### Simple Fan-Out
One topic, multiple subscribers. Each gets every message.
- Example: Order placed → Inventory Service, Email Service, Analytics Service

### Fan-Out + Filter
Each subscriber has a filter. Only receives matching messages.
- Example: Payment events → Fraud service only gets events > $10,000

## Ordering and Partitioning
- **Global ordering**: All subscribers see messages in the same order. Limits throughput.
- **Partition ordering**: Messages with the same key (e.g., user_id) are ordered. Different keys can be parallel.
- **No ordering**: Maximum throughput. Subscribers handle out-of-order.

## Backpressure in Pub/Sub
If a subscriber can't keep up:
- **Buffering**: Broker buffers messages (risks OOM)
- **Dropping**: Discard oldest messages
- **Flow control**: Slow down the publisher
- **Subscriber scaling**: Auto-scale consumer instances

## Real-World Implementations

| System | Type | Notes |
|--------|------|-------|
| Apache Kafka | Distributed event streaming | Topics with partitions, consumer groups. **Kafka 4.0 (2025) removed ZooKeeper** — KRaft (Raft-based metadata) is the only supported mode |
| Google Pub/Sub | Managed pub/sub | At-least-once, push and pull delivery |
| AWS SNS | Managed pub/sub | Fan-out to SQS, Lambda, HTTP, email |
| Redis Pub/Sub | In-memory | Fire and forget (no persistence) |
| RabbitMQ | Message broker | Exchange types: fanout, topic, direct |
| NATS | Lightweight messaging | JetStream for persistence |

## Possible Interview Questions
1. "How would you notify 10 different services when a user signs up?"
2. "What's the difference between Pub/Sub and a message queue?"
3. "A subscriber is down for 2 hours. What happens to its messages?"
4. "How do you ensure message ordering in a Pub/Sub system?"
5. "Design a real-time notification system using Pub/Sub."
