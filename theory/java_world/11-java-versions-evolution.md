# Java Versions & Evolution — LTS-Focused Interview Sheet

> **Purpose:** A senior-level study sheet for the "what changed, why it matters, what interviewers ask" dimension of Java versions. This is **not** a syntax tutorial — see `01-core-java.md` for language deep dives and `08-concurrency-multithreading.md` for virtual threads / structured concurrency internals.
>
> **Reference point:** April 2026. JDK 25 (LTS) is GA since Sep 2025. JDK 26 ships Mar 2026 (non-LTS).

---

## 1. LTS-First Map

Oracle ships an LTS every **two years** (since 17). Non-LTS releases are stepping stones — features appear in preview on the way to LTS finalization. Most enterprises only track LTSs, so those are the versions interviewers actually care about.

| LTS | GA | Support window (Oracle) | What a senior must be able to discuss |
|-----|----|--------------------------|----------------------------------------|
| **8** | Mar 2014 | Premier ended, extended to 2030 (paid) | Lambdas, Streams, `Optional`, `java.time`, `CompletableFuture`, default methods, Metaspace replacing PermGen. Still the single most common "legacy" starting point. |
| **11** | Sep 2018 | Premier through 2026, extended to 2032 | JPMS (modules) — the big jump from 8; `var` for locals; new `HttpClient`; `String` & `Files` additions; removal of Java EE / CORBA modules; G1 default GC; flight recorder opened up; single-file source launcher. |
| **17** | Sep 2021 | Premier through 2026, extended to 2029 | Records, sealed types, pattern matching for `instanceof`, text blocks (all final); switch expressions (final since 14); strong encapsulation of JDK internals by default; `SecurityManager` deprecated for removal. |
| **21** | Sep 2023 | Premier through 2028, extended to 2031 | **Virtual threads (final)**, pattern matching for `switch` (final), record patterns (final), sequenced collections, generational ZGC. The "new normal" baseline for green-field services in 2024–2026. |
| **25** | Sep 2025 | Premier through 2030, extended to 2033 | Compact object headers (final), scoped values (final), module import declarations (final), compact source files & instance `main` (final), flexible constructor bodies (final), AOT method profiling, JFR CPU-time profiling. **Structured concurrency and primitive patterns still preview.** |

**Rule of thumb for "should we migrate?":** 8 → 11 is a module-system + dependency-compat slog. 11 → 17 is mostly smooth; gains are language ergonomics. 17 → 21 unlocks virtual threads — worth it for I/O-heavy services. 21 → 25 is mostly a GC/startup/footprint upgrade; language wins are modest.

### The release cadence in one paragraph

Since JDK 9 (2017), OpenJDK ships a feature release every **6 months** and an LTS every **two years** (17, 21, 25, 29…). **Preview features** need `--enable-preview` and can change between releases. **Incubator modules** (e.g., Vector API) live in a separate `jdk.incubator.*` namespace and can change freely. **Experimental flags** (e.g., early ZGC generational mode) require `-XX:+UnlockExperimentalVMOptions`. Interviewers will probe whether you understand that "preview ≠ production".

---

## 2. Features That Actually Come Up in Interviews

### 2.1 Language

