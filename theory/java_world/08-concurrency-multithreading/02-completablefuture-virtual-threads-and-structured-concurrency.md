# CompletableFuture, Virtual Threads, and Structured Concurrency

> This file is the 2026 Loom-focused core: what changed, what is final, and what is still preview.

## 1. CompletableFuture

`CompletableFuture<T>` is for asynchronous composition. It is still important, but it is no longer the default answer for every concurrency question on modern Java.

```java
CompletableFuture<String> userFuture =
    CompletableFuture.supplyAsync(() -> fetchUserName());

CompletableFuture<Void> auditFuture =
    CompletableFuture.runAsync(() -> writeAuditLog());

CompletableFuture<Order> orderFuture =
    CompletableFuture.supplyAsync(() -> fetchUserId())
        .thenCompose(id -> fetchOrderAsync(id));

CompletableFuture<String> summary =
    userFuture.thenCombine(orderFuture, (user, order) -> user + ":" + order.id());
```

### Error handling

```java
CompletableFuture<String> robust =
    CompletableFuture.supplyAsync(() -> riskyCall())
        .exceptionally(ex -> "fallback");

CompletableFuture<String> observed =
    CompletableFuture.supplyAsync(() -> riskyCall())
        .whenComplete((result, ex) -> {
            if (ex != null) log.error("failed", ex);
        });
```

Key rule: non-async continuations may run on the thread that completes the previous stage, or on another caller that completes it. If thread choice matters, pass an executor explicitly.

### `allOf` / `anyOf`

```java
CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> "A");
CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> 42);

CompletableFuture<Void> all = CompletableFuture.allOf(a, b);
all.join();

String first = a.join();
Integer second = b.join();
```

The common mistake is forgetting that `allOf` returns `CompletableFuture<Void>`, so you still pull typed results out of the original futures.

## 2. Which Model To Pick?

| Need | Best default |
|------|--------------|
| Straight-line blocking I/O | Virtual threads |
| Fan-out / fan-in with shared cancellation | Structured concurrency |
| Pipeline-style async composition | `CompletableFuture` |
| Recursive CPU parallelism | Fork/Join |

Rule of thumb for new code on JDK 25/26:

- Use virtual threads for ordinary blocking work.
- Use `StructuredTaskScope` when a task owns multiple concurrent subtasks.
- Use `CompletableFuture` when you need true `CompletionStage` composition or interop with existing async APIs.

## 3. Virtual Threads

Virtual threads were previewed in JDK 19 (JEP 425), previewed again in JDK 20 (JEP 436), and finalized in JDK 21 (JEP 444).

### What matters in interviews

- They are still `java.lang.Thread`.
- They are cheap to create and cheap to block.
- They improve scalability for blocking I/O, not raw CPU speed.
- The scheduler uses platform threads underneath; by default target parallelism is around `availableProcessors()`.

```java
Thread vt = Thread.ofVirtual().start(() -> {
    System.out.println(Thread.currentThread().isVirtual());
});
```

### When to use them

| Workload | Virtual threads |
|---------|-----------------|
| HTTP / DB / filesystem blocking I/O | Excellent |
| Huge numbers of mostly-waiting tasks | Excellent |
| CPU-bound loops | Usually no benefit |
| ThreadLocal-heavy mutable caches | Often a poor fit |

### Pinning: what changed

This is the big stale-claim area in older notes.

- JDK 21 to 23: blocking in `synchronized` code could pin the virtual thread to its carrier.
- JDK 24: JEP 491 changed monitor handling so virtual threads can block in `synchronized` code and `Object.wait()` without routine carrier pinning.
- JDK 25 and 26 inherit that fix.

What can still pin or otherwise tie up carriers?

- Native / JNI / VM frames on stack.
- Class initialization waits.
- Other non-cooperative blocking below the Java-level scheduler boundary.

Diagnostics:

- `-Djdk.tracePinnedThreads=short`
- `-Djdk.tracePinnedThreads=full`
- JFR event `jdk.VirtualThreadPinned`
- `VirtualThreadSchedulerMXBean` (since JDK 24) for scheduler visibility

## 4. Structured Concurrency

Structured concurrency is still preview as of April 2026.

| Release | Status | JEP |
|--------|--------|-----|
| JDK 21 | 1st preview | JEP 453 |
| JDK 22 | 2nd preview | JEP 462 |
| JDK 23 | 3rd preview | JEP 480 |
| JDK 24 | 4th preview | JEP 499 |
| JDK 25 | 5th preview | JEP 505 |
| JDK 26 | 6th preview | JEP 525 |

That means the old monolithic sheet was wrong where it swapped the JDK 24 and JDK 25 JEP numbers.

### Why it matters

It models "a task with child subtasks" directly:

- sibling cancellation is automatic
- task lifetime is lexically scoped
- failure handling is simpler than manual `Future` plumbing
- observability is better in thread dumps

### JDK 25 API shape

For the current LTS, the important shape is factory-based:

```java
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;

record UserProfile(User user, List<Order> orders) {}

UserProfile fetchProfile(long userId) throws Exception {
    try (var scope = StructuredTaskScope.open()) {
        var userTask = scope.fork(() -> userService.getUser(userId));
        var orderTask = scope.fork(() -> orderService.getOrders(userId));

        scope.join();
        return new UserProfile(userTask.get(), orderTask.get());
    }
}
```

Useful joiners in JDK 25:

- `Joiner.awaitAllSuccessfulOrThrow()` for mixed result types
- `Joiner.allSuccessfulOrThrow()` for same-type subtasks
- `Joiner.anySuccessfulResultOrThrow()` for racing / hedging
- `Joiner.awaitAll()` when you need every subtask to finish and will inspect outcome yourself

Timeout / configuration example:

```java
try (var scope = StructuredTaskScope.open(
        Joiner.<String>allSuccessfulOrThrow(),
        cfg -> cfg.withTimeout(Duration.ofSeconds(2)))) {
    scope.fork(() -> callA());
    scope.fork(() -> callB());
    var subtasks = scope.join();
    return subtasks.map(StructuredTaskScope.Subtask::get).toList();
}
```

Interview-safe wording in 2026:

> "Structured concurrency is available as a preview API. On JDK 25 the important API shape is `StructuredTaskScope.open(...)`, not the old constructor-based API from earlier previews."

If the interviewer brings up JDK 26, acknowledge that JEP 525 keeps the feature in preview and adjusts the API again.

## 5. Scoped Values

`ScopedValue` finalized in JDK 25 (JEP 506).

History:

- JDK 20: incubator (JEP 429)
- JDK 21 to 24: preview rounds
- JDK 25: final

That means "five previews from 20 to 24" was wrong. JDK 20 was incubation, not preview.

```java
static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

String render(String id) throws Exception {
    return ScopedValue.where(REQUEST_ID, id)
        .call(() -> "request=" + REQUEST_ID.get());
}
```

Why interviewers care:

- immutable binding
- lexical lifetime
- automatic cleanup
- natural fit with virtual threads and structured concurrency

When it beats `ThreadLocal`:

- request-scoped identity
- trace IDs
- auth principal
- locale / tenant / correlation metadata

When `ThreadLocal` is still fine:

- mutable per-thread caches
- thread-confined reusable objects
- code that genuinely depends on thread lifetime rather than operation scope

## 6. One-Sentence Defaults

- Blocking I/O service on modern Java: virtual threads first.
- Fan-out request to multiple downstreams: structured concurrency if preview is acceptable, otherwise fall back to futures/executors.
- Async composition pipeline: `CompletableFuture`.
- Request context under Loom: `ScopedValue`, not `InheritableThreadLocal`.
