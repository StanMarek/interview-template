# Virtual Threads and the Wider Ecosystem

> **Goal:** Frame reactive programming correctly in the Java 21 to 25 era and compare Reactor with the other reactive stacks that still matter.

---

## 1. Virtual Threads Changed The Default Answer

By 2026, reactive programming is no longer the default answer to "how do I scale Java I/O concurrency?"

Java now has:

- virtual threads, finalized in JDK 21
- reduced pinning problems for `synchronized` thanks to JEP 491 in JDK 24
- scoped values, finalized in JDK 25

But one important correction to many older study sheets:

- **structured concurrency is still preview in JDK 25**
- **scoped values are final in JDK 25**

Do not reverse those two facts in an interview.

---

## 2. Reactive vs Virtual Threads

| Topic | Virtual Threads | Reactive |
|-------|-----------------|----------|
| Programming style | imperative | declarative / stream-based |
| Blocking I/O libraries | good fit | mismatch unless wrapped away from event loops |
| Streaming | possible, but less natural | natural |
| Backpressure | manual | built into the model |
| Debugging | easier | harder |
| Team onboarding | easier | harder |
| Fan-out/fan-in composition | good, especially with structured concurrency once final | very strong today |

### Good Default With Virtual Threads

- CRUD-heavy services
- existing JDBC/JPA code
- teams that want imperative control flow
- codebases where reactive migration cost is not justified

### Reactive Still Strongest For

- SSE and WebSocket pipelines
- demand-sensitive streams
- message-driven and event-driven processing
- systems already built on reactive libraries end to end

That is the modern non-dogmatic answer.

---

## 3. Spring Boot and Virtual Threads

Spring Boot supports virtual threads with:

```properties
spring.threads.virtual.enabled=true
```

Two nuances matter:

1. this is a Boot capability, not a statement that "all Spring apps are now automatically virtual-thread based"
2. Boot’s task-execution docs explicitly mention support for **blocking execution in Spring WebFlux**

That means virtual threads can help around blocking adapters and task execution infrastructure, but they do **not** turn event-loop-based WebFlux handlers into a blocking programming model.

---

## 4. Reactor and Virtual Threads

Reactor did not become obsolete because Loom shipped.

The more accurate statement is:

- Reactor and Loom interoperate
- Reactor can use a virtual-thread-backed `boundedElastic` when configured to do so
- reactive remains the stronger abstraction when the workload is fundamentally a stream with backpressure

That is a complement story, not a winner-takes-all story.

---

## 5. Ecosystem Comparison

### Project Reactor

- Spring’s default reactive library
- `Mono` and `Flux`
- deep Spring integration
- Reactive Streams-native

### RxJava 3

- still important historically and on Android
- richer legacy operator ecosystem
- server-side mindshare is much lower than Reactor in Spring-heavy codebases

### Mutiny

- Quarkus-first reactive library
- `Uni` and `Multi`
- API organized around event groups, which many developers find easier to discover

### Kotlin Coroutines and `Flow`

- often the nicest developer experience in Kotlin codebases
- `suspend` plus `Flow<T>` gives reactive behavior with more imperative-looking code
- Spring can bridge Reactor and coroutines

---

## 6. Short Comparisons

| Library | Best fit |
|---------|----------|
| Reactor | Spring, WebFlux, JVM server-side reactive work |
| RxJava 3 | Android, existing Rx-heavy systems |
| Mutiny | Quarkus and Vert.x ecosystems |
| Kotlin Flow | Kotlin-first services and libraries |

If writing library code meant to interoperate broadly, target `Publisher` where practical.

---

## 7. Interview Answers Worth Memorizing

**Would you choose WebFlux or MVC for a new CRUD service in 2026?**
Usually MVC plus virtual threads if concurrency pressure exists, unless there is a clear streaming or end-to-end reactive requirement.

**Did virtual threads kill reactive programming?**
No. They removed reactive’s monopoly on scalable I/O concurrency, but not its advantages for streaming, backpressure, and event composition.

**What changed recently that seniors often misstate?**
- `synchronized` pinning is greatly reduced by JEP 491 in JDK 24
- scoped values are final in JDK 25
- structured concurrency is still preview in JDK 25
