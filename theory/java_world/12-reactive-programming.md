# Reactive Programming — Senior Engineer Interview Preparation

---

## 1. Why Reactive?

### The Problem with Thread-Per-Request

Traditional servlet-based Java applications assign one OS thread to each incoming request. This model is simple but hits hard limits under high concurrency:

- Each platform thread consumes ~1 MB of stack memory by default
- A server with 4 GB allocated to threads can handle ~4,000 concurrent connections
- Under load, most threads are **blocked** waiting on I/O (database queries, HTTP calls, file reads)
- Thread scheduling overhead grows as context switches increase
- Thread pools become saturated, requests queue up, latency spikes

```java
// Traditional blocking approach — thread is parked during the entire DB call
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    User user = userRepository.findById(id);     // Thread blocked ~50ms
    List<Order> orders = orderService.get(id);    // Thread blocked ~30ms
    user.setOrders(orders);                       // Total: ~80ms of thread idle time
    return user;
}
```

With 200 threads in a pool, this server can handle at most ~2,500 requests/second at 80ms per request. Beyond that, requests queue and latency degrades.

### The C10K Problem

The C10K problem (handling 10,000 concurrent connections on a single machine) exposed the limitations of the thread-per-connection model. Solutions evolved toward **event-driven, non-blocking I/O**:

- **Nginx** replaced Apache's thread-per-connection with an event loop
- **Node.js** popularized single-threaded event loops with callbacks
- **Netty** brought non-blocking I/O to the JVM with an event-loop architecture
- **Reactive programming** formalized asynchronous data stream processing with backpressure

### The Reactive Manifesto

The Reactive Manifesto (2014) defines four pillars for building responsive distributed systems:

| Pillar | Description | Mechanism |
|--------|-------------|-----------|
| **Responsive** | Consistent, timely response times | Bounded latency, fast failure detection |
| **Resilient** | Stays responsive during failures | Replication, containment, isolation, delegation |
| **Elastic** | Stays responsive under varying load | Scale up/down without contention points |
| **Message-Driven** | Asynchronous message passing between components | Non-blocking communication, backpressure, location transparency |

Message-driven architecture is the **foundation** — it enables the other three properties. Asynchronous boundaries between components allow independent failure handling and elastic scaling.

### When Reactive Makes Sense vs When It Is Overkill

**Reactive is a strong fit when:**
- High concurrency with many simultaneous I/O-bound operations (10K+ connections)
- Streaming data — real-time feeds, SSE, WebSockets
- Orchestrating multiple downstream service calls in microservices
- Event-driven architectures with message brokers (Kafka, RabbitMQ)
- You need backpressure to prevent fast producers from overwhelming slow consumers

**Reactive is likely overkill when:**
- Low-to-moderate concurrency (< 1,000 concurrent requests)
- CPU-bound workloads (reactive does not speed up computation)
- Simple CRUD applications with straightforward request/response
- The team lacks reactive experience (steep learning curve, hard-to-debug stack traces)
- Virtual threads (Java 21+) can solve your concurrency needs with a simpler model

---

## 2. Reactive Streams Specification

The Reactive Streams specification (`org.reactivestreams`) defines a minimal set of interfaces for asynchronous stream processing with non-blocking backpressure. It was incorporated into the JDK as `java.util.concurrent.Flow` in Java 9.

### Core Interfaces

```java
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    void onSubscribe(Subscription s);   // Always called first
    void onNext(T t);                    // 0..N data signals
    void onError(Throwable t);           // Terminal: error
    void onComplete();                   // Terminal: success
}

public interface Subscription {
    void request(long n);   // Signal demand for n items (backpressure)
    void cancel();          // Cancel the subscription
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {
    // Both a subscriber and a publisher — transforms T to R
}
```

### Signal Protocol Rules

The specification enforces strict ordering and safety:

1. **`onSubscribe`** is always the first signal — exactly once
2. **`onNext`** signals must respect demand: publisher cannot emit more items than requested via `request(n)`
3. **`onError`** and **`onComplete`** are terminal — after either, no more signals
4. **Null values are forbidden** — passing null to `onNext` throws `NullPointerException`
5. **Demand is additive** — `request(3)` followed by `request(2)` means the publisher can emit 5 items total
6. **`request(Long.MAX_VALUE)`** effectively disables backpressure (unbounded demand)
7. **Calling `cancel()` does not guarantee immediate stop** — signals already in flight may still arrive

### How Backpressure Flows Upstream

```
Subscriber          Subscription          Publisher
    │                    │                    │
    │  onSubscribe(sub)  │                    │
    │◄───────────────────│                    │
    │                    │                    │
    │   request(10)      │                    │
    │───────────────────►│   (demand = 10)    │
    │                    │───────────────────►│
    │                    │                    │
    │   onNext(item1)    │                    │
    │◄───────────────────│   (demand = 9)     │
    │   onNext(item2)    │                    │
    │◄───────────────────│   (demand = 8)     │
    │        ...         │                    │
    │                    │                    │
    │   request(5)       │                    │
    │───────────────────►│   (demand += 5)    │
    │                    │                    │
    │   onComplete()     │                    │
    │◄───────────────────│                    │
```

The subscriber controls the flow rate by signaling how many items it can process. The publisher must respect this demand. This is **pull-based** — the subscriber pulls data at its own pace, preventing the publisher from overwhelming it.

### Relationship to java.util.concurrent.Flow

Since Java 9, the Reactive Streams interfaces are mirrored in `java.util.concurrent.Flow`:

```java
// JDK equivalent — semantically identical
Flow.Publisher<T>
Flow.Subscriber<T>
Flow.Subscription
Flow.Processor<T, R>
```

Libraries like Reactor provide adapters between `org.reactivestreams` and `Flow` types.

---

## 3. Project Reactor — Core Types

Project Reactor is the reactive library used by Spring WebFlux. It implements the Reactive Streams specification and provides two core publisher types.

### Mono\<T\> — 0 or 1 Element

`Mono<T>` represents an asynchronous computation that produces at most one value, then completes (or errors). Think of it as a reactive `CompletableFuture` / `Optional`.

```java
// Creation
Mono<String> justMono = Mono.just("hello");
Mono<String> emptyMono = Mono.empty();
Mono<String> errorMono = Mono.error(new RuntimeException("failed"));

// Deferred creation — lambda runs at subscribe time
Mono<String> deferred = Mono.fromSupplier(() -> expensiveCall());

// From CompletableFuture
Mono<String> fromFuture = Mono.fromFuture(CompletableFuture.supplyAsync(() -> "result"));

// From Callable
Mono<String> fromCallable = Mono.fromCallable(() -> blockingService.call());

// Transformation
Mono<Integer> length = Mono.just("hello")
    .map(String::length)               // 5
    .filter(len -> len > 3)            // passes
    .defaultIfEmpty(0);                // fallback if empty
```

### Flux\<T\> — 0 to N Elements

