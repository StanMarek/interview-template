# Core Java — Senior Engineer Interview Preparation

> **Reference versions:** Java 21 LTS, Java 24 (current project baseline), Java 25 LTS (Sep 2025). Items marked *(preview)* are still evolving; *(final)* is production-ready.

---

## 1. JVM Internals

### Memory Model (JMM)

The Java Memory Model defines how threads interact through memory. Every thread has a **working memory** (an abstraction that may be implemented via CPU caches, registers, or store buffers) and communicates via **main memory** (heap).

**Happens-Before Relationship**: If action A happens-before action B, then A's results are visible to B. Key rules:
- Program order within a thread
- Monitor unlock → subsequent lock
- `volatile` write → subsequent read
- `Thread.start()` → any action in started thread
- Any action in thread → `Thread.join()` return

**Visibility Problem**: Without synchronization, Thread A's writes may never be seen by Thread B because values can be cached in CPU registers/L1 cache.

```java
// BROKEN — flag may never be seen as true by reader thread
boolean flag = false;

// FIX — use volatile
volatile boolean flag = false;
```

**Reordering**: The JVM and CPU can reorder instructions for performance. `volatile` and `synchronized` prevent harmful reordering by inserting memory barriers (fences).

### JVM Architecture

```
┌──────────────────────────────────────────┐
│              Class Loader                │
│   Bootstrap → Platform → Application    │
├──────────────────────────────────────────┤
│           Runtime Data Areas             │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌────────┐  │
│  │ Heap │ │Stack │ │Method│ │   PC   │  │
│  │      │ │(per  │ │ Area │ │Register│  │
│  │Young │ │thread│ │      │ │(per    │  │
│  │ Old  │ │)     │ │      │ │thread) │  │
│  └──────┘ └──────┘ └──────┘ └────────┘  │
├──────────────────────────────────────────┤
│          Execution Engine                │
│   Interpreter → JIT Compiler (C1/C2)    │
│          Garbage Collector               │
└──────────────────────────────────────────┘
```

**Heap Generations (G1 prior to ZGC/Shenandoah)**:
- **Young Generation**: Eden + Survivor spaces (S0, S1). New objects allocated here. Minor GC collects this.
- **Old Generation (Tenured)**: Objects surviving multiple minor GCs are promoted here. Major/Full GC collects this.
- **Metaspace** (replaces PermGen since Java 8): Stores class metadata. Grows dynamically, limited by native memory.

**Object Header (JEP 519, Java 25)**: Compact object headers shrink the header from 96–128 bits to 64 bits on 64-bit hardware — the mark word now encodes the class pointer alongside hash/lock/GC-age bits. Enable with `-XX:+UseCompactObjectHeaders` (production-ready in 25; experimental in 24 under JEP 450). Typical gains: ~10–22% heap reduction, fewer GC cycles, better cache density.

### Garbage Collectors

| Collector | Pause Type | Best For | Key Flags |
|-----------|-----------|----------|-----------|
| Serial | Stop-the-world | Small heaps, single-core | `-XX:+UseSerialGC` |
| Parallel | Stop-the-world | Throughput-oriented batch | `-XX:+UseParallelGC` |
| G1 | Mostly concurrent | General purpose (default Java 9+) | `-XX:+UseG1GC` |
| ZGC | Sub-millisecond pauses | Ultra-low latency, multi-TB heaps | `-XX:+UseZGC` |
| Shenandoah | Sub-millisecond pauses | Low latency (RedHat) | `-XX:+UseShenandoahGC` |

**G1 Internals**: Divides heap into ~2048 equally-sized regions. Tracks "garbage-first" — collects regions with most garbage first. Uses remembered sets (RSets) to track cross-region references. Mixed collections can collect young + some old regions together.

**ZGC Internals**: Uses colored pointers (metadata stored in pointer bits) and load barriers. Can handle multi-terabyte heaps with <1ms pauses. Concurrent relocation using forwarding tables. **Since Java 24 (JEP 490), ZGC is generational-only** — the non-generational mode was removed.

**Tuning tips**:
- `-XX:+UseStringDeduplication` (G1 default since 18, available on ZGC) collapses duplicate `String` contents automatically — usually a better option than manual `intern()`.
- Prefer sizing the heap via `-Xmx` and letting the GC ergonomics pick the rest; hand-tuned Young/Old ratios are rarely worth it on G1/ZGC.

