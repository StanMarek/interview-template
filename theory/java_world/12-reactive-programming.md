# Reactive Programming — Senior Interview Study Map

> **Purpose:** A shorter landing page for reactive study. The detailed notes now live in topic files under `12-reactive-programming/`.
>
> **Reference point:** April 2026. Claims that can drift over time were rechecked against official Reactor, Spring, Reactive Streams, R2DBC, Spring Boot, and OpenJDK sources.

---

## Reading Path

1. [Foundations and Reactive Streams](12-reactive-programming/01-foundations-and-reactive-streams.md)
2. [Reactor Core](12-reactive-programming/02-reactor-core.md)
3. [Schedulers, Backpressure, and Context](12-reactive-programming/03-schedulers-backpressure-and-context.md)
4. [Spring WebFlux and R2DBC](12-reactive-programming/04-spring-webflux-and-r2dbc.md)
5. [Testing, Debugging, and Pitfalls](12-reactive-programming/05-testing-debugging-and-pitfalls.md)
6. [Virtual Threads and the Wider Ecosystem](12-reactive-programming/06-virtual-threads-and-ecosystem.md)

---

## What To Remember

- Reactive programming is mainly about **asynchronous composition, streaming, and backpressure**, not "making code faster" by magic.
- In 2026, the serious answer is **not** "reactive or virtual threads?" It is usually "pick the model that matches the workload".
- Spring’s own docs still position WebFlux as valuable when you need **non-blocking I/O, streaming, or a small fixed thread model**, while also explicitly saying the shift is unnecessary for many applications.
- Reactor remains the Spring default reactive library, but the important interoperability contract is still **Reactive Streams** / `Publisher`.
- R2DBC has a stable spec and Spring support, but it is still **not JPA with reactive syntax**. Its trade-offs are different and worth knowing.

---

## Suggested Interview Order

- Start with the protocol: `Publisher`, `Subscriber`, `Subscription`, backpressure.
- Move to Reactor vocabulary: `Mono`, `Flux`, `flatMap`, `concatMap`, `publishOn`, `subscribeOn`, `Context`.
- Then explain Spring usage: WebFlux controllers, `WebClient`, SSE/WebSocket, and why blocking code is a mismatch for event-loop threads.
- Finish with the 2026 framing: where virtual threads simplify life, and where reactive still has the cleaner model.