`Flux<T>` represents an asynchronous sequence of 0 to N items. It is the reactive equivalent of `Stream<T>` or `Iterable<T>`, but with push-based semantics and backpressure.

```java
// Creation
Flux<String> fromValues = Flux.just("a", "b", "c");
Flux<Integer> fromRange = Flux.range(1, 10);           // 1 through 10
Flux<String> fromIterable = Flux.fromIterable(List.of("x", "y", "z"));
Flux<Long> interval = Flux.interval(Duration.ofSeconds(1));  // 0, 1, 2, ... every second

// From array
Flux<String> fromArray = Flux.fromArray(new String[]{"a", "b", "c"});

// Generate — stateful, synchronous, one-by-one emission
Flux<Integer> generated = Flux.generate(
    () -> 0,                                    // initial state
    (state, sink) -> {
        sink.next(state);                       // emit current state
        if (state == 10) sink.complete();       // terminal condition
        return state + 1;                       // next state
    }
);

// Create — asynchronous, multi-value, bridge from callback APIs
Flux<String> created = Flux.create(sink -> {
    eventSource.register(event -> sink.next(event.getData()));
    eventSource.onClose(() -> sink.complete());
    eventSource.onError(e -> sink.error(e));
});
```

### Cold vs Hot Publishers

This distinction is critical for understanding reactive behavior:

**Cold Publishers** — data is generated fresh for each subscriber. Each subscriber gets the full sequence independently. Most Reactor operators produce cold publishers.

```java
Flux<Integer> cold = Flux.range(1, 3);

cold.subscribe(i -> System.out.println("Subscriber A: " + i));
// A: 1, A: 2, A: 3

cold.subscribe(i -> System.out.println("Subscriber B: " + i));
// B: 1, B: 2, B: 3
// Both get the complete sequence independently
```

**Hot Publishers** — data is emitted regardless of subscribers. Late subscribers miss items that were emitted before they subscribed. Think of a live radio broadcast.

```java
// ConnectableFlux — multicast cold source to multiple subscribers
Flux<Long> hot = Flux.interval(Duration.ofMillis(100))
    .publish()          // Convert to ConnectableFlux
    .autoConnect(2);    // Start emitting when 2 subscribers connect

hot.subscribe(i -> System.out.println("Sub A: " + i));
// ... some delay ...
hot.subscribe(i -> System.out.println("Sub B: " + i));
// Both receive the SAME items from the point of connection
```

| Aspect | Cold Publisher | Hot Publisher |
|--------|--------------|-------------- |
| Data generation | Per subscriber | Shared across subscribers |
| Subscriber timing | Gets full sequence | May miss earlier items |
| Example | HTTP request, DB query | Event stream, WebSocket, sensor data |
| Default in Reactor | Yes (most operators) | No (explicit conversion needed) |

### Sinks — Programmatic Signal Emission

`Sinks` provide a thread-safe way to programmatically emit signals. They replace the deprecated `FluxProcessor` and `EmitterProcessor`.

```java
// Sinks.One — emit a single value (Mono-like)
Sinks.One<String> sinkOne = Sinks.one();
Mono<String> mono = sinkOne.asMono();
sinkOne.tryEmitValue("result");

// Sinks.Many — emit multiple values (Flux-like)

// Unicast — single subscriber only
Sinks.Many<String> unicast = Sinks.many().unicast().onBackpressureBuffer();
Flux<String> unicastFlux = unicast.asFlux();

// Multicast — multiple subscribers, replay none
Sinks.Many<String> multicast = Sinks.many().multicast().onBackpressureBuffer();
Flux<String> multicastFlux = multicast.asFlux();

// Replay — multiple subscribers, replay last N items to late subscribers
Sinks.Many<String> replay = Sinks.many().replay().limit(10);
Flux<String> replayFlux = replay.asFlux();

// Emitting values
multicast.tryEmitNext("event-1");
multicast.tryEmitNext("event-2");
multicast.tryEmitComplete();
```

---

## 4. Reactor Operators

### Transforming Operators

```java
// map — synchronous 1-to-1 transformation
Flux.just("hello", "world")
    .map(String::toUpperCase)
    // "HELLO", "WORLD"

// flatMap — asynchronous 1-to-N, interleaved (no ordering guarantee)
Flux.just(1, 2, 3)
    .flatMap(id -> fetchUserAsync(id))  // Results may arrive in any order
    // User3, User1, User2 (order not guaranteed)

// concatMap — asynchronous 1-to-N, sequential (preserves order)
Flux.just(1, 2, 3)
    .concatMap(id -> fetchUserAsync(id))  // Waits for each to complete
    // User1, User2, User3 (order guaranteed, slower)

// flatMapSequential — asynchronous 1-to-N, eager subscribe but ordered output
Flux.just(1, 2, 3)
    .flatMapSequential(id -> fetchUserAsync(id))  // Subscribes eagerly, reorders
    // User1, User2, User3 (order guaranteed, potentially faster than concatMap)

// switchMap — cancels previous inner publisher when new item arrives
Flux.just("a", "ab", "abc")
    .switchMap(query -> searchService.search(query))
    // Only the result of "abc" search matters; earlier searches are cancelled
```

### flatMap vs concatMap vs flatMapSequential

| Operator | Subscribes | Ordering | Concurrency | Use Case |
|----------|-----------|----------|-------------|----------|
| `flatMap` | Eagerly, all at once | No guarantee | Configurable (`concurrency` param, default 256) | Maximum throughput, order irrelevant |
| `concatMap` | One at a time, sequentially | Preserved | 1 (inherently sequential) | Order matters, sequential processing |
| `flatMapSequential` | Eagerly, all at once | Preserved (reordered on output) | Configurable (`maxConcurrency` param) | Order matters but want concurrency |
| `switchMap` | Eagerly, cancels previous | Latest only | 1 active at a time | Typeahead search, latest-wins semantics |

### Filtering Operators

```java
Flux.range(1, 10)
    .filter(n -> n % 2 == 0)         // 2, 4, 6, 8, 10
    .distinct()                       // Remove duplicates
    .take(3)                          // 2, 4, 6 — take first 3
    .skip(1)                          // 4, 6 — skip first 1
    .elementAt(0)                     // Mono<Integer> = 4

// takeLast / skipLast
Flux.range(1, 5).takeLast(2);        // 4, 5
Flux.range(1, 5).skipLast(2);        // 1, 2, 3

// takeUntil / takeWhile
Flux.range(1, 10)
    .takeWhile(n -> n < 5);          // 1, 2, 3, 4
Flux.range(1, 10)
    .takeUntil(n -> n == 5);         // 1, 2, 3, 4, 5 (includes the matching element)
```

### Combining Operators

