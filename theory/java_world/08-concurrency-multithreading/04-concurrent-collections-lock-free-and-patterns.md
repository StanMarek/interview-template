# Concurrent Collections, Lock-Free Tools, and Core Patterns

> Use this file for `ConcurrentHashMap`, `BlockingQueue`, atomics, ABA, false sharing, and bread-and-butter interview patterns.

## 1. Concurrent Collections

### ConcurrentHashMap

Since Java 8, `ConcurrentHashMap` is not segment-based. The old "segment lock" explanation is dated.

What to remember:

- reads are largely lock-free
- empty-bin insertions can use CAS
- contended updates synchronize at bucket/bin granularity
- resize is cooperative
- `null` keys and values are forbidden

Atomic compound operations:

```java
map.putIfAbsent("k", 1);
map.computeIfAbsent("k", k -> loadValue(k));
map.compute("k", (k, v) -> v == null ? 1 : v + 1);
map.merge("k", 1, Integer::sum);
```

Interview line: `get()` and `put()` are individually thread-safe, but `get()` followed by `put()` is still a race.

### CopyOnWriteArrayList

Use it when reads dominate and writes are rare:

- listener registries
- static-ish configuration snapshots

Do not use it for write-heavy paths.

### BlockingQueue Variants

| Queue | Use |
|------|-----|
| `ArrayBlockingQueue` | Bounded producer-consumer |
| `LinkedBlockingQueue` | High-throughput FIFO, optionally bounded |
| `PriorityBlockingQueue` | Priority ordering |
| `SynchronousQueue` | Direct handoff, zero capacity |
| `DelayQueue` | Delayed availability |
| `LinkedTransferQueue` | Transfer-oriented handoff / queue hybrid |

## 2. Lock-Free Basics

### CAS

CAS means "update only if the value is still what I expected."

That powers:

- atomics
- many non-blocking queues/stacks
- striped counters

### Atomics

```java
AtomicInteger counter = new AtomicInteger();
counter.incrementAndGet();
counter.compareAndSet(5, 6);

AtomicReference<Node> head = new AtomicReference<>();
```

### ABA

Classic issue:

- thread 1 sees `A`
- thread 2 changes `A -> B -> A`
- thread 1 CASes successfully, missing the fact that the value changed in between

Standard interview answer: `AtomicStampedReference`.

## 3. LongAdder vs AtomicLong

Use `LongAdder` under high write contention when exact point-in-time reads are less important than update throughput.

```java
LongAdder adder = new LongAdder();
adder.increment();
long total = adder.sum();
```

Use `AtomicLong` when:

- you need a single atomic numeric state
- exact reads after each update matter
- contention is modest

## 4. False Sharing and `@Contended`

False sharing is when independent hot fields land on the same cache line.

```java
import jdk.internal.vm.annotation.Contended;

final class Counters {
    @Contended volatile long producerCount;
    @Contended volatile long consumerCount;
}
```

Interview-safe wording:

- symptom: throughput collapses with more threads even though there is little logical contention
- fix: separate hot fields across cache lines, or use structures such as `LongAdder`

## 5. Common Patterns

### Producer-Consumer

```java
BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);

queue.put("item");
String item = queue.take();
```

For senior interviews, emphasize:

- bounded queue
- backpressure
- interruption handling
- poison pill or lifecycle-aware shutdown

### Read-Write Lock

`ReentrantReadWriteLock`:

- reentrant
- supports conditions on the write lock
- good when reads are frequent and writes are infrequent

`StampedLock`:

- optimistic reads
- not reentrant
- easier to misuse

### Singleton Patterns

Ranking for interviews:

1. enum singleton
2. initialization-on-demand holder
3. double-checked locking with `volatile`

### Object Pooling

Object pooling is usually for scarce resources, not ordinary heap objects.

Good examples:

- DB connections
- expensive native handles
- rate-limited downstream clients

Bad default habit:

- pooling tiny Java objects that the GC already handles well

## 6. `Flow` in One Paragraph

`java.util.concurrent.Flow` is the JDK's Reactive Streams SPI:

- `Publisher`
- `Subscriber`
- `Subscription`
- `Processor`

You almost never implement it directly in application code. In interviews, the correct framing is:

- use it as an interoperability SPI
- use reactive libraries when you need stream processing and backpressure
- do not claim Loom made reactive programming obsolete for true streaming pipelines

## 7. Interview Traps

- `ConcurrentHashMap.size()` is not a linearizable "exact now" answer during concurrent updates.
- `computeIfAbsent` mapping functions should not do blocking or recursively mutate the same map.
- `parallelStream()` on blocking I/O is a common-pool footgun.
- `StampedLock` is not reentrant; that is a feature and a trap.
