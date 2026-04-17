# Streaming Semantics

## What It Is
The rules that define what "a message was processed" actually means in a streaming system: how many times it can be delivered, whether duplicates are possible, how time is measured, and how late data is handled. Most production incidents in streaming pipelines trace back to someone assuming the wrong semantic.

## Why It Matters
"Exactly-once" is the most misunderstood term in distributed systems. Interviewers love to pull on this thread because it separates people who have read a blog post from people who have run a pipeline.

## Delivery Semantics

### At-Most-Once
The broker sends the message; if delivery fails, it is lost. No retries. Useful only when losing data is cheaper than processing it twice (some metrics, non-critical telemetry). Essentially "fire and forget".

### At-Least-Once
The broker retries until the consumer acknowledges. If the ack is lost, the message is redelivered — so the consumer can see the same message more than once. This is the **default** for Kafka, SQS, RabbitMQ, Kinesis, and almost every real broker.

Consequence: your consumer **must** be idempotent or duplicates will corrupt state. See [idempotency.md](idempotency.md).

### Exactly-Once (The Myth)
"Exactly-once **delivery**" end-to-end is impossible in an asynchronous network with crash failures — it's a theorem, not an engineering limitation. What's actually possible:

- **Exactly-once processing** within a closed system (Kafka Streams / Flink with Kafka transactions): the offset commit and the output write are in the same atomic transaction, so a retry doesn't double-commit.
- **Effectively-once** end-to-end: at-least-once delivery + idempotent consumer = the user-visible effect is exactly-once.

If an interviewer asks "how do you get exactly-once?", the correct answer starts with "we get at-least-once delivery and make the consumer idempotent via `{idempotency key | deduplication | conditional writes}`." Kafka's "exactly-once" is a specific, bounded feature inside the Kafka ecosystem, not a magic guarantee across arbitrary sinks.

### Kafka EOS (Exactly-Once Semantics) — what it actually covers
- **Idempotent producer** (`enable.idempotence=true`) — the broker deduplicates retries based on producer ID + sequence number, so a retry never appends twice to a partition.
- **Transactions** — a producer can atomically write to multiple partitions **and** commit consumer offsets in the same transaction. Downstream consumers using `isolation.level=read_committed` never see uncommitted records.
- Works for Kafka → Kafka pipelines. For Kafka → external DB, you still need idempotent writes on the DB side (upsert on primary key, conditional update, etc).

## Time: Event Time vs Processing Time

| Time type        | Definition                                           | Use for                                    |
|------------------|------------------------------------------------------|--------------------------------------------|
| Event time       | When the event actually happened (in the data)       | Analytics, billing, anything user-facing   |
| Ingestion time   | When the broker received the event                   | Ops debugging                              |
| Processing time  | When the stream processor handled the event          | Simple cases with no late data             |

Event time is what you almost always want, but it introduces a new problem: events arrive out of order and late.

## Watermarks

A **watermark** T asserts "no more events with timestamp ≤ T expected". Windows close at their end-time relative to the watermark. **Allowed Lateness** is a SEPARATE mechanism — additional tolerance window for late events after the watermark passes the window end.

- Too aggressive (tight watermark) → late events are dropped or sent to a late-data side output.
- Too conservative (loose watermark) → results lag further behind real time.

Flink and Beam make watermarks explicit. Spark Structured Streaming and Kafka Streams have simpler models (fixed lateness bound). Watermarks are per-source and propagate through the DAG; a slow source holds back the entire pipeline.

## Windowing

Windows let you aggregate unbounded streams into bounded chunks.

### Tumbling Windows
Fixed-size, non-overlapping.
```
| 10:00-10:05 | 10:05-10:10 | 10:10-10:15 |
```
Use for: "count per minute", "revenue per hour". Simplest and most common.

### Sliding (Hopping) Windows
Fixed-size, but overlap by a slide interval.
```
| 10:00-10:05 |
    | 10:01-10:06 |
        | 10:02-10:07 |
```
Use for: "requests in the last 5 minutes, updated every minute". More compute, each event lands in multiple windows.

### Session Windows
Dynamic size. A window closes when there's a gap of `T` with no events for that key.
```
user A: [click, click, click]............[click, click]
        └─── session 1 ────┘             └── session 2 ──┘
```
Use for: user activity sessions, "time spent on page", detecting idle periods.

### Global Windows + Triggers
A single window for all data; custom triggers decide when to emit. Flexible but easy to get wrong.

## Late Data

Late = event arrives after the watermark for its window has passed.

