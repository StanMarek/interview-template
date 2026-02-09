# Message Brokers (Kafka, RabbitMQ)

## Apache Kafka

### What It Is
A distributed event streaming platform. It's an append-only, partitioned, replicated commit log that supports both message queuing and pub/sub patterns.

### Architecture
- **Topic**: A named feed/category of messages
- **Partition**: A topic is split into partitions for parallelism. Each partition is an ordered, immutable sequence of messages.
- **Broker**: A Kafka server. A cluster has multiple brokers.
- **Producer**: Publishes messages to a topic. Can specify partition key for ordering.
- **Consumer Group**: A group of consumers that together consume all partitions of a topic. Each partition is assigned to exactly one consumer in the group.
- **Offset**: Each message in a partition has a sequential offset. Consumers track their position by offset.

### Key Properties
- **Ordering**: Guaranteed within a partition (not across partitions)
- **Retention**: Time-based (default 7 days) or size-based. Messages are NOT deleted after consumption.
- **Replay**: Consumers can reset their offset and re-read messages
- **Throughput**: Millions of messages/sec on a single cluster
- **Durability**: Messages replicated across brokers (configurable replication factor)

### Consumer Groups
```
Topic: orders (3 partitions: P0, P1, P2)

Consumer Group A (3 consumers): C1→P0, C2→P1, C3→P2  (each partition → one consumer)
Consumer Group B (2 consumers): C4→P0+P1, C5→P2       (rebalancing when fewer consumers than partitions)
Consumer Group C (1 consumer):  C6→P0+P1+P2            (single consumer gets all)
```

Adding more consumers than partitions = idle consumers. Partitions are the unit of parallelism.

### When to Use Kafka
- High-throughput event streaming (clickstream, IoT, logs)
- Event sourcing and CDC (Change Data Capture)
- Stream processing (Kafka Streams, Flink, Spark Streaming)
- Decoupling producers and consumers at scale
- When you need message replay capability

## RabbitMQ

### What It Is
A traditional message broker implementing AMQP (Advanced Message Queuing Protocol). Focused on message routing, delivery guarantees, and flexible messaging patterns.

### Architecture
- **Exchange**: Receives messages from producers, routes to queues based on routing rules
- **Queue**: Stores messages until consumed
- **Binding**: Rule connecting an exchange to a queue
- **Consumer**: Pulls or receives pushed messages from a queue

### Exchange Types
| Type | Routing Logic |
|------|-------------|
| **Direct** | Route by exact routing key match |
| **Fanout** | Broadcast to all bound queues (pub/sub) |
| **Topic** | Route by pattern matching (`orders.*.created`) |
| **Headers** | Route by message header attributes |

### When to Use RabbitMQ
- Complex routing requirements (exchanges, bindings)
- Task queues with acknowledgments
- Request-reply patterns (RPC)
- Priority queues
- When you need per-message acknowledgment and redelivery
- Lower throughput, higher routing flexibility than Kafka

## Kafka vs RabbitMQ

| Feature | Kafka | RabbitMQ |
|---------|-------|----------|
| Model | Append-only log | Message broker |
| Throughput | Very high (millions msg/sec) | High (tens of thousands msg/sec) |
| Ordering | Per-partition | Per-queue |
| Retention | Configurable (days/weeks/forever) | Until consumed + acked |
| Replay | Yes (reset offset) | No (message deleted after ack) |
| Routing | Simple (topic + partition key) | Complex (exchanges, bindings, patterns) |
| Consumer model | Pull-based | Push or pull |
| Delivery | At-least-once (exactly-once with transactions) | At-most-once, at-least-once, or exactly-once |
| Use case | Event streaming, CDC, analytics | Task queues, RPC, complex routing |
| Protocol | Custom binary | AMQP, MQTT, STOMP |

## Other Message Brokers

| Broker | Notes |
|--------|-------|
| **Amazon SQS** | Managed queue. Simple, scalable, no ops. At-least-once. |
| **Amazon SNS** | Managed pub/sub. Fan-out to SQS, Lambda, HTTP. |
| **Google Pub/Sub** | Managed. Global, auto-scaling, at-least-once. |
| **Azure Service Bus** | Managed. Queues + topics, sessions, transactions. |
| **NATS** | Lightweight, high-performance. JetStream for persistence. |
| **Apache Pulsar** | Multi-tenant, geo-replication, tiered storage. Kafka alternative. |
| **Redis Streams** | Lightweight streaming within Redis. Good for simple use cases. |

## Possible Interview Questions
1. "When would you choose Kafka over RabbitMQ?"
2. "How does Kafka guarantee message ordering?"
3. "Explain consumer groups in Kafka."
4. "How would you handle a slow consumer that's falling behind?"
5. "Design a real-time analytics pipeline using Kafka."
6. "What happens when a Kafka broker goes down?"
7. "How would you implement exactly-once processing with Kafka?"
8. "Compare managed services (SQS, Google Pub/Sub) vs self-managed Kafka."