```java
Mono<User> user = userService.getUser(id);
Mono<Profile> profile = profileService.getProfile(id);

// zip — combine when both complete, pair by index
Mono<Tuple2<User, Profile>> combined = Mono.zip(user, profile);
Mono<UserDTO> dto = Mono.zip(user, profile)
    .map(tuple -> new UserDTO(tuple.getT1(), tuple.getT2()));

// zip with combinator function
Mono<UserDTO> dto2 = Mono.zip(user, profile, UserDTO::new);

// merge — interleave emissions from multiple sources (eager subscription)
Flux<Event> merged = Flux.merge(
    kafkaEvents(),       // Events arrive in real time
    webSocketEvents()    // Interleaved as they come
);

// concat — sequential, second source starts after first completes
Flux<Event> sequential = Flux.concat(
    cachedEvents(),      // Emit cached events first
    liveEvents()         // Then switch to live events
);

// combineLatest — re-emit whenever any source emits
Flux<String> combined = Flux.combineLatest(
    priceStream,
    exchangeRateStream,
    (price, rate) -> String.format("%.2f", price * rate)
);
```

### Error Handling

```java
// onErrorReturn — fallback value
Mono.fromCallable(() -> riskyOperation())
    .onErrorReturn("default-value");

// onErrorResume — fallback publisher
Mono.fromCallable(() -> primaryService.call())
    .onErrorResume(TimeoutException.class, ex -> fallbackService.call());

// onErrorMap — transform the exception
Mono.fromCallable(() -> parseJson(input))
    .onErrorMap(JsonParseException.class, ex ->
        new BadRequestException("Invalid JSON: " + ex.getMessage()));

// retry — simple retry N times
Mono.fromCallable(() -> unreliableService.call())
    .retry(3);

// retryWhen — advanced retry with backoff
Mono.fromCallable(() -> unreliableService.call())
    .retryWhen(Retry.backoff(3, Duration.ofMillis(500))
        .maxBackoff(Duration.ofSeconds(5))
        .filter(ex -> ex instanceof TransientException)
        .onRetryExhaustedThrow((spec, signal) ->
            new ServiceUnavailableException("Retries exhausted", signal.failure()))
    );

// timeout
Mono.fromCallable(() -> slowService.call())
    .timeout(Duration.ofSeconds(5))
    .onErrorResume(TimeoutException.class, ex -> Mono.just(cachedResult));
```

### Side Effects (Peeking Operators)

Side-effect operators do not modify the signal — they "peek" at it for logging, metrics, etc.

```java
Flux.range(1, 5)
    .doOnSubscribe(sub -> log.info("Subscribed"))
    .doOnNext(item -> log.debug("Processing: {}", item))
    .doOnError(err -> log.error("Error: {}", err.getMessage()))
    .doOnComplete(() -> log.info("Completed"))
    .doOnCancel(() -> log.warn("Cancelled"))
    .doOnTerminate(() -> log.info("Terminated (complete or error)"))
    .doFinally(signalType -> log.info("Finally: {}", signalType))
    .log()   // Logs all signals with Reactive Streams semantics
    .subscribe();
```

### Context — Replacing ThreadLocal

In reactive chains, execution hops between threads. `ThreadLocal` breaks. Reactor provides `Context`, an immutable key-value store propagated through the subscription chain.

```java
// Writing context
Mono<String> withContext = Mono.deferContextual(ctx -> {
        String traceId = ctx.get("traceId");
        return callService(traceId);
    })
    .contextWrite(Context.of("traceId", "abc-123"));

// Extracting context in operators
Flux.range(1, 5)
    .flatMap(i -> Mono.deferContextual(ctx -> {
        String userId = ctx.get("userId");
        return processWithUser(i, userId);
    }))
    .contextWrite(Context.of("userId", "user-42"));

// Context flows upstream (from bottom to top in the chain)
Mono.just("data")
    .flatMap(d -> Mono.deferContextual(ctx -> {
        // "key" = "value2" — closest contextWrite downstream wins
        return Mono.just(d + ctx.get("key"));
    }))
    .contextWrite(Context.of("key", "value2"))   // This one is seen
    .contextWrite(Context.of("key", "value1"))   // This one is further downstream
    .subscribe(System.out::println);  // "datavalue2"
```

### Orchestrating Multiple Service Calls

```java
public Mono<OrderSummary> getOrderSummary(String orderId) {
    Mono<Order> order = orderService.findById(orderId)
        .switchIfEmpty(Mono.error(new NotFoundException("Order not found")));

    return order.flatMap(o -> {
        // Fan out: parallel calls for related data
        Mono<Customer> customer = customerService.findById(o.getCustomerId());
        Mono<List<Product>> products = Flux.fromIterable(o.getProductIds())
            .flatMap(productService::findById)
            .collectList();
        Mono<ShippingInfo> shipping = shippingService.getInfo(orderId);

        // Combine results
        return Mono.zip(customer, products, shipping)
            .map(tuple -> OrderSummary.builder()
                .order(o)
                .customer(tuple.getT1())
                .products(tuple.getT2())
                .shipping(tuple.getT3())
                .build());
    })
    .timeout(Duration.ofSeconds(5))
    .onErrorResume(TimeoutException.class, ex ->
        Mono.error(new ServiceUnavailableException("Order summary timed out")));
}
```

---

## 5. Backpressure Strategies

Backpressure is the mechanism by which a slow consumer tells a fast producer to slow down. When a producer outpaces a consumer, you need a strategy to handle the excess signals.

### Strategies

```java
// Buffer — accumulate excess items (risk: unbounded memory growth)
Flux.interval(Duration.ofMillis(1))
    .onBackpressureBuffer()                        // Unbounded buffer
    .subscribe(slowConsumer());

// Buffer with limit and overflow strategy
Flux.interval(Duration.ofMillis(1))
    .onBackpressureBuffer(1000,                    // Max 1000 items
        dropped -> log.warn("Dropped: {}", dropped),  // Overflow handler
        BufferOverflowStrategy.DROP_OLDEST)        // Drop oldest when full
    .subscribe(slowConsumer());

// Drop — discard items that exceed demand
Flux.interval(Duration.ofMillis(1))
    .onBackpressureDrop(dropped -> log.warn("Dropped: {}", dropped))
    .subscribe(slowConsumer());

// Latest — keep only the most recent item
Flux.interval(Duration.ofMillis(1))
    .onBackpressureLatest()    // Consumer always gets the freshest value
    .subscribe(slowConsumer());

// Error — signal error when demand is exceeded
Flux.interval(Duration.ofMillis(1))
    .onBackpressureError()     // Throws IllegalStateException
    .subscribe(slowConsumer());
```

### limitRate — Prefetch Control

`limitRate` controls how many items are requested from upstream at a time, providing fine-grained backpressure tuning.

```java
Flux.range(1, 1000)
    .limitRate(100)     // Request 100 at a time, replenish at 75% (75)
    .subscribe(item -> process(item));

// Custom low tide (replenish threshold)
Flux.range(1, 1000)
    .limitRate(100, 50)  // Request 100, replenish when 50 consumed
    .subscribe(item -> process(item));
```