### Class Loading

**Delegation Model**: Child classloader delegates to parent first (Bootstrap → Platform → Application). Prevents duplicate classes and ensures core classes (java.lang.*) always come from bootstrap loader.

**Risks**:
- **ClassLoader leaks**: Custom classloaders holding references prevent class unloading → Metaspace OOM
- **ClassCastException across classloaders**: Same class loaded by two different classloaders are incompatible types

```java
// Custom classloader — hot reloading use case
public class HotSwapClassLoader extends ClassLoader {
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (name.startsWith("com.myapp")) {
            // Break delegation — load ourselves
            byte[] bytes = loadClassBytes(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
        return super.loadClass(name); // delegate to parent
    }
}
```

---

## 2. Concurrency Deep Dive

### synchronized vs ReentrantLock

| Feature | synchronized | ReentrantLock |
|---------|-------------|---------------|
| Fairness | Non-fair only | Fair or non-fair |
| Try-lock | No | `tryLock(timeout)` |
| Interruptible | No | `lockInterruptibly()` |
| Multiple conditions | No (single wait set) | Multiple `Condition` objects |
| Performance | Fast-path on uncontended locks | Slightly better under high contention |
| Virtual thread friendliness | Pins carrier before JDK 24; non-pinning since JDK 24 (JEP 491) | Never pins |

**synchronized internals**: Uses object header's mark word. States: unlocked → thin (CAS spin) → fat (OS mutex). Biased locking was **disabled by default and deprecated in Java 15 (JEP 374)** because it complicated the safepoint/pause machinery and no longer paid for itself on modern hardware. The implementation code remains in the JVM but is off by default — it has not been removed outright as of the current JDK.

```java
// ReentrantLock with multiple conditions
ReentrantLock lock = new ReentrantLock();
Condition notFull = lock.newCondition();
Condition notEmpty = lock.newCondition();

public void put(E item) throws InterruptedException {
    lock.lock();
    try {
        while (count == capacity) notFull.await();
        enqueue(item);
        notEmpty.signal();
    } finally {
        lock.unlock(); // ALWAYS in finally
    }
}
```

### java.util.concurrent Key Classes

**ConcurrentHashMap** (Java 8+): Uses CAS + synchronized on individual bins (not segments). Tree bins for long chains (>8 elements, and only when the table length is ≥ `MIN_TREEIFY_CAPACITY=64` — same rule as HashMap: treeify only if bucket size > 8 AND table length ≥ 64, otherwise the table is resized instead). `computeIfAbsent` is atomic. **Risk**: Recursive `computeIfAbsent` on same key → throws `IllegalStateException` ("Recursive update") since Java 9.

```java
// IllegalStateException ("Recursive update") with ConcurrentHashMap (since Java 9)
map.computeIfAbsent("key", k -> map.computeIfAbsent("key", k2 -> "value"));
```

**CompletableFuture**: Non-blocking async computation.

```java
CompletableFuture.supplyAsync(() -> fetchUser(id), customExecutor)
    .thenApplyAsync(user -> enrichUser(user))
    .thenAcceptAsync(user -> saveUser(user))
    .exceptionally(ex -> { log.error("Failed", ex); return null; });

// Combine multiple futures
CompletableFuture.allOf(future1, future2, future3)
    .thenApply(v -> Stream.of(future1, future2, future3)
        .map(CompletableFuture::join)
        .collect(Collectors.toList()));
```

**Risk with CompletableFuture**: Default uses `ForkJoinPool.commonPool()` which is shared across the JVM. CPU-bound tasks there can starve I/O tasks. Always provide a custom executor for I/O operations.

### Virtual Threads (Java 21+)

Lightweight threads managed by JVM, not OS. Mounted/unmounted from carrier (platform) threads.

```java
// Creating virtual threads
Thread.startVirtualThread(() -> handleRequest());

// With executor
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> callServiceA());
    executor.submit(() -> callServiceB());
}
```

