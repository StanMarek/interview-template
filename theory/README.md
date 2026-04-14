# Interview Theory - Top-Level Study Roadmap

A single index over the four study areas in this repo: **algorithms**, **java_world**, **node_world**, and **system_design**. The goal: tell you *what* to read, *in what order*, *how deep*, and *by when*.

If you are short on time, skip straight to the [Before-the-Interview 48-Hour Checklist](#before-the-interview--48-hour-checklist).

## 📂 Structure

| Area | Folder | Scope |
|------|--------|-------|
| Algorithms & Data Structures | [`algorithms/`](algorithms/) | DS patterns, complexity, LeetCode-style problem categories |
| Java Stack | [`java_world/`](java_world/) | Core Java, Spring, JVM, concurrency, testing, security |
| Node Stack | [`node_world/`](node_world/) | Node.js, TypeScript, NestJS/Express, V8, async, testing |
| System Design | [`system_design/`](system_design/) | Patterns, services, cloud mappings, worked-out designs |

Labels used throughout this document:

- **Must know** — expected at any level; do not walk into an interview without these
- **Nice to know** — commonly comes up at mid/senior rounds, or differentiates candidates
- **Elective** — deep-dive material; study only if the role explicitly targets it

Interview-level tags: **J** = junior, **M** = mid, **S** = senior.

---

## 🧮 Algorithms (`algorithms/`)

Recommended reading order matches the numeric prefix. Start with the master index, then go top-to-bottom. Every file is **must know** at some level — the level gate is the *depth* you can solve at, not whether you read the file.

| Order | Topic | Level | Label | Time |
|-------|-------|-------|-------|------|
| 0 | [00-master-index.md](algorithms/00-master-index.md) | J / M / S | Must know | 30 min |
| 1 | [01-arrays-and-strings.md](algorithms/01-arrays-and-strings.md) | J / M / S | Must know | 3-4 h |
| 2 | [02-hashmaps-and-hashsets.md](algorithms/02-hashmaps-and-hashsets.md) | J / M / S | Must know | 2-3 h |
| 3 | [03-linked-lists.md](algorithms/03-linked-lists.md) | J / M / S | Must know | 2 h |
| 4 | [04-stacks-and-queues.md](algorithms/04-stacks-and-queues.md) | J / M / S | Must know | 2 h |
| 5 | [05-trees.md](algorithms/05-trees.md) | J / M / S | Must know | 3-4 h |
| 6 | [06-heaps-priority-queues.md](algorithms/06-heaps-priority-queues.md) | M / S | Must know | 2 h |
| 7 | [07-graphs.md](algorithms/07-graphs.md) | M / S | Must know | 4-5 h |
| 8 | [08-tries.md](algorithms/08-tries.md) | M / S | Nice to know | 1-2 h |
| 9 | [09-dynamic-programming.md](algorithms/09-dynamic-programming.md) | M / S | Must know at S | 5-6 h |
| 10 | [10-binary-search.md](algorithms/10-binary-search.md) | J / M / S | Must know | 2 h |
| 11 | [11-backtracking.md](algorithms/11-backtracking.md) | M / S | Nice to know | 2-3 h |
| 12 | [12-intervals-sorting-greedy.md](algorithms/12-intervals-sorting-greedy.md) | M / S | Must know | 2-3 h |
| 13 | [13-bit-manipulation.md](algorithms/13-bit-manipulation.md) | M / S | Must know | 1-2 h |
| 14 | [14-math-and-number-theory.md](algorithms/14-math-and-number-theory.md) | M / S | Nice to know | 1-2 h |
| 15 | [15-string-algorithms.md](algorithms/15-string-algorithms.md) | S | Nice to know | 2-3 h |

**Total budget:** ~35-45 hours of reading + equal time practicing problems.

**Junior:** files 1-5, 10. Strong on complexity, two-pointer, hash-map frequency counting, BFS/DFS on trees.
**Mid:** add 6, 7, 9 (basic 1D DP), 12, 13 (basic XOR/mask tricks). Should implement Dijkstra, top-k with heap, classic DP tables.
**Senior:** everything, plus fluency on 9 (2D/optimization DP), 11, and comfortable with 14-15 (modular arithmetic, KMP/Z, rolling hash). Expect follow-ups on space optimization and alternative solutions.

---

## ☕ Java Stack (`java_world/`)

Read 01 (core Java) and 07 (OOP) first — they are prerequisites for everything else. After those two, work through the table top-to-bottom; the remaining order reflects interview frequency, not filename numbering.

| Order | Topic | Level | Label | Time |
|-------|-------|-------|-------|------|
| 1 | [01-core-java.md](java_world/01-core-java.md) | J / M / S | Must know | 4-5 h |
| 2 | [07-java-oop-abstraction-inheritance.md](java_world/07-java-oop-abstraction-inheritance.md) | J / M / S | Must know | 2-3 h |
| 3 | [02-spring-framework.md](java_world/02-spring-framework.md) | J / M / S | Must know | 5-6 h |
| 4 | [08-concurrency-multithreading.md](java_world/08-concurrency-multithreading.md) | M / S | Must know | 4-5 h |
| 5 | [04-databases.md](java_world/04-databases.md) | J / M / S | Must know | 3 h |
| 6 | [09-testing.md](java_world/09-testing.md) | J / M / S | Must know | 2-3 h |
| 7 | [06-design-patterns-practices.md](java_world/06-design-patterns-practices.md) | M / S | Must know | 3-4 h |
| 8 | [10-gc-jvm-performance.md](java_world/10-gc-jvm-performance.md) | M / S | Must know at S | 3 h |
| 9 | [03-cloud-microservices.md](java_world/03-cloud-microservices.md) | M / S | Must know | 3 h |
| 10 | [05-system-design.md](java_world/05-system-design.md) | M / S | Nice to know | 2-3 h |
| 11 | [11-java-versions-evolution.md](java_world/11-java-versions-evolution.md) | M / S | Nice to know | 2 h |
| 12 | [13-security-for-java.md](java_world/13-security-for-java.md) | M / S | Nice to know | 2-3 h |
| 13 | [12-reactive-programming.md](java_world/12-reactive-programming.md) | S | Elective | 3-4 h |

**Total budget:** ~40-50 hours.

**Junior:** 01, 07, 02 (beans/DI/scopes), 04 (JPA basics), 09 (JUnit/Mockito). Be able to explain `equals`/`hashCode`, generics, collections complexity.
**Mid:** add 08, 06, 03, 10 (GC basics). Comfortable with transactions, Spring Boot auto-config, thread-safe collections.
**Senior:** all of the above plus deep 08 (memory model, locks, executors), 10 (G1/ZGC trade-offs), 11, 13. 12 only for reactive roles.

---

## 🟢 Node Stack (`node_world/`)

Same rule as Java: 01 and 02 are foundations. If you are interviewing for a Node role *and* know Java already, you can skim files that share concepts (testing, design patterns, databases) and focus on the Node-specific parts (event loop, streams, TS types).

| Order | Topic | Level | Label | Time |
|-------|-------|-------|-------|------|
| 1 | [01-core-nodejs.md](node_world/01-core-nodejs.md) | J / M / S | Must know | 4-5 h |
| 2 | [02-typescript-type-system.md](node_world/02-typescript-type-system.md) | J / M / S | Must know | 3-4 h |
| 3 | [07-async-programming.md](node_world/07-async-programming.md) | J / M / S | Must know | 3 h |
| 4 | [03-nestjs-express.md](node_world/03-nestjs-express.md) | J / M / S | Must know | 4-5 h |
| 5 | [04-databases.md](node_world/04-databases.md) | J / M / S | Must know | 3 h |
| 6 | [08-testing.md](node_world/08-testing.md) | J / M / S | Must know | 2-3 h |
| 7 | [06-design-patterns-practices.md](node_world/06-design-patterns-practices.md) | M / S | Must know | 3 h |
| 8 | [09-v8-performance.md](node_world/09-v8-performance.md) | M / S | Must know at S | 3 h |
| 9 | [05-cloud-microservices.md](node_world/05-cloud-microservices.md) | M / S | Must know | 3 h |
| 10 | [10-security.md](node_world/10-security.md) | M / S | Must know | 2-3 h |
| 11 | [11-system-design.md](node_world/11-system-design.md) | M / S | Nice to know | 2-3 h |
| 12 | [12-reactive-streaming.md](node_world/12-reactive-streaming.md) | S | Elective | 2-3 h |
| 13 | [13-nodejs-typescript-evolution.md](node_world/13-nodejs-typescript-evolution.md) | M / S | Nice to know | 1-2 h |

**Total budget:** ~35-45 hours.

**Junior:** 01, 02, 07, 03 (basic controllers/providers), 04, 08.
**Mid:** add 06, 09, 05, 10.
**Senior:** everything, with real fluency on event-loop phases, TS advanced types, and backpressure in streams.

---

## 🏛️ System Design (`system_design/`)

This area has its own detailed index at [`system_design/README.md`](system_design/README.md). Below is the top-down reading order that pairs with the other three areas. Keep the existing README open while studying — it already carries the latency table and numbers you need to memorize.

### Phase 1 - Foundations (must know, all levels)

| Order | Topic | Level | Label | Time |
|-------|-------|-------|-------|------|
| 1 | [patterns/back-of-envelope.md](system_design/patterns/back-of-envelope.md) | J / M / S | Must know | 1 h |
| 2 | [patterns/cap-theorem.md](system_design/patterns/cap-theorem.md) | J / M / S | Must know | 1 h |
| 3 | [patterns/load-balancing.md](system_design/patterns/load-balancing.md) | J / M / S | Must know | 1 h |
| 4 | [patterns/caching.md](system_design/patterns/caching.md) | J / M / S | Must know | 1-2 h |
| 5 | [patterns/database-indexing.md](system_design/patterns/database-indexing.md) | J / M / S | Must know | 1 h |
| 6 | [patterns/sharding-partitioning.md](system_design/patterns/sharding-partitioning.md) | M / S | Must know | 1-2 h |
| 7 | [patterns/replication.md](system_design/patterns/replication.md) | M / S | Must know | 1 h |
| 8 | [patterns/consistent-hashing.md](system_design/patterns/consistent-hashing.md) | M / S | Must know | 1 h |
| 9 | [patterns/cdn.md](system_design/patterns/cdn.md) | J / M / S | Must know | 30 min |
| 10 | [patterns/api-gateway.md](system_design/patterns/api-gateway.md) | M / S | Must know | 1 h |
| 11 | [patterns/api-design-and-pagination.md](system_design/patterns/api-design-and-pagination.md) | J / M / S | Must know | 1 h |
| 12 | [patterns/concurrency-control.md](system_design/patterns/concurrency-control.md) | M / S | Must know | 1 h |
| 13 | [patterns/consensus-and-quorums.md](system_design/patterns/consensus-and-quorums.md) | M / S | Must know | 1 h |
| 14 | [patterns/streaming-semantics.md](system_design/patterns/streaming-semantics.md) | S | Must know at S | 1 h |

### Phase 2 - Messaging, Consistency, Resilience (mid / senior)

| Order | Topic | Level | Label | Time |
|-------|-------|-------|-------|------|
| 11 | [patterns/message-queues.md](system_design/patterns/message-queues.md) | M / S | Must know | 1-2 h |
| 12 | [patterns/pub-sub.md](system_design/patterns/pub-sub.md) | M / S | Must know | 1 h |
| 13 | [patterns/rate-limiting.md](system_design/patterns/rate-limiting.md) | M / S | Must know | 1 h |
| 14 | [patterns/circuit-breaker.md](system_design/patterns/circuit-breaker.md) | M / S | Must know | 30 min |
| 15 | [patterns/heartbeat.md](system_design/patterns/heartbeat.md) | M / S | Nice to know | 30 min |
| 16 | [patterns/idempotency.md](system_design/patterns/idempotency.md) | M / S | Must know | 1 h |
| 17 | [patterns/saga-pattern.md](system_design/patterns/saga-pattern.md) | M / S | Must know | 1 h |
| 18 | [patterns/outbox-pattern.md](system_design/patterns/outbox-pattern.md) | M / S | Nice to know | 30 min |
| 19 | [patterns/two-phase-commit.md](system_design/patterns/two-phase-commit.md) | S | Nice to know | 30 min |
| 20 | [patterns/distributed-locking.md](system_design/patterns/distributed-locking.md) | S | Nice to know | 1 h |
| 21 | [patterns/leader-election.md](system_design/patterns/leader-election.md) | S | Nice to know | 1 h |
| 22 | [patterns/service-discovery.md](system_design/patterns/service-discovery.md) | M / S | Must know | 30 min |
| 23 | [patterns/microservices-vs-monolith.md](system_design/patterns/microservices-vs-monolith.md) | J / M / S | Must know | 30 min |
| 24 | [patterns/proxy.md](system_design/patterns/proxy.md) | J / M / S | Must know | 30 min |
| 25 | [patterns/cqrs-event-sourcing.md](system_design/patterns/cqrs-event-sourcing.md) | S | Elective | 1-2 h |
| 26 | [patterns/bloom-filters.md](system_design/patterns/bloom-filters.md) | M / S | Nice to know | 30 min |
| 27 | [patterns/data-lakes-warehouses.md](system_design/patterns/data-lakes-warehouses.md) | S | Elective | 1 h |

### Phase 3 - Service Deep Dives

Pair each pattern with the matching service doc. Full list: [`system_design/services/`](system_design/services/). Minimum at mid-level: SQL, NoSQL, in-memory caches, object storage, message brokers.

### Phase 4 - Cloud Provider Mapping

Pick the one(s) you need. Full list: [`system_design/providers/`](system_design/providers/). Map every service you named during a design to the real offering (e.g. SQS vs Service Bus vs Pub/Sub).

### Phase 5 - Worked Designs (`diagrams/`)

Drill these against the clock. See [`system_design/interview-practice-tasks.md`](system_design/interview-practice-tasks.md). The four tracks (core, product, data_streaming, reliability, infrastructure) are indexed in detail in [`system_design/README.md`](system_design/README.md).

Rule of thumb:
- **Junior:** core track only (URL shortener, rate limiter, cache, notifications).
- **Mid:** core + any 3 from product track + 2 from reliability.
- **Senior:** at least one from each of the 5 tracks.

---

## 📅 Combined Schedules

### First 2 weeks — Minimum viable coverage

Goal: survive any generalist screen. ~2 hours/day.

| Day | Algorithms | Java/Node | System Design |
|-----|------------|-----------|----------------|
| 1 | [00-master-index](algorithms/00-master-index.md), [01-arrays-and-strings](algorithms/01-arrays-and-strings.md) | [01-core-*](java_world/01-core-java.md) | — |
| 2 | [02-hashmaps-and-hashsets](algorithms/02-hashmaps-and-hashsets.md) | core cont'd | — |
| 3 | [03-linked-lists](algorithms/03-linked-lists.md) | [07-oop](java_world/07-java-oop-abstraction-inheritance.md) / [02-typescript](node_world/02-typescript-type-system.md) | [back-of-envelope](system_design/patterns/back-of-envelope.md) |
| 4 | [04-stacks-and-queues](algorithms/04-stacks-and-queues.md) | [02-spring](java_world/02-spring-framework.md) / [03-nestjs-express](node_world/03-nestjs-express.md) | [cap-theorem](system_design/patterns/cap-theorem.md) |
| 5 | [05-trees](algorithms/05-trees.md) | spring / nest cont'd | [load-balancing](system_design/patterns/load-balancing.md) |
| 6 | [10-binary-search](algorithms/10-binary-search.md) | [04-databases](java_world/04-databases.md) / [04-databases](node_world/04-databases.md) | [caching](system_design/patterns/caching.md) |
| 7 | review + 3 problems | review | [database-indexing](system_design/patterns/database-indexing.md) |
| 8 | [06-heaps-priority-queues](algorithms/06-heaps-priority-queues.md) | [09-testing](java_world/09-testing.md) / [08-testing](node_world/08-testing.md) | [sharding-partitioning](system_design/patterns/sharding-partitioning.md) |
| 9 | [07-graphs](algorithms/07-graphs.md) part 1 | [08-concurrency](java_world/08-concurrency-multithreading.md) / [07-async](node_world/07-async-programming.md) | [replication](system_design/patterns/replication.md) |
| 10 | [07-graphs](algorithms/07-graphs.md) part 2 | concurrency/async cont'd | [consistent-hashing](system_design/patterns/consistent-hashing.md) |
| 11 | [12-intervals-sorting-greedy](algorithms/12-intervals-sorting-greedy.md) | [06-design-patterns-practices](java_world/06-design-patterns-practices.md) / [06-design-patterns-practices](node_world/06-design-patterns-practices.md) | [api-gateway](system_design/patterns/api-gateway.md), [cdn](system_design/patterns/cdn.md) |
| 12 | [09-dynamic-programming](algorithms/09-dynamic-programming.md) 1D | patterns cont'd | diagrams/core: [01_url_shortener](system_design/diagrams/core/01_url_shortener.md) |
| 13 | 3 mixed problems | [03-cloud-microservices](java_world/03-cloud-microservices.md) / [05-cloud-microservices](node_world/05-cloud-microservices.md) | diagrams/core: [02_rate_limiter](system_design/diagrams/core/02_rate_limiter.md) |
| 14 | full-area review | full-area review | diagrams/core: [03_distributed_cache](system_design/diagrams/core/03_distributed_cache.md) |

### First month — Mid-level solid

Continue after the 2-week plan. Target: a candidate solid at mid-level across every area.

- **Week 3 (algorithms depth + senior-ish patterns):** DP 2D, [08-tries](algorithms/08-tries.md), [11-backtracking](algorithms/11-backtracking.md). On the stack side: [10-gc-jvm-performance](java_world/10-gc-jvm-performance.md) / [09-v8-performance](node_world/09-v8-performance.md). System design: [message-queues](system_design/patterns/message-queues.md), [pub-sub](system_design/patterns/pub-sub.md), [rate-limiting](system_design/patterns/rate-limiting.md), [circuit-breaker](system_design/patterns/circuit-breaker.md), [idempotency](system_design/patterns/idempotency.md).
- **Week 4 (resilience + full mock designs):** [saga-pattern](system_design/patterns/saga-pattern.md), [outbox-pattern](system_design/patterns/outbox-pattern.md), [service-discovery](system_design/patterns/service-discovery.md), [microservices-vs-monolith](system_design/patterns/microservices-vs-monolith.md). Do 1 full design per day from the product track: [01_chat_system](system_design/diagrams/product/01_chat_system.md), [02_news_feed](system_design/diagrams/product/02_news_feed.md), [03_photo_sharing](system_design/diagrams/product/03_photo_sharing.md), etc. Stack: [13-security-for-java](java_world/13-security-for-java.md) / [10-security](node_world/10-security.md).

### Deep prep (2-3 months) — Senior-ready

Add on top of the first-month plan:

- All remaining algorithms files, plus re-doing [09-dynamic-programming](algorithms/09-dynamic-programming.md) with space-optimized variants.
- [12-reactive-programming](java_world/12-reactive-programming.md) / [12-reactive-streaming](node_world/12-reactive-streaming.md) if the role calls for it.
- Full [`system_design/diagrams/reliability/`](system_design/diagrams/reliability/) and [`system_design/diagrams/infrastructure/`](system_design/diagrams/infrastructure/) tracks.
- All remaining patterns ([two-phase-commit](system_design/patterns/two-phase-commit.md), [distributed-locking](system_design/patterns/distributed-locking.md), [leader-election](system_design/patterns/leader-election.md), [cqrs-event-sourcing](system_design/patterns/cqrs-event-sourcing.md), [data-lakes-warehouses](system_design/patterns/data-lakes-warehouses.md), [bloom-filters](system_design/patterns/bloom-filters.md)).
- All services in [`system_design/services/`](system_design/services/) (graph, time-series, vector, coordination).
- Pick one cloud provider and memorize the mapping: [aws.md](system_design/providers/aws.md), [azure.md](system_design/providers/azure.md), or [gcp.md](system_design/providers/gcp.md).

---

## ✅ Before the interview — 48-hour checklist

**T-48h — Refresh fundamentals (2-3 h)**

- [ ] [algorithms/00-master-index.md](algorithms/00-master-index.md) — scan the complexity / decision table
- [ ] [algorithms/01-arrays-and-strings.md](algorithms/01-arrays-and-strings.md), [algorithms/02-hashmaps-and-hashsets.md](algorithms/02-hashmaps-and-hashsets.md) — re-read the patterns section only
- [ ] [algorithms/05-trees.md](algorithms/05-trees.md), [algorithms/07-graphs.md](algorithms/07-graphs.md) — BFS/DFS templates
- [ ] [java_world/01-core-java.md](java_world/01-core-java.md) equals/hashCode, generics OR [node_world/01-core-nodejs.md](node_world/01-core-nodejs.md) event loop, [node_world/02-typescript-type-system.md](node_world/02-typescript-type-system.md) utility types

**T-24h — System design refresh (2-3 h)**

- [ ] [system_design/README.md](system_design/README.md) — re-memorize latency and size tables
- [ ] [system_design/patterns/back-of-envelope.md](system_design/patterns/back-of-envelope.md) — practice 2 estimations aloud
- [ ] [system_design/patterns/caching.md](system_design/patterns/caching.md), [system_design/patterns/load-balancing.md](system_design/patterns/load-balancing.md), [system_design/patterns/sharding-partitioning.md](system_design/patterns/sharding-partitioning.md), [system_design/patterns/replication.md](system_design/patterns/replication.md), [system_design/patterns/cap-theorem.md](system_design/patterns/cap-theorem.md) — skim headings + "when to use"
- [ ] One diagram walk-through from the matching track ([system_design/diagrams/core/01_url_shortener.md](system_design/diagrams/core/01_url_shortener.md) is the canonical warm-up)

**T-12h — Stack-specific polish (1-2 h)**

- [ ] Java track: [java_world/02-spring-framework.md](java_world/02-spring-framework.md), [java_world/08-concurrency-multithreading.md](java_world/08-concurrency-multithreading.md), [java_world/04-databases.md](java_world/04-databases.md) — re-read "gotchas" sections
- [ ] Node track: [node_world/03-nestjs-express.md](node_world/03-nestjs-express.md), [node_world/07-async-programming.md](node_world/07-async-programming.md), [node_world/04-databases.md](node_world/04-databases.md)
- [ ] Testing refresher: [java_world/09-testing.md](java_world/09-testing.md) or [node_world/08-testing.md](node_world/08-testing.md)

**T-2h — Final pass**

- [ ] Re-read the 5-step interview framework in [system_design/README.md](system_design/README.md)
- [ ] Say the latency numbers aloud once
- [ ] Solve one warm-up problem (easy array or hash-map) to get moving
- [ ] Stop studying. Water. Sleep.

---

## 🔗 Quick links

- [algorithms/](algorithms/) · [java_world/](java_world/) · [node_world/](node_world/) · [system_design/](system_design/)
- [system_design/README.md](system_design/README.md) — the detailed sub-index for patterns, services, providers, diagrams
- [system_design/interview-practice-tasks.md](system_design/interview-practice-tasks.md) — mock-interview driller