### Choosing the Right Strategy

| Strategy | Data Loss | Memory Risk | Use Case |
|----------|-----------|-------------|----------|
| `buffer()` (unbounded) | None | High (OOM) | Only when you know the source is finite |
| `buffer(maxSize, strategy)` | Possible | Bounded | General-purpose with controlled memory |
| `drop()` | Yes | None | Metrics, sampling — ok to miss some values |
| `latest()` | Yes | None | UI updates, sensor readings — only latest matters |
| `error()` | N/A (fails) | None | Strict processing — every item must be handled |
| `limitRate(n)` | None | Controlled | Tuning prefetch to match consumer speed |

---

## 6. Spring WebFlux

Spring WebFlux is the reactive web framework in Spring, built on Project Reactor and Netty. It provides a non-blocking, event-loop-based alternative to Spring MVC.

### Annotated Controllers

The annotation model mirrors Spring MVC but returns reactive types:

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<User>> getUser(@PathVariable String id) {
        return userService.findById(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Flux<User> getAllUsers() {
        return userService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<User> createUser(@Valid @RequestBody Mono<User> userMono) {
        return userMono.flatMap(userService::save);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteUser(@PathVariable String id) {
        return userService.deleteById(id);
    }
}
```

### Functional Endpoints

Functional endpoints provide a lambda-based alternative to annotations:

```java
@Configuration
public class UserRouter {

    @Bean
    public RouterFunction<ServerResponse> userRoutes(UserHandler handler) {
        return RouterFunctions.route()
            .path("/api/users", builder -> builder
                .GET("/{id}", handler::getUser)
                .GET("", handler::getAllUsers)
                .POST("", handler::createUser)
                .DELETE("/{id}", handler::deleteUser))
            .build();
    }
}

@Component
public class UserHandler {

    private final UserService userService;

    public UserHandler(UserService userService) {
        this.userService = userService;
    }

    public Mono<ServerResponse> getUser(ServerRequest request) {
        String id = request.pathVariable("id");
        return userService.findById(id)
            .flatMap(user -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(user))
            .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getAllUsers(ServerRequest request) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(userService.findAll(), User.class);
    }

    public Mono<ServerResponse> createUser(ServerRequest request) {
        return request.bodyToMono(User.class)
            .flatMap(userService::save)
            .flatMap(saved -> ServerResponse.created(URI.create("/api/users/" + saved.getId()))
                .bodyValue(saved));
    }

    public Mono<ServerResponse> deleteUser(ServerRequest request) {
        String id = request.pathVariable("id");
        return userService.deleteById(id)
            .then(ServerResponse.noContent().build());
    }
}
```

### WebClient — Reactive HTTP Client

`WebClient` replaces `RestTemplate` for non-blocking HTTP calls:

```java
@Service
public class ExternalApiService {

    private final WebClient webClient;

    public ExternalApiService(WebClient.Builder builder) {
        this.webClient = builder
            .baseUrl("https://api.example.com")
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .filter(ExchangeFilterFunction.ofRequestProcessor(request -> {
                log.debug("Request: {} {}", request.method(), request.url());
                return Mono.just(request);
            }))
            .build();
    }

    public Mono<Product> getProduct(String id) {
        return webClient.get()
            .uri("/products/{id}", id)
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response ->
                Mono.error(new NotFoundException("Product not found: " + id)))
            .onStatus(HttpStatusCode::is5xxServerError, response ->
                Mono.error(new ServiceException("External API error")))
            .bodyToMono(Product.class)
            .timeout(Duration.ofSeconds(3))
            .retryWhen(Retry.backoff(2, Duration.ofMillis(500)));
    }

    public Flux<Product> searchProducts(String query) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/products/search")
                .queryParam("q", query)
                .build())
            .retrieve()
            .bodyToFlux(Product.class);
    }

    public Mono<Product> createProduct(Product product) {
        return webClient.post()
            .uri("/products")
            .bodyValue(product)
            .retrieve()
            .bodyToMono(Product.class);
    }
}
```

### Server-Sent Events (SSE) Streaming

```java
@GetMapping(value = "/stream/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> streamEvents() {
    return Flux.interval(Duration.ofSeconds(1))
        .map(seq -> ServerSentEvent.<String>builder()
            .id(String.valueOf(seq))
            .event("heartbeat")
            .data("tick " + seq)
            .build());
}

// Client-side consumption with WebClient
Flux<ServerSentEvent<String>> events = webClient.get()
    .uri("/stream/events")
    .retrieve()
    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});

events.subscribe(event -> log.info("SSE: {} - {}", event.event(), event.data()));
```

### WebSocket Support

```java
@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketMapping(WebSocketHandler handler) {
        Map<String, WebSocketHandler> map = Map.of("/ws/chat", handler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}

@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private final Sinks.Many<String> chatSink = Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Receive messages from client and broadcast to all
        Mono<Void> input = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(msg -> chatSink.tryEmitNext(msg))
            .then();

        // Send broadcasted messages to this client
        Mono<Void> output = session.send(
            chatSink.asFlux().map(session::textMessage));

        return Mono.zip(input, output).then();
    }
}
```

### When to Choose WebFlux vs Spring MVC

| Criteria | Spring MVC | Spring WebFlux |
|----------|-----------|----------------|
| Programming model | Imperative, blocking | Reactive, non-blocking |
| Server | Tomcat, Jetty (servlet) | Netty, Undertow (non-blocking) |
| Threading | Thread-per-request | Event loop (few threads) |
| Database access | JDBC, JPA (blocking) | R2DBC (non-blocking) |
| Concurrency | Hundreds to low thousands | Tens of thousands |
| Debugging | Standard stack traces | Complex, operator-heavy traces |
| Learning curve | Low | High |
| Streaming | Limited (chunked transfer) | Native (SSE, WebSocket, Flux) |
| Ecosystem maturity | Extensive (JPA, Hibernate, etc.) | Growing (R2DBC, reactive Mongo, etc.) |
| Virtual threads (Java 21+) | Excellent fit | Less necessary |

---

## 7. R2DBC — Reactive Database Access

### Why JDBC Is Blocking

JDBC was designed around blocking I/O. Every `Statement.execute()` call blocks the calling thread until the database responds. In a reactive pipeline, this defeats the purpose of non-blocking execution — one blocking call can stall the entire event loop.

```java
// This BLOCKS the event loop thread — never do this in WebFlux
Mono.fromCallable(() -> jdbcTemplate.queryForObject("SELECT ...", User.class))
    .subscribeOn(Schedulers.boundedElastic());  // Band-aid: offload to blocking pool
```

R2DBC (Reactive Relational Database Connectivity) provides a truly non-blocking SPI for relational databases.

### DatabaseClient Usage

```java
@Configuration
public class R2dbcConfig {

    @Bean
    public ConnectionFactory connectionFactory() {
        return ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, "localhost")
            .option(PORT, 5432)
            .option(USER, "admin")
            .option(PASSWORD, "secret")
            .option(DATABASE, "mydb")
            .build());
    }
}

