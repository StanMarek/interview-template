# Foundations and Reactive Streams

> **Goal:** Understand what reactive programming solves, where it fits, and what the Reactive Streams contract actually guarantees.

---

## 1. Why Reactive Exists

Reactive programming is an answer to a specific systems problem:

- traditional request handling often parks a thread while waiting on I/O
- blocked threads still consume memory and scheduler attention
- at high concurrency, the thread-per-request model becomes less predictable
- event-loop and callback-based systems avoid that cost by not blocking the worker thread

That does **not** mean reactive code is automatically faster. Spring’s WebFlux docs explicitly say reactive and non-blocking code does not generally make applications run faster; the main expected benefit is scaling with a small, fixed number of threads and less memory when the workload includes enough latency.

### Good Fit

- many concurrent I/O-bound requests
- SSE, WebSockets, or other streaming endpoints
- fan-out to multiple downstream services
- pipelines where backpressure matters
- systems already built around reactive drivers or messaging

### Usually Overkill

- simple CRUD services
- mostly blocking infrastructure
- CPU-bound workloads
- teams that do not already understand reactive failure modes
- cases where virtual threads solve the concurrency problem with simpler code

---

## 2. Reactive Manifesto in One Minute

The Reactive Manifesto describes systems that are:

- **responsive**
- **resilient**
- **elastic**
- **message-driven**

For interviews, the key point is that message-driven boundaries and non-blocking communication are what make the other three easier to achieve under load and failure.

---

## 3. Reactive Streams

Reactive Streams is the JVM standard for asynchronous stream processing with non-blocking backpressure.

### Core Interfaces

```java
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    void onSubscribe(Subscription s);
    void onNext(T t);
    void onError(Throwable t);
    void onComplete();
}

public interface Subscription {
    void request(long n);
    void cancel();
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {
}
```

### Rules That Matter

1. `onSubscribe` happens first and exactly once.
2. A publisher must not emit more items than were requested.
3. `onError` and `onComplete` are terminal.
4. `null` is forbidden.
5. Demand is additive.
6. `request(Long.MAX_VALUE)` means effectively unbounded demand.
7. `cancel()` is best-effort, not an "instant hard stop" guarantee.

### The Important Mental Model

Reactive Streams is not just "async iteration". Its defining feature is **backpressure**:

- the consumer signals demand with `request(n)`
- the producer must respect that demand
- this prevents unbounded buffering across async boundaries

That contract is the reason `Flux` and similar types are more than fancy callbacks.

---

## 4. `Flow` in the JDK

Since Java 9, `java.util.concurrent.Flow` mirrors Reactive Streams with 1:1 semantic equivalence.

```java
Flow.Publisher<T>
Flow.Subscriber<T>
Flow.Subscription
Flow.Processor<T, R>
```

In practice:

- many libraries still expose `org.reactivestreams` types directly
- adapters exist between the two worlds
- conceptually, they are the same protocol

---

## 5. Senior-Level Framing

If asked "what is reactive programming?" avoid vague answers like "programming with streams" or "programming asynchronously".

A stronger answer is:

> Reactive programming is a model for composing asynchronous, non-blocking pipelines where data is represented as a stream of signals and flow control is built into the protocol through backpressure.

That answer makes it clear that:

- the model is about **signals**, not just values
- it is meant for **async boundaries**
- backpressure is a first-class part of the contract

---

## 6. Short Interview Prompts

**What problem does backpressure solve?**
It prevents a fast producer from overwhelming a slower consumer with unbounded buffering.

**Is Reactive Streams about network protocols?**
No. It is a minimal JVM contract for async stream processing. Network layers can build on it, but that is not its main abstraction level.

**Is `Flow` different from Reactive Streams?**
Not semantically. The official Reactive Streams site describes `Flow` as 1:1 semantically equivalent.