**Key Characteristics**:
- Cheap to create (~1KB stack vs ~1MB for platform threads); disposable — spawn one per task, never pool them
- Ideal for I/O-bound tasks (HTTP calls, DB queries)
- **Pinning (historical)**: In Java 21–23, `synchronized` blocks pinned a virtual thread to its carrier, so the legacy advice was "replace `synchronized` with `ReentrantLock`". **JEP 491 (Java 24) eliminates monitor-based pinning** — the JVM can now park a virtual thread that is holding a monitor. That advice is obsolete; keep `synchronized` unless you need lock features (tryLock, fairness, multiple conditions).
- Remaining pinning causes: `Object.wait` inside a native frame, and JNI / foreign-function upcalls. Diagnose with `-Djdk.tracePinnedThreads=full`.
- **Not suitable for CPU-bound work** — long compute won't yield and wastes a carrier. Keep CPU work on `ForkJoinPool` / a fixed pool.

**Scoped Values (JEP 506, final in Java 25)**: Immutable, per-thread, inheritable context — a modern, virtual-thread-friendly replacement for `ThreadLocal` that avoids memory leaks in pooled threads.

```java
static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

ScopedValue.where(CURRENT_USER, user).run(() -> handleRequest());
// inside the scope:
User u = CURRENT_USER.get();
```

**Structured Concurrency (JEP 505, still preview in Java 25)**: Treats a fan-out of concurrent subtasks as a single unit of work with scoped lifetimes and cancellation.

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user   = scope.fork(() -> fetchUser(id));
    var orders = scope.fork(() -> fetchOrders(id));
    scope.join().throwIfFailed();
    return new Response(user.get(), orders.get());
}
```

---

## 3. Collections Internals

### HashMap Internals

- **Buckets**: Array of `Node<K,V>[]`, default initial capacity 16, load factor 0.75
- **Hashing**: `hash(key)` spreads high bits → `(n-1) & hash` for bucket index
- **Collision**: Linked list → **TreeNode (Red-Black tree)** when chain length > 8 (TREEIFY_THRESHOLD) and table size ≥ 64
- **Resize**: Doubles capacity, rehashes all entries. **Not thread-safe** — concurrent put can cause infinite loop (Java 7 head-insert) or data loss (Java 8+)

### ConcurrentHashMap vs Collections.synchronizedMap

| Aspect | ConcurrentHashMap | synchronizedMap |
|--------|------------------|-----------------|
| Locking | Per-bin (fine-grained) | Entire map (coarse) |
| Null keys/values | NOT allowed | Allowed |
| Iterators | Weakly consistent | Fail-fast |
| Compound operations | Atomic via compute/merge | Must externally sync |

### TreeMap vs HashMap

- TreeMap: Red-Black tree, O(log n) operations, sorted by keys, implements `NavigableMap`
- HashMap: Hash table, O(1) average, no ordering

### Common Interview Questions

**Q: Why is String a good HashMap key?**
Strings are immutable, so `hashCode()` is cached. Immutability guarantees the hash won't change after insertion, which would "lose" the entry.

**Q: What happens when two keys have same hashCode?**
Both go to the same bucket. `equals()` is used to distinguish them in the chain/tree. This is why the `hashCode/equals` contract matters: equal objects must have equal hashCodes.

**Q: How does CopyOnWriteArrayList work?**
Every mutation creates a new copy of the internal array. Reads are lock-free on the current snapshot. Ideal for read-heavy, write-rare scenarios (e.g., listener lists). **Risk**: High memory churn on frequent writes.

### equals / hashCode Contract

Five rules the JVM's hash-based collections rely on:
1. **Reflexive**: `x.equals(x)` is true.
2. **Symmetric**: `a.equals(b)` ↔ `b.equals(a)`.
3. **Transitive**: `a.equals(b)` and `b.equals(c)` ⇒ `a.equals(c)`.
4. **Consistent**: Repeated calls return the same result as long as the relevant fields don't change.
5. **`hashCode` consistency**: Equal objects **must** have equal hash codes (the converse is not required).

Breaking rule 5 hides entries in `HashMap`/`HashSet`. Mutating a field used by `hashCode` after insertion "loses" the entry — a classic bug. Prefer immutable keys, or use `record` which generates correct `equals`/`hashCode` automatically.

### Comparable vs Comparator

- `Comparable<T>` defines the **natural ordering** of a type; `TreeMap`/`TreeSet` use it by default.
- `Comparator<T>` is an external ordering; pass it to the sorted collection constructor or `Stream.sorted(cmp)`.
- **Consistency with equals**: if `compareTo` returns 0 for two objects, `equals` *should* return true. `TreeMap`/`TreeSet` use `compareTo` (not `equals`) for key identity, so inconsistency causes "lost" entries or unexpected deduplication.
- Modern `Comparator` composition avoids hand-written compare chains:

```java
Comparator<Employee> byDeptThenSalary =
    Comparator.comparing(Employee::getDept)
              .thenComparingDouble(Employee::getSalary)
              .reversed()
              .thenComparing(Employee::getName, Comparator.nullsLast(Comparator.naturalOrder()));
