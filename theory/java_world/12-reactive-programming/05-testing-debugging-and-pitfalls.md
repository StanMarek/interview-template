# Testing, Debugging, and Pitfalls

> **Goal:** Know how reactive code is verified, how failures are diagnosed, and what mistakes senior interviewers expect you to spot quickly.

---

## 1. `StepVerifier`

`StepVerifier` is the default way to test Reactor publishers.

```java
@Test
void emitsExpectedValues() {
    StepVerifier.create(Flux.just("a", "b", "c"))
        .expectNext("a")
        .expectNext("b")
        .expectNext("c")
        .verifyComplete();
}
```

Error case:

```java
@Test
void emitsError() {
    StepVerifier.create(Flux.just(1, 2, 0).map(i -> 10 / i))
        .expectNext(10, 5)
        .expectError(ArithmeticException.class)
        .verify();
}
```

Use `assertNext`, `expectNextMatches`, and `expectNextCount` when exact values are not the point of the test.

---

## 2. Virtual Time

For time-based operators, use virtual time instead of sleeping in tests.

```java
@Test
void testsIntervalWithoutWaitingRealTime() {
    StepVerifier.withVirtualTime(() -> Flux.interval(Duration.ofHours(1)).take(3))
        .expectSubscription()
        .thenAwait(Duration.ofHours(3))
        .expectNext(0L, 1L, 2L)
        .verifyComplete();
}
```

Important rule:

- create the publisher inside the supplier passed to `withVirtualTime`

---

## 3. `TestPublisher`

`TestPublisher` lets you drive the source manually.

```java
@Test
void controlsTheSource() {
    TestPublisher<String> source = TestPublisher.create();

    StepVerifier.create(source.flux().map(String::toUpperCase))
        .then(() -> source.next("alpha", "beta"))
        .expectNext("ALPHA", "BETA")
        .then(source::complete)
        .verifyComplete();
}
```

That is useful when you want to test operator behavior instead of waiting on real asynchronous components.

---

## 4. `WebTestClient`

For WebFlux integration tests:

```java
client.get()
    .uri("/api/users/42")
    .exchange()
    .expectStatus().isOk()
    .expectHeader().contentType(MediaType.APPLICATION_JSON)
    .expectBody(User.class)
    .value(user -> assertThat(user.getName()).isEqualTo("alice"));
```

It is the standard test client for WebFlux endpoints.

---

## 5. Debugging Tools

### `checkpoint`

Use `checkpoint` for targeted diagnostics.

```java
return userService.findById(id)
    .checkpoint("after user lookup")
    .flatMap(profileService::enrich)
    .checkpoint("after profile enrich");
```

This is usually the best first move when a specific chain is failing.

### `Hooks.onOperatorDebug()`

```java
Hooks.onOperatorDebug();
```

This captures assembly information for all operators and gives much better stack traces, but with meaningful overhead. Keep it to development and tests.

### `ReactorDebugAgent`

```java
ReactorDebugAgent.init();
```

Reactor documents this as a lower-overhead alternative based on bytecode instrumentation. It is more practical than global operator debug when you need richer stack traces without paying the full assembly-capture cost everywhere.

Make the conservative claim:

- it is lower overhead than `Hooks.onOperatorDebug()`
- it still needs explicit setup and operational judgment
- do not present it as a magic "leave it on everywhere forever" switch

### BlockHound

```java
BlockHound.install();
```

BlockHound detects blocking calls on threads that should not block, which is extremely valuable in tests for WebFlux and Reactor code.

---

## 6. Common Pitfalls

### Blocking the Event Loop

Bad:

```java
Flux.range(1, 10)
    .map(i -> jdbcTemplate.queryForObject("select ...", User.class));
```

Better:

```java
Flux.range(1, 10)
    .flatMap(i -> Mono.fromCallable(() -> jdbcTemplate.queryForObject("select ...", User.class))
        .subscribeOn(Schedulers.boundedElastic()));
```

### Forgetting to Subscribe

Nothing happens without subscription.

In frameworks, the container usually subscribes for you.

In tests or standalone code, you often must do it explicitly.

### Using `ThreadLocal` As If Threads Were Stable

Reactive code is not thread-affine by default. Use Reactor `Context`, optionally bridged through Micrometer context propagation.

### Swallowing Errors

Bad:

```java
flux.subscribe(value -> process(value));
```

Better:

```java
flux.subscribe(
    this::process,
    error -> log.error("Stream failed", error)
);
```

Or handle errors in the chain itself with `onErrorResume`, `onErrorMap`, and `doOnError`.

### Overgrown Chains

If the chain is unreadable, split it into named methods.

This is not "anti-reactive". It is basic maintainability.

### Misunderstanding Hot Publishers

Late subscribers can miss data on hot sources. If replay is required, choose replay semantics explicitly.

---

## 7. What Interviewers Often Ask

**How do you test a timeout or retry policy without making tests slow?**
Use `StepVerifier.withVirtualTime`.

**How do you catch accidental blocking calls?**
Use BlockHound in tests and avoid running blocking calls on event-loop threads.

**What is the first debugging tool you reach for?**
Usually `checkpoint` in the failing chain, then broader tooling if needed.