@Repository
public class UserRepository {

    private final DatabaseClient databaseClient;

    public UserRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<User> findAll() {
        return databaseClient.sql("SELECT id, name, email FROM users")
            .map(row -> new User(
                row.get("id", Long.class),
                row.get("name", String.class),
                row.get("email", String.class)))
            .all();
    }

    public Mono<User> findById(Long id) {
        return databaseClient.sql("SELECT id, name, email FROM users WHERE id = :id")
            .bind("id", id)
            .map(row -> new User(
                row.get("id", Long.class),
                row.get("name", String.class),
                row.get("email", String.class)))
            .one();
    }

    public Mono<Void> insert(User user) {
        return databaseClient.sql("INSERT INTO users (name, email) VALUES (:name, :email)")
            .bind("name", user.getName())
            .bind("email", user.getEmail())
            .then();
    }
}
```

### Spring Data R2DBC — Reactive Repositories

Spring Data R2DBC provides repository abstractions similar to Spring Data JPA:

```java
public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    Flux<User> findByEmailContaining(String fragment);

    @Query("SELECT * FROM users WHERE name = :name")
    Mono<User> findByName(String name);

    @Query("SELECT * FROM users WHERE created_at > :since ORDER BY created_at DESC")
    Flux<User> findRecentUsers(LocalDateTime since);
}

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Mono<User> createUser(User user) {
        return userRepository.save(user);
    }

    public Flux<User> findActiveUsers() {
        return userRepository.findAll()
            .filter(User::isActive);
    }
}
```

### Connection Pooling with r2dbc-pool

```java
@Bean
public ConnectionFactory connectionFactory() {
    ConnectionFactory original = ConnectionFactories.get("r2dbc:postgresql://localhost/mydb");

    ConnectionPoolConfiguration config = ConnectionPoolConfiguration.builder(original)
        .maxIdleTime(Duration.ofMinutes(30))
        .initialSize(5)
        .maxSize(20)
        .maxCreateConnectionTime(Duration.ofSeconds(5))
        .build();

    return new ConnectionPool(config);
}
```

### Transactions in Reactive Context

```java
@Service
public class TransferService {

    private final TransactionalOperator txOperator;
    private final AccountRepository accountRepository;

    public TransferService(ReactiveTransactionManager txManager,
                           AccountRepository accountRepository) {
        this.txOperator = TransactionalOperator.create(txManager);
        this.accountRepository = accountRepository;
    }

    // Declarative transactions with @Transactional
    @Transactional
    public Mono<Void> transferDeclarative(String fromId, String toId, BigDecimal amount) {
        return accountRepository.findById(fromId)
            .flatMap(from -> {
                from.debit(amount);
                return accountRepository.save(from);
            })
            .then(accountRepository.findById(toId))
            .flatMap(to -> {
                to.credit(amount);
                return accountRepository.save(to);
            })
            .then();
    }

    // Programmatic transactions with TransactionalOperator
    public Mono<Void> transferProgrammatic(String fromId, String toId, BigDecimal amount) {
        return accountRepository.findById(fromId)
            .flatMap(from -> {
                from.debit(amount);
                return accountRepository.save(from);
            })
            .then(accountRepository.findById(toId))
            .flatMap(to -> {
                to.credit(amount);
                return accountRepository.save(to);
            })
            .then()
            .as(txOperator::transactional);  // Wrap in transaction
    }
}
```

---

## 8. Testing Reactive Code

### StepVerifier — Asserting Signal Sequences

`StepVerifier` is the primary tool for testing reactive publishers. It lets you assert the exact sequence of signals.

```java
@Test
void shouldEmitExpectedValues() {
    Flux<String> source = Flux.just("alpha", "bravo", "charlie");

    StepVerifier.create(source)
        .expectNext("alpha")
        .expectNext("bravo")
        .expectNext("charlie")
        .expectComplete()           // Assert onComplete signal
        .verify();                  // Triggers subscription and verification
}

@Test
void shouldHandleErrors() {
    Flux<Integer> source = Flux.just(1, 2, 0)
        .map(i -> 10 / i);         // ArithmeticException on third element

    StepVerifier.create(source)
        .expectNext(10)             // 10 / 1
        .expectNext(5)              // 10 / 2
        .expectError(ArithmeticException.class)
        .verify();
}

@Test
void shouldAssertWithMatchers() {
    Flux<User> users = userService.findAll();

    StepVerifier.create(users)
        .expectNextMatches(user -> user.getName().startsWith("A"))
        .expectNextCount(4)         // Expect 4 more items (skip asserting values)
        .thenConsumeWhile(user -> user.isActive())  // Consume while predicate holds
        .expectComplete()
        .verify(Duration.ofSeconds(5));   // Timeout for the entire verification
}

@Test
void shouldCollectAndAssert() {
    Flux<Integer> source = Flux.just(3, 1, 4, 1, 5, 9);

    StepVerifier.create(source.collectList())
        .assertNext(list -> {
            assertThat(list).hasSize(6);
            assertThat(list).containsExactly(3, 1, 4, 1, 5, 9);
        })
        .expectComplete()
        .verify();
}
```

### StepVerifier.withVirtualTime — Testing Time-Based Operators

Virtual time replaces the real clock, allowing instant testing of delays and intervals:

```java
@Test
void shouldTestIntervalWithVirtualTime() {
    // The Flux MUST be created inside the supplier for virtual time to work
    StepVerifier.withVirtualTime(() -> Flux.interval(Duration.ofHours(1)).take(3))
        .expectSubscription()
        .expectNoEvent(Duration.ofHours(1))    // Assert nothing happens for 1 hour
        .expectNext(0L)
        .thenAwait(Duration.ofHours(1))        // Advance virtual clock by 1 hour
        .expectNext(1L)
        .thenAwait(Duration.ofHours(1))
        .expectNext(2L)
        .expectComplete()
        .verify();
}

@Test
void shouldTestDelayedRetry() {
    AtomicInteger attempts = new AtomicInteger(0);

    Mono<String> retrying = Mono.defer(() -> {
        if (attempts.incrementAndGet() < 3) {
            return Mono.error(new RuntimeException("fail"));
        }
        return Mono.just("success");
    }).retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(10)));

    StepVerifier.withVirtualTime(() -> retrying)
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(10))     // First retry delay
        .thenAwait(Duration.ofSeconds(10))     // Second retry delay
        .expectNext("success")
        .expectComplete()
        .verify();
}
```

### TestPublisher — Simulating Publisher Behavior

`TestPublisher` lets you manually control emissions for testing operator behavior:

```java
@Test
void shouldHandleSlowPublisher() {
    TestPublisher<String> testPublisher = TestPublisher.create();

    Flux<String> processed = testPublisher.flux()
        .map(String::toUpperCase)
        .timeout(Duration.ofSeconds(5));

    StepVerifier.create(processed)
        .then(() -> testPublisher.next("hello"))
        .expectNext("HELLO")
        .then(() -> testPublisher.next("world"))
        .expectNext("WORLD")
        .then(() -> testPublisher.complete())
        .expectComplete()
        .verify();
}