```

### String Internals

- **Immutable + final** → thread-safe, safely cacheable `hashCode`, and safe to use as `Map` keys.
- **Compact Strings (Java 9+)**: backing field is `byte[]` + a 1-byte coder (LATIN1 or UTF16). ASCII-heavy apps roughly halve heap usage versus the pre-9 `char[]` representation — automatic, no flag.
- **String pool** lives in the heap (moved out of PermGen in Java 7). Literals are interned at class-load time.
- **`intern()` caveats**: manual interning is rarely worth it in modern JVMs. Prefer `-XX:+UseStringDeduplication` (G1/ZGC) which collapses duplicate contents in the background without bloating the pool.
- **Concatenation**: `+` in a single expression is compiled to `invokedynamic` + `StringConcatFactory` (Java 9+), which picks a strategy at runtime and is usually faster than a hand-rolled `StringBuilder`. Use `StringBuilder` explicitly only inside loops.

### Autoboxing Pitfalls

- `Integer` caches `-128..127` by default. `Integer a = 127; Integer b = 127; a == b` is true, but flips to false at 128. Always use `.equals()` for boxed numeric comparisons.
- Unboxing a `null` wrapper throws `NullPointerException` — common with `Map.get`, which returns `null` when absent. Prefer `getOrDefault` or primitive collections (Eclipse Collections' `IntIntHashMap`) in hot loops.
- Avoid boxed types in tight numeric code — each autobox allocates an object and hits the GC.

---

## 4. Generics & Type System

### Type Erasure

Generics are compile-time only. At runtime, `List<String>` and `List<Integer>` are both `List`. This means:
- Cannot do `new T()`, `new T[]`, or `instanceof T`
- Cannot overload methods differing only by generic type parameter

**Workaround for type token**:

```java
// Super type token pattern (used by Jackson, Guice)
TypeReference<List<String>> ref = new TypeReference<>() {};
// Captures generic type via anonymous class's generic superclass
```

### Wildcards: PECS (Producer Extends, Consumer Super)

```java
// Producer — reads items of type T
void printAll(List<? extends Number> list) {
    for (Number n : list) System.out.println(n); // Safe to read as Number
    // list.add(1); // COMPILE ERROR — can't write
}

// Consumer — writes items of type T
void addNumbers(List<? super Integer> list) {
    list.add(1);     // Safe to write Integer
    list.add(2);
    // Integer n = list.get(0); // COMPILE ERROR — can only read as Object
}
```

---

## 5. Streams & Functional Programming

### Stream Pipeline Internals

Streams use **lazy evaluation**. Intermediate operations build a pipeline; terminal operations trigger execution. Internally uses `Spliterator` for traversal.

**Parallel Streams**: Uses `ForkJoinPool.commonPool()` by default (shared with CompletableFuture).

```java
// Custom ForkJoinPool for parallel streams
ForkJoinPool customPool = new ForkJoinPool(4);
customPool.submit(() ->
    list.parallelStream()
        .filter(...)
        .collect(Collectors.toList())
).get();
```

**Risks with Parallel Streams**:
- **Shared mutable state** → race conditions
- **Ordering overhead** with ordered sources (ArrayList vs HashSet)
- **Small datasets** → overhead exceeds benefit
- **Blocking I/O** in parallel stream → starves common pool

### Key Collectors

```java
// Grouping with downstream collector
Map<Department, Long> countByDept = employees.stream()
    .collect(Collectors.groupingBy(Employee::getDept, Collectors.counting()));

