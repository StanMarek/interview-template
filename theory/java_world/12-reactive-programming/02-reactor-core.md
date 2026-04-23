# Reactor Core

> **Goal:** Learn the Reactor vocabulary Spring uses by default: `Mono`, `Flux`, hot vs cold publishers, and the operators interviewers ask about most often.

---

## 1. `Mono` and `Flux`

Reactor gives you two primary publisher types:

- `Mono<T>`: 0 or 1 item
- `Flux<T>`: 0 to N items

```java
Mono<String> one = Mono.just("hello");
Mono<String> empty = Mono.empty();
Mono<String> lazy = Mono.fromSupplier(this::loadValue);

Flux<Integer> many = Flux.range(1, 5);
Flux<String> fromIterable = Flux.fromIterable(List.of("a", "b", "c"));
Flux<Long> interval = Flux.interval(Duration.ofSeconds(1));
```

### Useful Creation Patterns

```java
Mono<User> blockingWrapper = Mono.fromCallable(() -> legacyService.loadUser());

Flux<Integer> generated = Flux.generate(
    () -> 0,
    (state, sink) -> {
        sink.next(state);
        if (state == 3) {
            sink.complete();
        }
        return state + 1;
    }
);
```

Use:

- `fromCallable` or `fromSupplier` for lazy creation
- `generate` for synchronous, one-by-one emission
- `create` or `push` when bridging callback-style APIs

---

## 2. Cold vs Hot

Most Reactor sequences are **cold**:

- every subscription gets its own sequence
- nothing happens until somebody subscribes

```java
Flux<Integer> cold = Flux.range(1, 3);

cold.subscribe(i -> System.out.println("A: " + i));
cold.subscribe(i -> System.out.println("B: " + i));
```

Both subscribers see `1, 2, 3`.

Hot publishers are different:

- data may exist independently of a subscriber
- late subscribers can miss earlier events

```java
Flux<Long> hot = Flux.interval(Duration.ofMillis(100))
    .publish()
    .autoConnect(1);
```

Use the "radio broadcast" analogy for hot sources and the "query rerun per subscriber" analogy for cold ones.

---

## 3. Sinks

`Sinks` are Reactor’s programmatic signal emitters. They replaced the old processor-heavy approach.

```java
Sinks.One<String> one = Sinks.one();
Mono<String> result = one.asMono();
one.tryEmitValue("done");

Sinks.Many<String> replay = Sinks.many().replay().limit(10);
Flux<String> replayFlux = replay.asFlux();
```

Common variants:

- `Sinks.one()` for one value
- `many().unicast()` for one subscriber
- `many().multicast()` for many subscribers without replay
- `many().replay()` for replay semantics

---

## 4. The Operator Set You Must Know

### Transforming

```java
Flux.just("a", "bb", "ccc")
    .map(String::length);
```

`map` is synchronous and one-to-one.

### `flatMap`

```java
Flux.just(1, 2, 3)
    .flatMap(userService::findById);
```

Use `flatMap` when:

- each item becomes an async inner publisher
- order does not matter
- throughput matters more than ordering

### `concatMap`

```java
Flux.just(1, 2, 3)
    .concatMap(userService::findById);
```

Use `concatMap` when:

- ordering matters
- sequential processing is acceptable

### `flatMapSequential`

```java
Flux.just(1, 2, 3)
    .flatMapSequential(userService::findById);
```

Use it when:

- you want concurrent inner work
- but still want ordered downstream results

### `switchMap`

```java
Flux.just("j", "ja", "jav", "java")
    .switchMap(searchService::search);
```

Use it for "latest wins" semantics such as typeahead search.

---

## 5. Filtering and Combining

```java
Flux.range(1, 10)
    .filter(i -> i % 2 == 0)
    .take(3)
    .skip(1);
```

```java
Mono<User> user = userService.getUser(id);
Mono<Profile> profile = profileService.getProfile(id);

Mono<UserDto> dto = Mono.zip(user, profile, UserDto::new);
```

Know these families:

- filtering: `filter`, `take`, `skip`, `distinct`
- aggregation: `collectList`, `reduce`
- combination: `zip`, `merge`, `concat`, `combineLatest`

### Quick Distinction

- `merge`: interleave as data arrives
- `concat`: second source waits for the first to finish
- `zip`: align by index
- `combineLatest`: recompute whenever any source emits

---

## 6. Error Handling

Reactive error handling is operator-based, not `try/catch` around the whole pipeline.

```java
Mono<String> safe = remoteCall()
    .timeout(Duration.ofSeconds(2))
    .onErrorResume(TimeoutException.class, ex -> cacheLookup())
    .onErrorMap(original -> new ServiceException("Remote call failed", original));
```

The important operators:

- `onErrorReturn`
- `onErrorResume`
- `onErrorMap`
- `retry`
- `retryWhen`
- `timeout`
- `doOnError`

### Rule Of Thumb

- use `onErrorResume` for a fallback path
- use `onErrorMap` when translating infrastructure exceptions to domain exceptions
- use `retryWhen` for transient failures with policy
- never silently swallow errors unless that is truly the business rule

---

## 7. A Typical Orchestration Example

```java
public Mono<OrderSummary> loadSummary(String orderId) {
    return orderService.findById(orderId)
        .switchIfEmpty(Mono.error(new NotFoundException(orderId)))
        .flatMap(order ->
            Mono.zip(
                customerService.findById(order.customerId()),
                shippingService.status(order.id()),
                Flux.fromIterable(order.productIds())
                    .flatMap(productService::findById)
                    .collectList()
            ).map(tuple -> new OrderSummary(
                order,
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3()
            ))
        );
}
```

What interviewers want to hear:

- you understand fan-out and fan-in
- you know when ordering matters
- you know failures short-circuit unless handled

---

## 8. What Seniors Usually Get Asked

**Why not just use `CompletableFuture`?**
Because Reactor gives you a richer streaming model, better composition for multi-item sequences, and built-in backpressure semantics.

**Is `Mono` just a `CompletableFuture` clone?**
Not exactly. It is a Reactive Streams publisher with signal semantics, cancellation, and operator composition in the same model as `Flux`.

**What is the most common misuse of `flatMap`?**
Using it when order matters, then being surprised by interleaving.