@Test
void shouldHandleViolatingPublisher() {
    // Simulate a misbehaving publisher
    TestPublisher<String> violator = TestPublisher.createNoncompliant(
        TestPublisher.Violation.ALLOW_NULL);

    StepVerifier.create(violator.flux())
        .then(() -> violator.next(null))
        .expectError(NullPointerException.class)
        .verify();
}
```

### Comprehensive Test Example

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private OrderService orderService;

    @Test
    void shouldPlaceOrderWhenItemsInStock() {
        Order order = new Order("order-1", List.of("item-a", "item-b"));

        when(inventoryService.checkStock("item-a")).thenReturn(Mono.just(true));
        when(inventoryService.checkStock("item-b")).thenReturn(Mono.just(true));
        when(inventoryService.reserve("item-a")).thenReturn(Mono.empty());
        when(inventoryService.reserve("item-b")).thenReturn(Mono.empty());
        when(orderRepository.save(any())).thenReturn(Mono.just(order));

        StepVerifier.create(orderService.placeOrder(order))
            .assertNext(savedOrder -> {
                assertThat(savedOrder.getId()).isEqualTo("order-1");
                assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            })
            .expectComplete()
            .verify();

        verify(inventoryService).reserve("item-a");
        verify(inventoryService).reserve("item-b");
    }

    @Test
    void shouldRejectOrderWhenItemOutOfStock() {
        Order order = new Order("order-2", List.of("item-a", "item-b"));

        when(inventoryService.checkStock("item-a")).thenReturn(Mono.just(true));
        when(inventoryService.checkStock("item-b")).thenReturn(Mono.just(false));

        StepVerifier.create(orderService.placeOrder(order))
            .expectError(OutOfStockException.class)
            .verify();

        verify(inventoryService, never()).reserve(any());
    }
}
```

---

## 9. Virtual Threads vs Reactive

Java 21 introduced virtual threads (Project Loom), which offer a fundamentally different approach to the same concurrency problem reactive programming solves.

### Thread-Per-Request with Virtual Threads

Virtual threads are lightweight (few KB each), managed by the JVM rather than the OS. They make the thread-per-request model viable for massive concurrency:

```java
// Traditional platform threads — limited to thousands
ExecutorService platformPool = Executors.newFixedThreadPool(200);

// Virtual threads — can create millions
ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();

// Spring Boot 3.2+ — enable virtual threads
// application.properties:
// spring.threads.virtual.enabled=true

// Code stays imperative and simple
@GetMapping("/users/{id}")
public User getUser(@PathVariable Long id) {
    User user = userRepository.findById(id);     // Virtual thread parks (cheap)
    List<Order> orders = orderService.get(id);    // Virtual thread parks (cheap)
    user.setOrders(orders);
    return user;
}
```

When a virtual thread encounters a blocking I/O operation, it **unmounts** from the carrier (platform) thread, freeing the carrier for other virtual threads. When the I/O completes, the virtual thread is remounted on any available carrier.

### Comparison Table

| Aspect | Platform Threads | Virtual Threads | Reactive (Reactor/WebFlux) |
|--------|-----------------|-----------------|---------------------------|
| **Scalability** | ~5,000 concurrent | ~1,000,000+ concurrent | ~1,000,000+ concurrent |
| **Memory per task** | ~1 MB stack | ~few KB | Negligible (shared event loop) |
| **Programming model** | Imperative, blocking | Imperative, blocking | Functional, non-blocking |
| **Learning curve** | Low | Low | High |
| **Debugging** | Standard stack traces | Standard stack traces | Complex operator traces |
| **Backpressure** | Manual | Manual | Built-in |
| **Streaming** | Awkward | Awkward | Native |
| **ThreadLocal** | Works | Works | Broken (use Context) |
| **Ecosystem** | JDBC, JPA, all libraries | JDBC, JPA, all libraries | R2DBC, reactive-only libs |
| **Error handling** | try-catch | try-catch | Operator chains |
| **CPU-bound work** | Carrier thread bound | Carrier thread bound | Scheduler-dependent |

### When Virtual Threads Make Reactive Unnecessary

- Standard request-response microservices with blocking I/O (JDBC, JPA, REST calls)
- Teams without reactive experience — virtual threads preserve familiar imperative code
- Applications where backpressure and streaming are not requirements
- Brownfield projects with existing blocking codebases — virtual threads require zero refactoring

### When Reactive Still Wins

- **Streaming data** — SSE, WebSockets, infinite event streams are naturally expressed as `Flux`
- **Backpressure** — reactive streams have built-in demand signaling; virtual threads do not
- **Event-driven architectures** — reactive composition operators (`merge`, `zip`, `switchMap`) elegantly express complex event flows
- **Fan-out/fan-in patterns** — `flatMap` with controlled concurrency is more expressive than manual virtual thread coordination
- **Mature reactive ecosystems** — if you already use reactive MongoDB, Redis, Kafka, switching to virtual threads gains little

### Hybrid Approaches

You can mix both in the same application:

```java
@Configuration
public class HybridConfig {

    // WebFlux endpoint that delegates to blocking code on virtual threads
    @Bean
    public RouterFunction<ServerResponse> routes() {
        Scheduler virtualThreadScheduler = Schedulers.fromExecutor(
            Executors.newVirtualThreadPerTaskExecutor());

        return RouterFunctions.route()
            .GET("/api/report", request -> {
                Mono<Report> report = Mono.fromCallable(() -> legacyService.generateReport())
                    .subscribeOn(virtualThreadScheduler);  // Run on virtual thread
                return ServerResponse.ok().body(report, Report.class);
            })
            .build();
    }
}
```

### Migration Path from Reactive to Virtual Threads

1. **Upgrade to Java 21+** and Spring Boot 3.2+
2. **Enable virtual threads**: `spring.threads.virtual.enabled=true`
3. **Replace WebFlux controllers** with Spring MVC controllers one endpoint at a time
4. **Replace R2DBC** with JDBC/JPA (or keep R2DBC if it works well)
5. **Replace `WebClient`** with `RestClient` (blocking) where reactive composition is not needed
6. **Keep reactive for streaming endpoints** — SSE, WebSocket, Kafka consumers
7. **Remove `Mono`/`Flux`** wrappers around simple request-response flows
8. **Test thoroughly** — virtual threads have different characteristics with `synchronized` blocks and `native` methods (they pin the carrier thread)

---

## 10. Common Pitfalls

### 1. Blocking Calls in Reactive Chains

The most dangerous pitfall. A single blocking call on an event loop thread can freeze the entire application.

