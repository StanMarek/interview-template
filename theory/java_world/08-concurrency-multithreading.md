# Concurrency & Multithreading — Senior Engineer Interview Preparation

> **Reference point:** April 2026. JDK 25 is the current LTS (GA on 2025-09-16). JDK 26 is the latest GA feature release (2026-03-17). For Loom-specific status: virtual threads are final since JDK 21 (JEP 444), scoped values are final in JDK 25 (JEP 506), and structured concurrency is still preview in both JDK 25 (JEP 505) and JDK 26 (JEP 525).

This topic used to live in one oversized sheet. It is now split into smaller study notes so you can revise by topic instead of scrolling through one long file.

## Study Order

1. [Threads, Executors, and Pools](08-concurrency-multithreading/01-threads-executors-and-pools.md)
2. [CompletableFuture, Virtual Threads, and Structured Concurrency](08-concurrency-multithreading/02-completablefuture-virtual-threads-and-structured-concurrency.md)
3. [Synchronization and the Java Memory Model](08-concurrency-multithreading/03-scoped-values-synchronization-and-jmm.md)
4. [Concurrent Collections, Lock-Free Tools, and Core Patterns](08-concurrency-multithreading/04-concurrent-collections-lock-free-and-patterns.md)
5. [Debugging, Failure Modes, and Interview Drills](08-concurrency-multithreading/05-debugging-and-interview-drills.md)

## High-Yield Version Baseline

- `Thread.State` still has exactly six states: `NEW`, `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, `TERMINATED`.
- Virtual threads are standard Java threads now, not a preview feature.
- `synchronized` no longer causes routine virtual-thread pinning on JDK 24+; the old "replace every monitor with ReentrantLock" advice is stale.
- `ScopedValue` is final in JDK 25 and is the interview-safe answer for immutable request context under Loom.
- `StructuredTaskScope` is **not** final yet. In 2026 you should say "preview API" unless the interviewer explicitly constrains the discussion to an older release.

## How To Revise

- If the interview is JVM/backend-heavy: do files 1 -> 3 -> 5 first.
- If it is service-architecture heavy: do files 1 -> 2 -> 5 first.
- If you are short on time: skim files 2 and 5, then return to 3 for JMM / `volatile` / publication rules.

## Quick Traps

- `Executors.newFixedThreadPool(n)` uses an unbounded queue.
- `volatile` gives visibility and ordering, not atomic read-modify-write.
- `ConcurrentHashMap.get()` plus `put()` is still a race.
- Virtual threads help I/O-bound concurrency, not CPU-bound throughput.
- Structured concurrency is a better fit than `CompletableFuture` for "fan out, wait, cancel siblings on failure", but it still needs `--enable-preview`.
