# Schedulers, Backpressure, and Context

> **Goal:** Understand where work runs, how Reactor controls demand, and how request-scoped data is propagated without relying on `ThreadLocal`.

---

## 1. Reactor Is Concurrency-Agnostic By Default

Reactor does not automatically make work concurrent. Unless you introduce a scheduler boundary, operators run on the thread that is currently delivering signals.

That is why thread questions matter so much in interviews.

---

## 2. Scheduler Types

The scheduler names matter because they encode intent:

| Scheduler | Typical use |
|-----------|-------------|
| `Schedulers.parallel()` | short CPU-bound work |
| `Schedulers.boundedElastic()` | blocking I/O escape hatch |
| `Schedulers.single()` | serialized work on one reusable thread |
| `Schedulers.immediate()` | run on the caller thread |
| `Schedulers.fromExecutor(...)` | integrate a custom executor |

### `boundedElastic()` in 2026

Reactor’s reference guide says `Schedulers.boundedElastic()` is the preferred scheduler for blocking work, and that since Reactor 3.6 it can use a virtual-thread-per-task implementation when:

- the app runs on Java 21+
- `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`

Otherwise it uses the standard bounded elastic pool:

- capped thread creation
- idle worker reuse
- up to `100000` queued tasks after the thread cap is reached

That means the right claim is:

- Reactor **can** integrate with virtual threads here
- virtual-thread-backed `boundedElastic` is **opt-in**
- it is not accurate to say Reactor "became virtual-thread based by default"

---

## 3. `subscribeOn` vs `publishOn`

This is one of the most common interview questions.

### `subscribeOn`

`subscribeOn` affects where subscription starts and where upstream work runs.

```java
Mono.fromCallable(this::blockingCall)
    .subscribeOn(Schedulers.boundedElastic());
```

Use it mainly for:

- blocking sources
- source creation that should not run on the current thread

### `publishOn`

`publishOn` changes the scheduler for downstream work from that point on.

```java
Flux.range(1, 5)
    .publishOn(Schedulers.parallel())
    .map(this::cpuHeavyTransform);
```

### Short Rule

- `subscribeOn`: choose where the source runs
- `publishOn`: choose where the rest of the chain runs from here

### Multiple Calls

- only the first effective `subscribeOn` matters
- every `publishOn` introduces a new downstream hop

---

## 4. Parallel Rails

```java
Flux.range(1, 1_000_000)
    .parallel()
    .runOn(Schedulers.parallel())
    .map(this::expensiveCalculation)
    .sequential();
```

Use `parallel()` sparingly:

- good for stateless CPU-bound work
- not the default choice for I/O fan-out

For I/O concurrency, `flatMap` with controlled concurrency is usually the clearer tool.

---

## 5. Backpressure Strategies

Backpressure exists so the consumer can control rate. But some sources still produce too fast, so you need a policy.

### Buffer

```java
source.onBackpressureBuffer();
source.onBackpressureBuffer(1000);
```

- preserves data
- risks memory growth if unbounded

### Drop

```java
source.onBackpressureDrop();
```

- loses data
- appropriate for lossy streams such as metrics or sampling

### Latest

```java
source.onBackpressureLatest();
```

- keeps only the newest item
- useful for UI-like "freshest state wins" situations

### Error

```java
source.onBackpressureError();
```

- fail fast when the downstream cannot keep up

### `limitRate`

```java
Flux.range(1, 1000)
    .limitRate(100);
```

Use `limitRate` when tuning upstream request size rather than rewriting the whole pipeline.

---

## 6. Reactor `Context`

Reactive code can hop threads, so plain `ThreadLocal` is not a reliable per-request transport.

Reactor solves that with `Context`, which is tied to the subscription, not to a specific thread.

```java
Mono<String> result = Mono.deferContextual(ctx ->
        Mono.just("traceId=" + ctx.get("traceId")))
    .contextWrite(context -> context.put("traceId", "abc-123"));
```

Important details from the Reactor docs:

- context propagates through the subscription path
- writes conceptually move from downstream toward upstream
- relative operator placement matters

If you place a `contextWrite` too high in the chain, readers below it will not see that value.

---

## 7. Micrometer Context Propagation

Since Reactor 3.5, Reactor integrates with `io.micrometer:context-propagation`.

That enables bridging between:

- Reactor `Context`
- `ThreadLocal`-based mechanisms such as MDC or tracing

Automatic restoration mode is enabled with:

```java
Hooks.enableAutomaticContextPropagation();
```

Use the precise claim:

- Reactor supports Micrometer context propagation
- automatic mode applies to **new subscriptions**
- this helps bridge `ThreadLocal`-based tooling, but it does not make `ThreadLocal` itself the primary reactive abstraction

---

## 8. Senior-Level Guidance

If you must call a blocking library from Reactor:

```java
Mono.fromCallable(() -> jdbcTemplate.queryForObject(...))
    .subscribeOn(Schedulers.boundedElastic());
```

That is the standard escape hatch.

But the correct senior caveat is:

- it avoids blocking the event loop
- it does **not** make the library non-blocking
- enough of these calls can still become a scaling bottleneck