```java
// BAD — blocks the Netty event loop thread
Flux.range(1, 10)
    .map(id -> jdbcTemplate.queryForObject("SELECT ...", User.class)) // BLOCKS
    .subscribe();

// GOOD — offload to a bounded elastic scheduler
Flux.range(1, 10)
    .flatMap(id -> Mono.fromCallable(() -> jdbcTemplate.queryForObject("SELECT ...", User.class))
        .subscribeOn(Schedulers.boundedElastic()))
    .subscribe();
```

**Detection with BlockHound**: BlockHound is a Java agent that detects blocking calls in non-blocking threads at runtime:

```java
// Add to test setup
BlockHound.install();

// BlockHound throws an exception when a blocking call is detected
// on a thread that should not block (e.g., reactor-http-nio threads)
```

### 2. Forgetting to Subscribe

Nothing happens until you subscribe. A reactive chain without a subscriber is like writing SQL without executing it.

```java
// BAD — this does absolutely nothing
userRepository.save(user);                   // Returns Mono<User>, never subscribed

// GOOD — subscribe explicitly or let the framework subscribe
userRepository.save(user).subscribe();       // Explicit subscribe

// BETTER — return Mono from controller, Spring subscribes for you
@PostMapping("/users")
public Mono<User> createUser(@RequestBody User user) {
    return userRepository.save(user);        // Spring subscribes to the returned Mono
}
```

### 3. Using ThreadLocal in Reactive Chains

Reactive chains hop between threads. `ThreadLocal` values are lost:

```java
// BAD — ThreadLocal value is lost when flatMap switches threads
ThreadLocal<String> traceId = new ThreadLocal<>();
traceId.set("abc-123");

Mono.just("data")
    .flatMap(d -> {
        // traceId.get() is likely null here — different thread
        return callService(traceId.get());
    });

// GOOD — use Reactor Context
Mono.deferContextual(ctx -> {
        String trace = ctx.get("traceId");
        return callService(trace);
    })
    .contextWrite(Context.of("traceId", "abc-123"));
```

### 4. Error Swallowing

Unhandled errors in reactive chains are silently dropped, making debugging difficult:

```java
// BAD — error is silently swallowed
flux.subscribe(
    item -> process(item)
    // No error handler! Errors disappear silently
);

// GOOD — always handle errors
flux.subscribe(
    item -> process(item),
    error -> log.error("Stream error", error),
    () -> log.info("Stream completed")
);

// ALSO GOOD — handle in the chain
flux.doOnError(error -> log.error("Error in stream", error))
    .onErrorResume(error -> Flux.empty())
    .subscribe();
```

### 5. Excessive Operator Chaining

Long operator chains become unreadable. Break them into named methods:

```java
// BAD — hard to read and debug
return userRepository.findById(id)
    .flatMap(user -> orderRepository.findByUserId(user.getId())
        .collectList()
        .flatMap(orders -> Flux.fromIterable(orders)
            .flatMap(order -> productService.getProducts(order.getProductIds())
                .collectList()
                .map(products -> new OrderDTO(order, products)))
            .collectList()
            .map(orderDTOs -> new UserProfile(user, orderDTOs))));

// GOOD — decompose into readable methods
return userRepository.findById(id)
    .flatMap(this::buildUserProfile);

private Mono<UserProfile> buildUserProfile(User user) {
    return orderRepository.findByUserId(user.getId())
        .flatMap(this::enrichOrderWithProducts)
        .collectList()
        .map(orderDTOs -> new UserProfile(user, orderDTOs));
}

private Mono<OrderDTO> enrichOrderWithProducts(Order order) {
    return productService.getProducts(order.getProductIds())
        .collectList()
        .map(products -> new OrderDTO(order, products));
}
```

### 6. Misusing Hot Publishers

Subscribing to a hot publisher after it has emitted can cause lost events:

```java
// BAD — late subscriber misses events
Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
sink.tryEmitNext("event-1");
sink.tryEmitNext("event-2");

sink.asFlux().subscribe(System.out::println);  // Prints nothing — events already emitted

// GOOD — use replay to cache events for late subscribers
Sinks.Many<String> replaySink = Sinks.many().replay().all();
replaySink.tryEmitNext("event-1");
replaySink.tryEmitNext("event-2");

replaySink.asFlux().subscribe(System.out::println);  // Prints event-1, event-2
```

---

## 11. Common Senior Interview Questions

**Q1: What is backpressure, and why is it important in reactive systems?**

Backpressure is a flow control mechanism where a data consumer signals to the producer how much data it can handle. Without backpressure, a fast producer can overwhelm a slow consumer, causing buffer overflows, memory exhaustion, or dropped data.

In Reactive Streams, backpressure is implemented through the `Subscription.request(n)` method. The subscriber explicitly requests `n` items, and the publisher must not emit more than requested. This creates a pull-based protocol where the consumer controls the data flow rate.

Strategies for handling backpressure include buffering (with bounded limits), dropping excess items, keeping only the latest item, or signaling an error. The choice depends on the use case: metrics can tolerate drops, financial transactions cannot.

---

**Q2: Explain the difference between `Mono` and `Flux`. When would you use each?**

`Mono<T>` represents an asynchronous computation that produces 0 or 1 element, then terminates (with `onComplete` or `onError`). Use `Mono` for single-result operations: fetching one entity by ID, saving an entity, performing a single HTTP call, or representing a void operation (`Mono<Void>`).

`Flux<T>` represents an asynchronous sequence of 0 to N elements. Use `Flux` for multiple-result operations: fetching a list of entities, streaming events, processing items from a message queue, or any scenario with multiple emissions.

Key distinction: `Mono` is semantically equivalent to `CompletableFuture` or `Optional` in the reactive world, while `Flux` is equivalent to `Stream` or `Iterable`. A `Mono` can be viewed as a `Flux` of at most one element, and Reactor provides `Mono.from(Flux)` / `Flux.from(Mono)` for conversion.

---

**Q3: What is the difference between cold and hot publishers? Give examples of each.**

A cold publisher generates data fresh for each subscriber. The data sequence starts from the beginning for every new subscription, and each subscriber is independent. Examples: an HTTP request triggered on subscribe, a database query, `Flux.range()`, `Flux.fromIterable()`. Most Reactor operators produce cold publishers by default.

A hot publisher emits data regardless of subscribers. Late subscribers miss elements that were emitted before they subscribed. The data exists independently of any subscription. Examples: mouse click events, a WebSocket feed, a Kafka topic, `Flux.interval()` shared with `.share()`, a `Sinks.Many` multicast.

You convert cold to hot using operators like `.share()` (multicast with auto-connect), `.publish().autoConnect(n)` (start when n subscribers connect), or `.publish().refCount(n)` (start with n subscribers, stop when all unsubscribe).

---

**Q4: How does `flatMap` differ from `concatMap` and `flatMapSequential`? When do you use each?**

All three transform each element into an inner publisher, but they differ in subscription strategy and ordering:

