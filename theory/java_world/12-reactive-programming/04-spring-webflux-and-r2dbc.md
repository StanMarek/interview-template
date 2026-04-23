# Spring WebFlux and R2DBC

> **Goal:** Understand how Spring applies reactive programming on the web and database sides, and where the boundaries with blocking infrastructure really are.

---

## 1. What Spring WebFlux Is

Spring WebFlux is Spring’s reactive web stack.

The Spring Framework docs position it as:

- fully non-blocking
- based on Reactive Streams backpressure
- able to run on Netty and on supported servlet-based runtimes such as Tomcat and Jetty

The same docs also say something many people skip:

- for a wide range of applications, the shift is unnecessary
- reactive and non-blocking code do not generally make applications faster
- the main benefit is predictable scaling with a small, fixed number of threads when latency-heavy I/O is involved

That is the 2026 framing you should use in interviews.

---

## 2. Supported Programming Models

WebFlux supports two main styles:

- annotated controllers
- functional endpoints

One subtle point from the Spring docs: both Spring MVC and WebFlux can return reactive types from controller methods. What really distinguishes WebFlux is the non-blocking execution model and support for reactive request handling end to end.

### Annotated Controller Example

```java
@RestController
@RequestMapping("/api/users")
class UserController {

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    Mono<ResponseEntity<User>> getUser(@PathVariable String id) {
        return userService.findById(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping
    Flux<User> listUsers() {
        return userService.findAll();
    }
}
```

### Functional Endpoint Example

```java
@Bean
RouterFunction<ServerResponse> routes(UserHandler handler) {
    return RouterFunctions.route()
        .GET("/api/users/{id}", handler::getUser)
        .GET("/api/users", handler::getAllUsers)
        .build();
}
```

The choice is mostly about style and local conventions, not raw capability.

---

## 3. Threading Model

The Spring Framework docs describe WebFlux as using a small number of event-loop worker threads on non-blocking servers.

Two important consequences:

1. blocking code is a mismatch for request-handling threads
2. if you see more threads, it is often because schedulers or third-party libraries introduced them

Spring also documents that:

- `WebClient` itself operates in event-loop style
- if both client and server use Reactor Netty, they share event-loop resources by default

---

## 4. `WebClient`

`WebClient` is Spring’s reactive HTTP client.

The current reference docs describe it as:

- fully non-blocking
- streaming-friendly
- built on the same codec infrastructure as WebFlux server-side processing

It can use several underlying clients, including:

- Reactor Netty
- JDK `HttpClient`
- Jetty Reactive HttpClient
- Apache HttpComponents

### Typical Usage

```java
Mono<Product> product = webClient.get()
    .uri("/products/{id}", id)
    .retrieve()
    .onStatus(HttpStatusCode::is4xxClientError,
        response -> Mono.error(new NotFoundException(id)))
    .onStatus(HttpStatusCode::is5xxServerError,
        response -> Mono.error(new ServiceException("Remote failure")))
    .bodyToMono(Product.class);
```

Spring’s docs also note that by default `retrieve()` turns `4xx` and `5xx` responses into `WebClientResponseException` subclasses unless you override that with `onStatus`.

---

## 5. Streaming Endpoints

Reactive HTTP becomes most obviously useful when the response is naturally a stream.

### SSE

```java
@GetMapping(value = "/stream/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
Flux<ServerSentEvent<String>> streamEvents() {
    return Flux.interval(Duration.ofSeconds(1))
        .map(i -> ServerSentEvent.<String>builder()
            .id(String.valueOf(i))
            .event("heartbeat")
            .data("tick " + i)
            .build());
}
```

### WebSocket

```java
class ChatHandler implements WebSocketHandler {

    private final Sinks.Many<String> sink =
        Sinks.many().multicast().onBackpressureBuffer();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Mono<Void> input = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(message -> sink.tryEmitNext(message))
            .then();

        Mono<Void> output = session.send(
            sink.asFlux().map(session::textMessage)
        );

        return Mono.when(input, output);
    }
}
```

These are the kinds of workloads where reactive code is naturally expressive.

---

## 6. WebFlux vs Spring MVC in 2026

| Topic | Spring MVC | Spring WebFlux |
|-------|------------|----------------|
| Default mental model | imperative | reactive |
| Blocking allowed | yes | assumed no |
| Best fit | general CRUD and broad ecosystem integration | streaming, non-blocking I/O, reactive pipelines |
| Debugging ergonomics | simpler | harder |
| Backpressure | not built in | built in via reactive types |
| Team ramp-up cost | lower | higher |

Avoid fake benchmark-style claims such as "MVC handles X requests, WebFlux handles Y". Official docs do not make that promise, and real numbers are workload-dependent.

---

## 7. Why JDBC Is a Problem Inside WebFlux

JDBC is blocking. If you execute JDBC work directly on an event-loop thread, you break the concurrency model.

```java
Mono.fromCallable(() -> jdbcTemplate.queryForObject("select ...", User.class))
    .subscribeOn(Schedulers.boundedElastic());
```

That wrapper is the escape hatch, not the ideal architecture.

---

## 8. What R2DBC Is

R2DBC is the Reactive Relational Database Connectivity specification for SQL data access using reactive patterns.

The official R2DBC spec and Spring docs emphasize:

- non-blocking database access
- Reactive Streams integration
- deferred connection creation
- cancellable connection acquisition

One spec detail worth knowing:

- a `Connection` is **not** safe for concurrent state-changing use by multiple subscribers

---

## 9. Spring’s R2DBC Layers

There are two different levels worth remembering.

### Spring Framework R2DBC Core

`DatabaseClient` is the low-level fluent API for SQL execution and row mapping.

```java
Flux<User> users = databaseClient.sql("""
        select id, name, email
        from users
        where active = true
        """)
    .map((row, metadata) -> new User(
        row.get("id", Long.class),
        row.get("name", String.class),
        row.get("email", String.class)
    ))
    .all();
```

### Spring Data R2DBC

Spring Data Relational now positions `R2dbcEntityTemplate` as the central entry point for entity-oriented persistence, with repositories on top.

```java
Flux<Person> people = template.select(Person.class)
    .matching(query(where("lastname").is("Smith")))
    .all();
```

Use:

- `DatabaseClient` for low-level SQL control
- `R2dbcEntityTemplate` for mapped entity access
- repositories for standard aggregate access patterns

---

## 10. Transactions and Pooling

Spring Framework provides `R2dbcTransactionManager`, which binds the connection to subscriber `Context`.

Key implication from the docs:

- framework classes such as `DatabaseClient` already participate correctly
- code that bypasses Spring helpers and calls `ConnectionFactory.create()` directly can miss transaction integration

For pooling, Spring’s docs point to **third-party** pools such as `r2dbc-pool`. Spring’s own connection-factory implementations are primarily for testing and simple cases, not as a full production pool.

---

## 11. R2DBC Caveats

R2DBC is an established option in the Spring ecosystem, but do not sell it as "JPA without threads".

Typical caveats:

- fewer mature abstractions than the JPA/Hibernate world
- relational object mapping is simpler and less magical
- fewer ecosystem conveniences
- some teams still prefer JDBC or JPA plus virtual threads for mainstream CRUD services

That trade-off discussion is exactly what interviewers want from a senior answer.