// Partitioning (boolean split)
Map<Boolean, List<Employee>> partitioned = employees.stream()
    .collect(Collectors.partitioningBy(e -> e.getSalary() > 100000));

// Custom collector
Collector<String, StringJoiner, String> joiner =
    Collector.of(
        () -> new StringJoiner(", "),    // supplier
        StringJoiner::add,               // accumulator
        StringJoiner::merge,             // combiner (for parallel)
        StringJoiner::toString           // finisher
    );
```

**`toList()` vs `Collectors.toList()`**: `Stream.toList()` (Java 16+) returns an **unmodifiable** `List` and is the modern default. `Collectors.toList()` returns a **mutable** `List` (the Javadoc does not guarantee the concrete class — it is `ArrayList` in the current HotSpot JDK but do not rely on that) — use it only when you need to mutate the result afterwards.

**`Collectors.teeing` (Java 12+)**: feeds the same stream into two downstream collectors and merges their results — handy for single-pass min+max, sum+count, etc.

```java
record MinMax(double min, double max) {}
MinMax mm = numbers.stream().collect(Collectors.teeing(
    Collectors.minBy(Double::compare),     // Collector<Double,?,Optional<Double>>
    Collectors.maxBy(Double::compare),     // Collector<Double,?,Optional<Double>>
    (min, max) -> new MinMax(
        min.orElse(Double.NaN),
        max.orElse(Double.NaN))));
```

### Stream Gatherers (final, Java 24 — JEP 485)

Gatherers are to intermediate ops what `Collector` is to terminal ops: a reusable, user-defined intermediate. Built-ins in `java.util.stream.Gatherers`:

```java
// Sliding windows
stream.gather(Gatherers.windowSliding(3));          // [1,2,3], [2,3,4], ...
stream.gather(Gatherers.windowFixed(3));            // [1,2,3], [4,5,6], ...

// Fold with state
stream.gather(Gatherers.fold(() -> 0, Integer::sum));

// Run them in parallel too
stream.parallel().gather(Gatherers.scan(() -> 0, Integer::sum)).toList();
```

Prefer gatherers over ad-hoc `reduce` / mutable accumulators when you need sliding windows, running sums, deduplication, or batching.

### Optional — Best Practices

- **Use as a return type** for values that may be absent. **Do not** use for fields, method parameters, or collection elements (it adds allocation and obscures intent).
- **Never call `.get()` unguarded** — treat it as a code smell. Prefer `orElse`, `orElseGet`, `orElseThrow`, `ifPresent`, `map`, `flatMap`.
- `orElse(x)` always evaluates `x`; `orElseGet(() -> expensive())` defers — matters when the default is costly.
- **Do not** serialize `Optional` (it's not `Serializable`) — return `Optional` from APIs, store raw nullable fields.
- `stream()` on `Optional` turns it into a 0-or-1 stream, which composes cleanly with `flatMap`.

```java
Optional<User> user = repo.findById(id);
return user.map(User::getEmail)
           .filter(e -> !e.isBlank())
           .orElseThrow(() -> new NotFoundException(id));
```

### Varargs

- `method(String... names)` compiles to a `String[]` parameter. Passing an array directly works (`method(arr)`); passing `null` passes a null array.
- **Heap pollution warnings** on generic varargs — annotate with `@SafeVarargs` on `static`/`final`/`private` methods when you truly don't corrupt the array.
- Overload resolution prefers non-varargs; beware `log.info("x {}", arr)` vs `log.info("x {}", (Object) arr)`.

---

## 6. Exception Handling Best Practices

- Use checked exceptions for **recoverable** conditions (e.g., `IOException`)
- Use unchecked exceptions for **programming errors** (e.g., `NullPointerException`)
- **Never** catch `Throwable` or `Error` in production code (except top-level handlers)
- Prefer specific exceptions over generic ones
- Use try-with-resources for `AutoCloseable` resources
- Don't use exceptions for control flow — expensive (fills stack trace)

```java
// Suppressed exceptions with try-with-resources
try (var conn = getConnection(); var stmt = conn.createStatement()) {
    // If both the try block and close() throw,
    // close()'s exception becomes "suppressed"
    // Accessible via ex.getSuppressed()
}

