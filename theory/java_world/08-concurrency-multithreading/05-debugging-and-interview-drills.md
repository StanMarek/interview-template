# Debugging, Failure Modes, and Interview Drills

> This file keeps the active-recall and debugging material in one place so the theory files can stay shorter.

## 1. Failure Modes

### Deadlock

All four Coffman conditions must hold:

1. mutual exclusion
2. hold and wait
3. no preemption
4. circular wait

Detection:

```java
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] deadlocked = bean.findDeadlockedThreads();
```

Operationally:

- `jstack <pid>`
- `kill -3 <pid>`
- thread-dump footer: `Found one Java-level deadlock`

### Livelock

Threads are active but make no progress because they keep reacting to each other.

Fix:

- randomized backoff
- tie-breakers
- bounded retries

### Starvation

One thread never gets a turn.

Common causes:

- unfair contention
- huge tasks in shared pools
- priority abuse

## 2. Debugging Checklist

### Deadlock

- Look for `BLOCKED` threads waiting on monitors held by each other.
- Confirm lock order inversion.

### Lost wake-up

- Check for `if` instead of `while` around `wait()`.
- Check for `notify()` where multiple predicates share a wait set.

### Virtual-thread pinning

On JDK 21 to 23:

- monitor-based blocking in `synchronized` could pin
- `jdk.VirtualThreadPinned` was a strong hint

On JDK 24+:

- monitor-based pinning should largely disappear
- remaining pinned stacks usually point to native / VM / class-init situations

### False sharing

- throughput gets worse as threads increase
- CPU is busy but useful work stalls
- look for adjacent hot fields

## 3. Must Know

- six `Thread.State` values
- happens-before edges
- `volatile` vs atomic classes
- `synchronized` vs `ReentrantLock`
- `ConcurrentHashMap` compound operations
- virtual threads for blocking I/O, not CPU throughput
- structured concurrency is still preview
- scoped values are final in JDK 25
- JDK 24 fixed routine monitor-based virtual-thread pinning

## 4. Common Traps

1. Shared `SimpleDateFormat`
2. Double-checked locking without `volatile`
3. Swallowing `InterruptedException`
4. `notify()` on a monitor with multiple predicates
5. Unbounded executor queues
6. Pooling virtual threads
7. `parallelStream()` for blocking I/O
8. `synchronized(StringLiteral)` or other globally shared lock objects

## 5. Two-Minute Oral Drill

Answer each in one sentence.

1. What does `volatile` guarantee?
2. Why is `ConcurrentHashMap.get()` plus `put()` unsafe?
3. What changed in JDK 24 for virtual threads and monitors?
4. When is `LongAdder` better than `AtomicLong`?
5. Why is `StructuredTaskScope` not a "finished" API answer yet?
6. When would you keep `ThreadLocal` instead of moving to `ScopedValue`?
7. Why is `Executors.newFixedThreadPool` a production trap?
8. What does `StampedLock` buy you, and what does it cost?

## 6. Whiteboard / Coding Drill

Implement from a blank file:

1. Bounded blocking queue with `ReentrantLock` and two `Condition`s
2. Simple read-write lock
3. Token-bucket limiter
4. `CompletableFuture` batch fan-out helper
5. Thread-safe LRU cache

## 7. Timed Mock

30-minute pass:

1. Draw the six thread states and key transitions.
2. Explain happens-before using `volatile`, monitor exit/enter, and `start`/`join`.
3. Design an I/O-bound service on JDK 25 or 26 using virtual threads, bounded downstream concurrency, and a separate CPU pool.
4. Diagnose a stuck JVM from a thread dump.
5. Explain why structured concurrency is attractive, then state clearly that it is still preview.

## 8. Review Checklist

- [ ] Can I explain the difference between runnable Java state and actual CPU execution?
- [ ] Can I give three happens-before edges without looking?
- [ ] Can I explain why `count++` on a `volatile` field is still broken?
- [ ] Can I explain the JDK 24 virtual-thread pinning change precisely?
- [ ] Can I choose among `CountDownLatch`, `CyclicBarrier`, `Phaser`, and `Semaphore` quickly?
- [ ] Can I explain why `StructuredTaskScope` is lexically scoped and why that matters?
- [ ] Can I choose between `ThreadLocal` and `ScopedValue` in one sentence?
- [ ] Can I explain why backpressure beats unbounded queueing?