- `flatMap`: Subscribes to all inner publishers eagerly (up to a configurable concurrency limit, default 256). Results are interleaved as they arrive, with no ordering guarantee. Use for maximum throughput when order does not matter.

- `concatMap`: Subscribes to inner publishers one at a time, sequentially. The next inner publisher is not subscribed until the previous one completes. Preserves ordering but limits throughput. Use when order matters and sequential processing is acceptable.

- `flatMapSequential`: Subscribes to inner publishers eagerly (like `flatMap`) but reorders results to match the original sequence. Use when you want both ordering and concurrent execution.

Example: fetching user details for a list of IDs. If display order does not matter, use `flatMap` for speed. If you need results in the same order as the input IDs, use `flatMapSequential` for concurrent fetching with ordered output.

---

**Q5: How do you handle errors in a reactive pipeline? Describe the main strategies.**

Reactive error handling uses operators instead of try-catch:

- **`onErrorReturn(value)`**: Replace the error with a static fallback value and complete normally. Simple but inflexible.
- **`onErrorResume(fn)`**: Replace the error with an alternative publisher. Can inspect the exception type and provide different fallbacks per error. Most flexible.
- **`onErrorMap(fn)`**: Transform the exception into a different exception type. Useful for wrapping low-level exceptions in domain exceptions.
- **`retry(n)`**: Re-subscribe to the source up to n times on error.
- **`retryWhen(spec)`**: Advanced retry with configurable backoff, jitter, filtering by exception type, and max attempts.
- **`timeout(duration)`**: Signal `TimeoutException` if no items arrive within the duration.
- **`doOnError(consumer)`**: Side effect only — log or record metrics without altering the error propagation.

Best practices: always define error handling at the appropriate level, never swallow errors silently, use `retryWhen` with backoff for transient failures, and set timeouts on external calls.

---

**Q6: Why can you not use `ThreadLocal` in a reactive chain? What is the alternative?**

Reactive chains execute across multiple threads. A `map` operator might run on one thread, while a subsequent `flatMap` runs on a completely different thread (especially after `publishOn` or `subscribeOn` boundaries). `ThreadLocal` values are bound to a specific thread, so they become invisible or incorrect when execution switches threads.

The alternative is Reactor's `Context`, an immutable key-value store attached to the subscription, not to a thread. Context flows through the entire reactive chain from the subscriber up to the publisher (it propagates upstream). You write context with `contextWrite(Context.of("key", "value"))` and read it with `Mono.deferContextual(ctx -> ...)`.

This is commonly used for propagating request-scoped data like trace IDs, authentication tokens, or MDC logging context in reactive applications.

---

**Q7: What happens if you call a blocking method inside a reactive pipeline? How can you detect and prevent this?**

Calling a blocking method (JDBC query, `Thread.sleep()`, `File.read()`, `synchronized` block) inside a reactive pipeline blocks the event loop thread. Since Netty uses a small number of event loop threads (typically one per CPU core), blocking even one thread can dramatically reduce throughput and cause the application to appear frozen for other requests.

Detection:
- **BlockHound**: A Java agent that throws an exception whenever a blocking call is detected on a non-blocking thread. Install it in tests: `BlockHound.install()`.
- **Reactor debug mode**: `Hooks.onOperatorDebug()` produces enhanced stack traces that help identify where blocking calls occur.
- **Monitoring**: Watch for disproportionately long event loop task execution times.

Prevention:
- Offload blocking calls to `Schedulers.boundedElastic()` using `.subscribeOn(Schedulers.boundedElastic())`.
- Replace blocking libraries with reactive alternatives (R2DBC instead of JDBC, reactive Redis, reactive Kafka).
- Use `Mono.fromCallable()` wrapped with the appropriate scheduler for unavoidable blocking operations.

---

**Q8: Explain the Reactive Streams specification. What are its core interfaces and rules?**

The Reactive Streams specification defines four interfaces for asynchronous stream processing with non-blocking backpressure:

- `Publisher<T>`: Produces a potentially unbounded number of elements. Has one method: `subscribe(Subscriber)`.
- `Subscriber<T>`: Consumes elements. Methods: `onSubscribe(Subscription)`, `onNext(T)`, `onError(Throwable)`, `onComplete()`.
- `Subscription`: The link between publisher and subscriber. Methods: `request(long n)` for demand signaling and `cancel()` to terminate.
- `Processor<T,R>`: Both a subscriber and a publisher, acting as a processing stage.

Key rules: `onSubscribe` is called first and exactly once. A publisher must not emit more items than requested via `request(n)`. `onError` and `onComplete` are terminal signals — no further signals after either. Null elements are forbidden. Demand is cumulative. These rules are verified by the TCK (Technology Compatibility Kit).

Since Java 9, these interfaces are mirrored in `java.util.concurrent.Flow`, making reactive streams a standard JDK concept.

---

**Q9: When would you choose reactive programming over virtual threads in a new Java 21+ project?**

Choose reactive when:
- The application involves streaming data (SSE, WebSockets, event feeds) where `Flux` naturally models the domain
- Backpressure is a core requirement (e.g., consuming from a fast Kafka topic, rate-limited APIs)
- Complex event composition is needed (`merge`, `zip`, `switchMap`, `combineLatest`)
- The team has reactive experience and the ecosystem is already reactive (reactive MongoDB, R2DBC, reactive Redis)

Choose virtual threads when:
- Standard request-response microservices (most CRUD applications)
- The team prefers imperative, debuggable code with familiar stack traces
- The application uses blocking libraries (JDBC, JPA, synchronous HTTP clients)
- Backpressure and streaming are not requirements
- Migrating a legacy blocking application to handle higher concurrency

In practice, many organizations adopt a hybrid approach: virtual threads for most endpoints, reactive for streaming and event-driven features. The key insight is that virtual threads and reactive solve the same scalability problem through different means — virtual threads make blocking cheap, while reactive avoids blocking entirely.

---

**Q10: How do you test reactive code effectively?**

Testing reactive code requires specialized tools because assertions must validate asynchronous signal sequences:

- **`StepVerifier`**: The primary tool. Creates a subscriber that asserts signals step by step: `expectNext(value)`, `expectError(type)`, `expectComplete()`. Always call `.verify()` to trigger execution.
- **`StepVerifier.withVirtualTime()`**: Tests time-dependent operators (delays, intervals, timeouts) without waiting real time. The `Flux` must be created inside the supplier lambda for virtual time to work.
- **`TestPublisher`**: Manually controls signal emission. Useful for testing how your operators handle various publisher behaviors, including misbehaving publishers.
- **`WebTestClient`**: Integration testing for WebFlux endpoints. Provides fluent assertions for response status, headers, and body.
- **`BlockHound`**: Install in test setup to catch accidental blocking calls.

Best practices: test both happy path and error paths, use `assertNext` with AssertJ for complex assertions, set timeouts on `verify()` to prevent tests from hanging, and decompose complex chains into testable methods.