// Multi-catch — one handler, multiple types
try {
    parse(input);
} catch (IOException | NumberFormatException e) {
    // `e` is effectively final — cannot be reassigned
    log.warn("bad input", e);
}
```

**Helpful NullPointerExceptions (Java 14+)**: messages now pinpoint the exact expression — e.g., `Cannot invoke "String.length()" because "user.name" is null`. Enabled by default; `-XX:-ShowCodeDetailsInExceptionMessages` disables.

**Avoid anti-patterns**:
- **Sneaky throws** (Lombok's `@SneakyThrows`, generic tricks): bypass the compiler's checked-exception tracking; fine for demos, a liability in libraries.
- **Swallowing `InterruptedException`**: always either re-throw or call `Thread.currentThread().interrupt()` to restore the flag. Virtual threads rely on this for cancellation.
- **Catch-and-wrap without chaining**: always pass the original as `cause` (`new ServiceException("x", e)`), never drop it.

---

## 7. Modern Language Features (Java 17 → 25)

### Records (final, Java 16)

Nominal, immutable, shallowly-unmodifiable data carriers. The compiler generates canonical constructor, accessors, `equals`, `hashCode`, and `toString`.

```java
public record Point(int x, int y) {
    // Compact constructor — runs before the generated field assignments
    public Point {
        if (x < 0 || y < 0) throw new IllegalArgumentException();
    }
}
```

Records are **transparent**: their state is exactly their components, which is what unlocks record patterns below. They are implicitly `final` and cannot extend other classes (they can implement interfaces).

### Sealed Classes (final, Java 17)

Controlled inheritance — the author lists the permitted subtypes, giving the compiler the exhaustiveness information that powers pattern matching.

```java
public sealed interface Shape permits Circle, Rectangle, Triangle {}
public record Circle(double radius)          implements Shape {}
public record Rectangle(double w, double h)  implements Shape {}
public final class Triangle implements Shape { /* ... */ }
```

Each permitted subtype must be `final`, `sealed`, or `non-sealed`.

### Pattern Matching

**`instanceof` patterns (final, Java 16)** — bind the tested type in one step:

```java
if (obj instanceof String s && !s.isBlank()) {
    return s.toUpperCase();
}
```

**Switch expressions (final, Java 14)** + **pattern matching for switch (final, Java 21)**:

```java
double area(Shape shape) {
    return switch (shape) {
        case Circle c                     -> Math.PI * c.radius() * c.radius();
        case Rectangle r when r.w() == r.h() -> r.w() * r.w();   // guarded pattern
        case Rectangle r                  -> r.w() * r.h();
        case Triangle t                   -> calculateTriangleArea(t);
        // No default needed — the sealed hierarchy is exhaustive
    };
}
```

Key rules: `null` is handled with `case null` (no more separate `if`); dominance order matters — the compiler rejects unreachable cases.

**Record patterns (final, Java 21)** — destructure records directly:

```java
sealed interface Json permits JsonNum, JsonStr, JsonArr {}
record JsonNum(double v) implements Json {}
record JsonStr(String v) implements Json {}
record JsonArr(List<Json> items) implements Json {}

String render(Json j) {
    return switch (j) {
        case JsonNum(double v)        -> Double.toString(v);
        case JsonStr(String v)        -> "\"" + v + "\"";
        case JsonArr(List<Json> xs)   -> xs.stream().map(this::render).collect(joining(",", "[", "]"));
    };
}
```

Nested deconstruction (`case Pair(Point(int x, _), Point(int x2, _))`) and unnamed patterns/variables `_` are final in Java 22+.

### Text Blocks (final, Java 15)

Multi-line string literals with smart indentation stripping:

```java
String query = """
        SELECT id, name
        FROM users
        WHERE active = true
        """;    // trailing whitespace before each newline is stripped