| Feature | Final in | One-line interview summary |
|---------|----------|----------------------------|
| Lambdas + functional interfaces | 8 | The entry point to functional Java — `@FunctionalInterface`, capture semantics, effectively-final rule. |
| Streams | 8 | Lazy pipeline, intermediate vs terminal ops. Don't confuse with parallelism. See `01-core-java.md §5`. |
| `var` (local var inference) | 10 | Type still static; improves readability for long generic types. **Not** like JS `var`. Forbidden for fields, method params, method returns. |
| Text blocks | 15 | Smart indentation stripping. Useful for SQL/JSON/HTML literals. Escape rules: `\s` for trailing space, `\` for line continuation. |
| Records | 16 | Nominal tuples. Generate canonical ctor, accessors, `equals`/`hashCode`/`toString`. Implicitly `final`. Not a "value type" — still heap-allocated, still identity-ful. |
| `instanceof` patterns | 16 | `if (o instanceof String s && !s.isBlank())` — binds and flows the type. |
| Switch expressions | 14 | Arrow-form, yields a value, exhaustiveness-checked, no fallthrough. |
| Sealed types | 17 | Closed hierarchies (`permits X, Y, Z`). Each permitted type must be `final`, `sealed`, or `non-sealed`. Powers exhaustive pattern matching. |
| Pattern matching for `switch` | 21 | Type patterns + `when` guards + `case null` + record deconstruction — replaces the visitor pattern for most data-modelling cases. |
| Record patterns | 21 | `case Point(int x, int y)` destructuring; nests arbitrarily. |
| Unnamed variables/patterns `_` | 22 | For unused lambda params, catch vars, pattern bindings. |
| Module import declarations | 25 | `import module java.base;` — pulls every exported package. Useful for scripts/teaching, not production modules. |
| Compact source files + instance `main` | 25 | Single-file programs without `class` wrapper and without `static main`. Scripting ergonomic; rarely relevant to business code. |
| Flexible constructor bodies | 25 | Statements allowed before `this(...)` / `super(...)` — lets you validate inputs before chaining. |
| Primitive types in patterns | still preview in 25 (JEP 507) | `case int i when i > 0`, range-checked `instanceof byte b`. Don't rely on it in 25 production. |

Cross-link: Records, sealed types, and pattern matching are covered with examples in `01-core-java.md §7`. Don't redo that material in an interview — cite it.

### 2.2 Concurrency

| Feature | Status | Senior-level summary |
|---------|--------|----------------------|
| `CompletableFuture` | final since 8 | Non-blocking composition. Default pool is `ForkJoinPool.commonPool` — **always pass a custom executor** for I/O. |
| Virtual threads | **final in 21** (JEP 444) | Cheap (~1 KB stack), carrier-mounted, **for I/O only**, disposable — never pool them. JEP 491 (JDK 24) removed `synchronized` pinning; keep `synchronized` unless you need `ReentrantLock` features. |
| Scoped values | **final in 25** (JEP 506) | Immutable, dynamically-scoped context. Replaces `ThreadLocal` for per-request data, especially with millions of VTs. |
| Structured concurrency | **still preview in 25** (JEP 505, 5th preview) | Treat a fan-out as one unit of work; auto-cancellation on failure. API reshaped between 21 and 25 (now `StructuredTaskScope.open(Joiner...)`). Do **not** claim it's production-final yet. |

**See `08-concurrency-multithreading.md §3` for virtual threads / pinning / structured concurrency internals.** In an interview, cite the key facts, not the code.

### 2.3 Performance / GC

| Topic | What to know |
|-------|--------------|
| **Default GC** | G1 since JDK 9. Region-based, "garbage first", mixed young+old collections. |
| **G1 tuning defaults** | `-XX:+UseStringDeduplication` default since 18. Heap ergonomics do a reasonable job — tune `-Xmx`, `-XX:MaxGCPauseMillis` (default 200 ms) and leave the rest alone. |
| **ZGC** | Sub-ms pauses, multi-TB heaps via colored pointers + load barriers. **Generational ZGC became default mode in 23 (JEP 474); non-generational mode removed in 24 (JEP 490).** Generational gives 2–10× less CPU for the same pause target. |
| **Shenandoah** | Red Hat's low-pause alternative. Generational mode experimental in 24 (JEP 404). Similar goals to ZGC, different implementation (Brooks pointers historically, load-reference barriers now). Pick ZGC if you're on Oracle/mainline builds; Shenandoah is fine on OpenJDK from Red Hat/Amazon. |
| **Compact object headers** | Experimental in 24 (JEP 450), **final in 25 (JEP 519)**. Shrinks headers from 96–128 bits to 64 bits. ~10–22 % heap reduction in object-heavy workloads. Enable with `-XX:+UseCompactObjectHeaders`. |
| **AOT class loading & method profiling** | JEP 483 (JDK 24) caches loaded/linked classes; JEP 515 (JDK 25) captures method profiles AOT. Net effect: faster startup/warmup — matters for serverless and CLI workloads. |
| **Biased locking** | Disabled by default in 15 (JEP 374), then fully removed. If someone asks why, say: "no longer pays for itself on modern CPUs and complicated the safepoint machinery." |

### 2.4 APIs

| API | Introduced / changed | Notes |
|-----|----------------------|-------|
| **Stream additions** | `takeWhile` / `dropWhile` / `iterate` (9), `Stream.toList()` (16 — unmodifiable), `mapMulti` (16), `Collectors.teeing` (12), **Stream Gatherers final in 24 (JEP 485)**. | Prefer `stream.toList()` over `collect(toList())` for new code. Use Gatherers for sliding windows, running totals, batching. |
| **Optional evolution** | `ifPresentOrElse` (9), `or` (9), `stream()` (9), `orElseThrow()` no-arg (10). | Return type only. Never a field or parameter. `.get()` is a smell. |
| **`HttpClient`** | Incubator 9, **final in 11**. HTTP/2 and WebSocket. | Replaces legacy `HttpURLConnection`. JDK 26 adds HTTP/3 (JEP 517). |
| **Foreign Function & Memory (FFM)** | Final in **22 (JEP 454)**. | Replacement for JNI and `sun.misc.Unsafe` memory access. `Arena`, `MemorySegment`, `Linker`. JNI is being restricted (JEP 472) in favour of FFM. |
| **Class-File API** | Final in **24 (JEP 484)**. | Official replacement for ASM/Javassist for bytecode manipulation. |
| **Sequenced collections** | **21** — `SequencedCollection`, `SequencedSet`, `SequencedMap`. | `getFirst()`, `getLast()`, `reversed()`, `addFirst/Last`. Retrofitted across `List`, `Deque`, `LinkedHashMap`, etc. |
| **Vector API** | Still incubating (11th incubator in 26). | SIMD operations. Don't claim it's production-stable. |
| **KDF API** | Preview in 24, **final in 25 (JEP 510)**. | HKDF etc. — first-class key-derivation. |

### 2.5 Removals / Deprecations That Still Bite

| Thing | Fate | Why you care |
|-------|------|--------------|
| **Java EE & CORBA modules** (JAXB, JAX-WS, `java.activation`, `javax.transaction`, `java.corba`) | Removed in **11** | Biggest 8 → 11 migration pain. Add Jakarta artifacts (`jakarta.xml.bind-api`, etc.) as explicit deps. |
| **`Nashorn` JS engine** | Deprecated 11, removed 15 | Pick GraalJS if you need in-JVM JavaScript. |
| **Applet API (`java.applet`)** | Deprecated 9, removed **17** | Usually a no-op; rare libraries referenced it. |
| **`SecurityManager`** | Deprecated for removal in **17**, **permanently disabled in 24 (JEP 486)** | Any container / server that relied on it (old app servers, plugin sandboxes) is on borrowed time. No drop-in replacement; redesign around containerization/OS isolation. |
| **RMI Activation** | Removed in 17 | Don't use RMI. Full stop. |
| **`Object.finalize()`** | Deprecated 9, deprecated-for-removal 18, **finalization disabled by default from 21** (`--finalization=disabled`) | Use `java.lang.ref.Cleaner` or `AutoCloseable`. Asked in interviews constantly. |
| **Biased locking** | Disabled by default 15, removed later | Historical trivia — but know why. |
| **`sun.misc.Unsafe` memory access** | Deprecated in 23 (JEP 471) | Migrate to FFM (`MemorySegment`) or `VarHandle`. Netty, Cassandra, etc. are in the middle of this migration. |
| **JNI** | "Prepare to restrict" in 24 (JEP 472) | Will eventually require a CLI opt-in, like `--enable-native-access`. Plan toward FFM. |
| **String Templates** | Previewed in 21–22, **pulled entirely**. | Don't mention them as a current feature. The committee is rethinking the design. |
| **32-bit ports, Solaris, SPARC** | Removed 15–16 | Occasionally relevant for legacy ops questions. |

---

## 3. "Which Version Introduced X?" Quick Lookup

Only the ones interviewers actually ask about.

| Feature | First appeared | Final in |
|---------|----------------|----------|
| Lambdas / Streams / `Optional` / `java.time` | 8 | 8 |
| Default methods on interfaces | 8 | 8 |
| `CompletableFuture` | 8 | 8 |
| JPMS (modules) | 9 | 9 |
| `var` for local variables | 10 | 10 |
| New `HttpClient` | 9 (incubator) | 11 |
| Switch expressions | 12 (preview) | 14 |
| Text blocks | 13 (preview) | 15 |
| Records | 14 (preview) | 16 |
| `instanceof` patterns | 14 (preview) | 16 |
| `Stream.toList()` | 16 | 16 |
| Sealed types | 15 (preview) | 17 |
| Pattern matching for `switch` | 17 (preview) | 21 |
| Record patterns | 19 (preview) | 21 |
| Virtual threads | 19 (preview) | **21** |
| Sequenced collections | 21 | 21 |
| Generational ZGC | 21 (experimental) | 23 (default) |
| FFM API | 19 (incubator) | **22** |
| Unnamed patterns / `_` | 21 (preview) | 22 |
| Stream Gatherers | 22 (preview) | **24** |
| Virtual-thread `synchronized` no longer pins | — | **24** (JEP 491) |
| Class-File API | 22 (preview) | 24 |
| Compact object headers | 24 (experimental) | **25** (JEP 519) |
| Scoped values | 20 (incubator) | **25** (JEP 506) |
| Module import declarations | 23 (preview) | 25 |
| Flexible constructor bodies | 22 (preview) | 25 |
| Compact source files + instance `main` | 21 (preview) | 25 |
| Structured concurrency | 21 (preview) | **still preview in 25** (JEP 505) |
| Primitive types in patterns | 23 (preview) | still preview in 25 (JEP 507) |
| String Templates | 21 (preview) | **withdrawn** |

---

## 4. Preview / Incubator Status as of JDK 25 (GA Sep 2025)

> **Write this on the back of your hand before the interview.** Senior candidates routinely lose credibility by claiming a preview feature is "shipped".

- **Final in 25:** Virtual threads (since 21), pattern matching for `switch` (since 21), record patterns (since 21), sequenced collections (since 21), Stream Gatherers (since 24), FFM (since 22), Class-File API (since 24), **scoped values (JEP 506)**, compact object headers (JEP 519), module import declarations (JEP 511), compact source files + instance `main` (JEP 512), flexible constructor bodies (JEP 513), KDF API (JEP 510), AOT method profiling (JEP 515), JFR CPU-time profiling (JEP 509, Linux).
- **Still preview in 25:**
  - **Structured Concurrency (JEP 505)** — 5th preview. API was reshaped between 21 and 25 (constructor-based scopes in 21–23 → factory-based `StructuredTaskScope.open(Joiner...)` from 24 onward). **Do not assume it's stable in 25.** It's extremely likely to finalize in 26, but until then you must write `--enable-preview`.
  - **Primitive types in patterns, `instanceof`, and `switch` (JEP 507)** — 3rd preview.
  - **PEM encodings of cryptographic objects (JEP 470)** — 1st preview.
- **Incubator (moving target):** Vector API (11th incubator shipping in 26). Never claim this is "standard".
- **Withdrawn / pulled:** String Templates — do not mention as a current feature.

**If asked "is structured concurrency available in 25?"** — the correct senior answer is: *"The API is available as a preview feature and the JDK 25 version uses the current `StructuredTaskScope.open(Joiner...)` shape, but it's not yet final — still requires `--enable-preview`. The closely-related Scoped Values API did finalize in 25. I wouldn't ship structured concurrency to production until it finalizes, which is expected in 26."*

---

## 5. Interview Questions with Model Answers

**Q1. Why does Oracle have an LTS every two years and feature releases every six months?**
Faster cadence = smaller, reviewable increments; preview features let the community try APIs before they're frozen. Two-year LTS gives enterprises a predictable, vendor-supportable target without slowing OpenJDK down. Practically, most shops track only LTSs.

**Q2. What's the single most important change between 8 and 11?**
JPMS (modules) plus removal of Java EE / CORBA modules (JAXB, JAX-WS, `java.activation`, `java.corba`, `java.transaction`, `java.xml.ws.annotation`). The module system itself rarely bites app code, but the removed modules require explicit Jakarta dependencies — that's the migration pain.

**Q3. What do records actually give you, and what are they *not*?**
They give you: a transparent, nominal data carrier with canonical ctor, accessors, `equals`/`hashCode`/`toString`, and compact-constructor validation. They are **not** value types (Valhalla) — they're still identity-ful reference types on the heap. They're also not deeply immutable: a `record Foo(List<String> xs)` holds a reference to a mutable list unless you `List.copyOf` it in the compact ctor.

**Q4. What problem do sealed types solve?**
They give the compiler the exhaustiveness information needed for pattern matching (`switch` without `default`) and make algebraic-data-type modelling practical in Java. Before sealing, any exhaustiveness check was best-effort; now it's enforced.

**Q5. Are virtual threads faster than platform threads?**
Not intrinsically. They're **cheaper to create** (~1 KB vs 1 MB) and **unmount on blocking I/O**, which gives you dramatically higher concurrency for I/O-bound workloads. For CPU-bound work they're the same as platform threads and often worse (they can waste a carrier). Always state the I/O-vs-CPU qualifier in interviews.

**Q6. Should I still replace `synchronized` with `ReentrantLock` for virtual threads?**
Not on JDK 24+. JEP 491 eliminated monitor-based pinning — `synchronized` no longer pins a virtual thread to its carrier. Keep `synchronized` unless you actually need `tryLock`, fairness, timed acquisition, or multiple `Condition`s. Pre-24, the legacy advice still holds.

**Q7. What's the difference between scoped values and `ThreadLocal`?**
`ScopedValue` (JEP 506, final in 25) is immutable and dynamically scoped — you set it via `ScopedValue.where(k, v).run(...)` and it's unbound when the scope exits. `ThreadLocal` is mutable, per-thread, and leaks on pooled threads if you forget `remove()`. With millions of virtual threads, `ThreadLocal` also costs more memory. Use scoped values for request context, security principals, tracing IDs.

**Q8. Is structured concurrency production-ready in JDK 25?**
No. It's the 5th preview (JEP 505). Requires `--enable-preview`. The API shape has changed between 21 and 25 (constructor-style → factory-style with `Joiner` policies). Expected to finalize in JDK 26. Scoped values — which it pairs with — did finalize in 25.

**Q9. What's the difference between G1 and ZGC?**
G1 is region-based, partially concurrent, targets sub-second pauses, and is a reasonable general-purpose default. ZGC targets sub-millisecond pauses even on multi-TB heaps using colored pointers and load barriers. Since JDK 23, generational ZGC is the default mode; non-generational mode was removed in 24. Use G1 unless you have a latency SLA that G1 can't hit.

**Q10. What happened to biased locking, and why?**
Disabled by default in 15 (JEP 374), removed shortly after. It was an optimization for uncontended `synchronized` in single-threaded workloads, but on modern CPUs with cheap atomic ops it rarely helped, while it complicated the safepoint and deoptimization machinery. A simpler VM won.

**Q11. When did the `SecurityManager` die, and what replaces it?**
Deprecated for removal in 17, permanently disabled in 24 (JEP 486). No direct replacement — the JVM is no longer trying to be a sandbox. Migrate to OS-/container-level isolation (seccomp, user namespaces, read-only filesystems) and capability-based code review.

**Q12. What does compact object headers buy you?**
JEP 519 (final in 25). Header shrinks from 96–128 bits to 64 bits on 64-bit hardware — the class pointer is packed into the mark word. Typical ~10–22 % heap savings on object-heavy workloads, fewer GC cycles, better cache density. Enable with `-XX:+UseCompactObjectHeaders`.

**Q13. What does pattern matching for `switch` actually let you stop writing?**
The visitor pattern and long `if-else instanceof` ladders. Combined with sealed types and record patterns, you get exhaustive, type-safe, destructuring dispatch in pure data code — no boilerplate visitor interface, no `default` branch, no casts.

**Q14. `Stream.toList()` vs `Collectors.toList()` — which do you use?**
`Stream.toList()` (since 16) returns an **unmodifiable** list and avoids the collector overhead. Use it by default. Fall back to `Collectors.toList()` only when you need to mutate the result afterward (it returns an `ArrayList`). `Collectors.toUnmodifiableList()` is a third option that also returns unmodifiable but with collector semantics.

**Q15. If you could pick one LTS for a new service today (April 2026), which and why?**
JDK 25. It has virtual threads, pattern matching, scoped values (final), compact object headers, and the AOT startup improvements. JDK 21 is still a fine answer for conservative shops that want more third-party validation, but 25 is the 5-year vendor-support target. Avoid 17 for new services unless you're constrained by library compatibility — you're leaving virtual threads on the table.

---

## 6. Migration Scenarios (Discussion Prompts)

**M1. You're moving an 8 → 17 monolith. What's the actual risk surface?**
Module-system fallout from removed Java EE / CORBA packages (8 → 11) — expect `ClassNotFoundException` on JAXB/JAX-WS; add Jakarta deps. Reflective access to JDK internals is now denied by default (17 strong encapsulation) — Hibernate, Lombok-heavy codebases, and old AspectJ instrumentation need upgrades. Removal of `Nashorn` and RMI Activation affects niche integrations. New `HttpClient` replaces `HttpURLConnection` but the old API still works. GC default moved to G1. Language gains (records, sealed, switch expressions, text blocks) are pure upside.

**M2. You're moving 17 → 21 on an I/O-heavy service. What do you actually gain?**
Virtual threads — the core win. You can drop a reactive stack (or at least stop introducing one) and write synchronous, debuggable code that handles thousands of concurrent blocking calls. Pattern matching for `switch` + record patterns let you retire visitor hierarchies. Sequenced collections give `getFirst`/`getLast`/`reversed` across `List`, `Deque`, `LinkedHashMap`. Generational ZGC is default-available. Caveat: audit for `synchronized` pinning — on 21, `synchronized` still pins a VT to its carrier. (JEP 491 fix lands in 24, not 21.)

**M3. You're moving 21 → 25. Is it worth the quarterly change-management ticket?**
Usually yes, for these wins: scoped values finalize (drop `ThreadLocal` for request context), compact object headers (~10–22 % heap save, just a flag), AOT class loading and method profiling (meaningful startup/warmup gains — matters for serverless / CLIs / tests), JEP 491 landed in 24 so `synchronized` no longer pins VTs, Class-File API & FFM are now stable so library churn is done. Language gains (module imports, compact source files, flexible ctors) are minor for business code. Structured concurrency is **still preview** in 25 — plan on 26 for that.

**M4. You maintain a library that supports JDK 8 baseline customers. What's your policy for adopting new features?**
Stay on JDK 8 source for published artifacts, or ship a multi-release JAR (`META-INF/versions/N/...`) for selective optimizations (e.g., Eclipse Collections, Jackson). Build and test on 8, 11, 17, 21, 25. Use CI matrix; mark preview-dependent modules as optional add-ons. Don't leak `record`/`sealed`/`Stream.toList()` into public API if your published baseline is 8/11. Pick a public deprecation window (e.g., "JDK 11 baseline dropped in v5.0") and announce it one major version ahead.

**M5. You're on JDK 25 with virtual threads. Where can you *not* use them?**
CPU-bound work (keep on `ForkJoinPool` or a fixed pool sized to core count). Code paths with `Object.wait` inside native frames or long JNI / FFM downcalls — those still pin the carrier. Libraries with thread-affinity assumptions (some DB drivers, OpenTelemetry context before 1.32, anything with `ThreadLocal`-heavy state that assumes a bounded pool). Transactional code that relies on thread-bound `ThreadLocal` for the tx context — audit it, migrate to scoped values where possible.

---

## 7. Anti-Patterns: Things Candidates Say Wrong

**"Virtual threads are faster than platform threads."**
Only for I/O-bound workloads. For CPU-bound work they're equal or worse (a long compute never yields and wastes a carrier). Always state the I/O-vs-CPU qualifier. They're not magic concurrency.

**"Records are Java's value types / value classes."**
No. Records are still identity-ful reference types on the heap. Valhalla value classes are a separate, unfinished project. Records generate the usual boilerplate — they don't change the object model.

**"A record is immutable."**
Shallowly. `record Foo(List<String> xs)` still holds a mutable list unless you `List.copyOf` in the compact constructor. Record components are `final`, not deep-frozen.

**"Switch expressions and pattern matching are the same thing."**
Switch expressions (final in 14) = the arrow-form that yields a value. Pattern matching for `switch` (final in 21) = type patterns / record patterns / guards inside cases. You can use switch expressions without pattern matching and vice versa.

**"Structured concurrency is available in 21."**
It's been previewed since 21, but the API has been reshaped multiple times (constructor-based in 21–23, factory-based `StructuredTaskScope.open(Joiner...)` from 24). It's still preview in 25. Don't claim it's production-ready.

**"Sealed classes are like `final` for a hierarchy."**
Not quite. Sealed lists **permitted** subtypes; each permitted type must pick `final`, `sealed`, or `non-sealed`. The point isn't to block inheritance — it's to give the compiler exhaustiveness information.

**"`Optional` replaces `null`."**
Only as a **return type** for potentially-absent values. `Optional` fields, parameters, and collection elements are misuse — extra allocation, worse ergonomics, and it's not `Serializable`. `.get()` without a presence check is a smell.

**"`var` makes Java dynamically typed."**
No. Variables are still statically typed; `var` only infers the static type from the initializer. It can't be used for fields, parameters, return types, or uninitialized locals.

**"`ThreadLocal` is fine with virtual threads."**
It works, but it's wasteful at millions of VTs and easy to leak on any pooled executor. Use `ScopedValue` (final in 25) for per-request context. Also: virtual threads don't "pool", so the classic `ThreadLocal`-leak-in-a-pool argument is subtler — but inheritable ThreadLocals and frameworks that pool carriers indirectly still bite.

**"`SecurityManager` sandboxes untrusted code."**
Not in 2026. It's been permanently disabled since JDK 24 (JEP 486). Use OS/container isolation.

**"G1 vs ZGC: just use ZGC, it has lower pauses."**
ZGC trades CPU for pause times. If your SLA is comfortably under G1's reach (sub-second), G1 is usually cheaper in CPU. ZGC shines for multi-TB heaps or hard <10 ms pause SLAs. Generational ZGC (default since 23) closed much of the throughput gap, but "pick ZGC by default" is still wrong on small-to-medium heaps with no latency pressure.

**"I'll upgrade to 26 as soon as it ships."**
26 is non-LTS. Most shops should jump LTS-to-LTS (21 → 25 → 29). Track non-LTS only if you're running green-field services and willing to redeploy every six months.