Options:
1. **Drop** — default, simplest, lossy. OK for low-value telemetry.
2. **Side output** / **dead-letter stream** — route late events to a separate sink for backfill.
3. **Allowed lateness** — keep windows open for `L` extra time after the watermark; re-emit updated results. Flink and Beam support this natively. Downstream consumers must handle **updates** to previously emitted results.
4. **Reprocess from the log** — Lambda / Kappa style: re-run the batch over the full log with the correct timestamps. Fixes everything eventually, costs a full replay.

## Ordering

Brokers typically guarantee ordering **per partition/key**, not globally.
- Kafka: order preserved within a partition. Choose your partition key carefully (user ID? account ID?).
- Kinesis: order within a shard.
- SQS FIFO: order within a message group.
- Global ordering across keys requires a single partition, which caps throughput — rarely worth it.

A consumer that parallelizes processing within a partition (threads per message) **breaks ordering**. Single-threaded per partition, or partition-aware thread pools keyed by the same key.

## Backpressure

Fast producer + slow consumer = unbounded buffer growth = OOM. Solutions:
- **Blocking producers** — the broker/queue pushes back (Kafka producer's `buffer.memory` fills, then blocks).
- **Consumer lag metrics + autoscaling** — scale the consumer group when `lag > threshold`.
- **Shedding** — drop low-priority messages at the edge (rate limiter, sampler).
- **Reactive pull models** — consumer requests N messages at a time; natural backpressure (RabbitMQ `prefetch`, Reactor `request(n)`).

## Real Systems

| System          | Delivery default | Exactly-once support                         | Ordering       | Notes                                  |
|-----------------|------------------|----------------------------------------------|----------------|----------------------------------------|
| Kafka           | at-least-once    | EOS within Kafka (idempotent + transactions) | per partition  | The reference streaming log            |
| Kinesis         | at-least-once    | no native EOS; do it at consumer             | per shard      |                                        |
| RabbitMQ        | at-least-once    | no                                           | per queue      | Ack-based, prefetch for backpressure    |
| SQS standard    | at-least-once    | no                                           | none           | Possible duplicates always              |
| SQS FIFO        | exactly-once delivery (dedup window, 5 min) | yes within window | per message group |                                   |
| Pulsar          | at-least-once    | yes (transactions, effective-once)           | per partition  |                                        |
| Flink           | — (processor)    | exactly-once processing via checkpoints      | —              | With Kafka source + checkpoint sink     |
| Kafka Streams   | — (processor)    | EOS v2 via transactions                      | —              |                                        |
| Spark Structured Streaming | — (processor) | exactly-once natively via checkpointing + idempotent sinks | — | Comparable guarantees to Flink for supported sinks |

## Design Patterns

### Idempotent Consumer
Most common and flexible. Each event has a stable ID; consumer upserts by that ID or checks a deduplication table. Pairs well with at-least-once delivery.

### Transactional Outbox + CDC
Write to DB and outbox table in one transaction; a separate process publishes the outbox to Kafka. Avoids dual-writes. See [outbox-pattern.md](outbox-pattern.md).

### Log Compaction
Kafka compacted topics keep only the latest value per key — great for materialized views, config, and KTables.

### CDC (Change Data Capture)
Stream DB changes (Debezium, Postgres logical replication). Gives you an ordered, at-least-once stream of row changes; downstream consumers must still be idempotent.

## Common Pitfalls and Red Flags
- Claiming "exactly-once" without naming the mechanism that makes it so.
- Using processing time for windowed aggregations on a global pipeline with late data.
- Parallelizing a partition's consumer and then being surprised ordering is gone.
- No dead-letter topic / no DLQ plan — a single poison message stalls a partition forever.
- Ignoring consumer lag in the monitoring story.
- "We'll just store the last-processed offset in our app DB" — then crashing between the side effect and the offset commit, so you either double-process or lose data. Transactions or idempotent sinks fix this.
- Using SQS standard for ordered or deduplicated workloads.

## Possible Interview Questions
1. What does "exactly-once" mean in Kafka? What guarantees does it actually provide?
2. Why is exactly-once delivery impossible end-to-end?
3. Design an ad-click aggregation pipeline. How do you prevent double-counting clicks?
4. Explain event time vs processing time. When does the difference matter?
5. What is a watermark? What happens when one is too aggressive?
6. A user's click arrives 10 minutes late. Show me three different ways to handle it.
7. How do you handle backpressure when consumers can't keep up?
8. A single partition has a poison message. What happens and what do you do?
9. When would you choose session windows over tumbling windows?
10. How does Kafka guarantee ordering, and how do consumers break it?

## Related
- [idempotency.md](idempotency.md) — the mandatory companion to at-least-once delivery.
- [message-queues.md](message-queues.md) — broker-level semantics and patterns.
- [pub-sub.md](pub-sub.md) — fan-out patterns.
- [outbox-pattern.md](outbox-pattern.md) — reliable publishing from a transactional source.