```

### Other modern features worth knowing

- **Unnamed variables `_`** (final, Java 22) — for unused lambda parameters, catch variables, and pattern bindings.
- **Primitive types in patterns** (preview in 25, JEP 507) — `case int i when i > 0`, `x instanceof byte b` with range checks.
- **Flexible constructor bodies** (preview in 25, JEP 513) — statements allowed before `this(...)`/`super(...)` for input validation.
- **Module imports** (preview in 24, JEP 494) — `import module java.base;`.

### Immutability Checklist

A class is effectively immutable when:
1. All fields are `private final`.
2. The class is `final` (or constructor is private and factory-based).
3. Constructor makes **defensive copies** of mutable parameters (`List.copyOf`, `Map.copyOf`).
4. Accessors return unmodifiable views or copies, never internal references.
5. No methods mutate state.

Records give you 1 and 2 for free but do **not** make component collections immutable — `List.copyOf` in the compact constructor is still your job.

---

## 8. Common Senior-Level Interview Questions

**Q: Explain the difference between strong, soft, weak, and phantom references.**
- **Strong**: Normal references. Object won't be GC'd while reachable.
- **Soft** (`SoftReference`): GC'd only under memory pressure. Used for caches.
- **Weak** (`WeakReference`): GC'd at next collection. Used in `WeakHashMap` for canonicalization maps.
- **Phantom** (`PhantomReference`): Enqueued after the object is unreachable but before memory reclaim. Used for post-mortem cleanup via `java.lang.ref.Cleaner` (modern replacement for `finalize()`, which is deprecated for removal since Java 9 and disabled by default in 21+). `get()` always returns null.

**Q: What is the "double-checked locking" pattern and why was it broken before Java 5?**
Without `volatile`, the JVM could reorder the assignment — publishing the reference before the constructor completes. Java 5+ memory model guarantees `volatile` writes prevent this reordering.

```java
private static volatile Singleton instance;

public static Singleton getInstance() {
    if (instance == null) {                 // First check (no locking)
        synchronized (Singleton.class) {
            if (instance == null) {         // Second check (with lock)
                instance = new Singleton(); // volatile prevents reordering
            }
        }
    }
    return instance;
}
```

**Q: What causes a memory leak in Java?**
- Unclosed resources (streams, connections)
- Static collections growing unbounded
- Listeners/callbacks never deregistered
- ThreadLocal not removed after use (especially in thread pools)
- Inner classes holding implicit reference to outer class
- ClassLoader leaks in application servers

**Q: Explain the `Comparable` vs `Comparator` contract with TreeMap.**
`Comparable.compareTo()` must be consistent with `equals()`: if `compareTo` returns 0, `equals` should return true. TreeMap uses `compareTo` (not `equals`) for key identity. Inconsistency causes "lost" entries.

**Q: Why is `finalize()` dead, and what do I use instead?**
`Object.finalize()` is unpredictable (no guarantee of timing or even execution), slows GC, and can resurrect objects. It was deprecated in Java 9, deprecated *for removal* in 18, and finalization is disabled by default from Java 21 (`--finalization=disabled`). Use `java.lang.ref.Cleaner` (a daemon-thread-backed `PhantomReference` wrapper) or, for anything I/O-shaped, implement `AutoCloseable` and rely on try-with-resources.

**Q: Why should I prefer composition over inheritance, and how do modern Java features help?**
Inheritance couples subclasses to the superclass's implementation details (the fragile-base-class problem). Java 17+ sealed hierarchies + records + pattern matching let you model closed sets of variants without inheritance, and get exhaustive compile-time checks. Use inheritance for true "is-a-kind-of" contracts; use composition (and sealed/record/switch) for data modelling.

**Q: When should I pick virtual threads over an async framework (Reactor, RxJava)?**
Virtual threads give you synchronous, debugger-friendly code that scales like reactive for I/O-bound work — no `Mono`/`Flux`, no colored functions. Pick reactive only when you need explicit back-pressure, demand-driven flow control, or you're integrating with an existing reactive stack. For straightforward "lots of HTTP/DB calls in parallel" work on Java 21+, virtual threads are the default answer. Remember: still wrong for CPU-bound work.

**Q: What are scoped values, and why not just use ThreadLocal?**
`ThreadLocal` on a pool leaks memory if `.remove()` is forgotten, is mutable across a call stack, and costs extra with millions of virtual threads. `ScopedValue` (final in Java 25) is immutable, scoped to a dynamic extent (`ScopedValue.where(k, v).run(...)`), cheap per virtual thread, and auto-cleaned when the scope exits. Prefer it for request context, security principals, tracing IDs.
