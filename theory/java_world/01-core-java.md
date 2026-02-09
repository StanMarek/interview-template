# Core Java — Senior Engineer Interview Preparation

---

## 1. JVM Internals

### Memory Model (JMM)

The Java Memory Model defines how threads interact through memory. Every thread has a **working memory** (CPU cache) and communicates via **main memory** (heap).

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
│   Bootstrap → Extension → Application   │
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

### Garbage Collectors

| Collector | Pause Type | Best For | Key Flags |
|-----------|-----------|----------|-----------|
| Serial | Stop-the-world | Small heaps, single-core | `-XX:+UseSerialGC` |
| Parallel | Stop-the-world | Throughput-oriented batch | `-XX:+UseParallelGC` |
| G1 | Mostly concurrent | General purpose (default Java 9+) | `-XX:+UseG1GC` |
| ZGC | Sub-millisecond pauses | Ultra-low latency | `-XX:+UseZGC` |
| Shenandoah | Sub-millisecond pauses | Low latency (RedHat) | `-XX:+UseShenandoahGC` |

**G1 Internals**: Divides heap into ~2048 equally-sized regions. Tracks "garbage-first" — collects regions with most garbage first. Uses remembered sets (RSets) to track cross-region references. Mixed collections can collect young + some old regions together.

**ZGC Internals**: Uses colored pointers (metadata stored in pointer bits) and load barriers. Can handle multi-terabyte heaps with <1ms pauses. Concurrent relocation using forwarding tables.

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
| Performance | Biased locking (removed Java 15+) | Slightly better under high contention |

**synchronized internals**: Uses object header's mark word. States: unlocked → biased → thin (CAS spin) → fat (OS mutex). Biased locking was removed in Java 15+ because it added complexity to the JVM pause mechanism.

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

**ConcurrentHashMap** (Java 8+): Uses CAS + synchronized on individual bins (not segments). Tree bins for long chains (>8 elements). `computeIfAbsent` is atomic. **Risk**: Recursive `computeIfAbsent` on same key → deadlock.

```java
// DEADLOCK with ConcurrentHashMap
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
- Cheap to create (~1KB stack vs ~1MB for platform threads)
- Ideal for I/O-bound tasks (HTTP calls, DB queries)
- **Pinning problem**: `synchronized` blocks pin virtual thread to carrier thread. Use `ReentrantLock` instead.
- **Not suitable for CPU-bound work** — won't yield, blocks carrier thread

**Structured Concurrency (Preview)**:

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    var user = scope.fork(() -> fetchUser(id));
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
- **Collision**: Linked list → **TreeMap (Red-Black tree)** when chain length > 8 (TREEIFY_THRESHOLD) and table size ≥ 64
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
```

---

## 7. Records, Sealed Classes, Pattern Matching (Java 17+)

```java
// Records — immutable data carriers
public record Point(int x, int y) {
    // Compact constructor for validation
    public Point {
        if (x < 0 || y < 0) throw new IllegalArgumentException();
    }
}

// Sealed classes — controlled inheritance
public sealed interface Shape permits Circle, Rectangle, Triangle {}
public record Circle(double radius) implements Shape {}
public record Rectangle(double w, double h) implements Shape {}
public final class Triangle implements Shape { /* ... */ }

// Pattern matching with sealed types (exhaustive switch)
double area(Shape shape) {
    return switch (shape) {
        case Circle c    -> Math.PI * c.radius() * c.radius();
        case Rectangle r -> r.w() * r.h();
        case Triangle t  -> calculateTriangleArea(t);
        // No default needed — compiler knows all subtypes
    };
}
```

---

## 8. Common Senior-Level Interview Questions

**Q: Explain the difference between strong, soft, weak, and phantom references.**
- **Strong**: Normal references. Object won't be GC'd while reachable.
- **Soft** (`SoftReference`): GC'd only under memory pressure. Used for caches.
- **Weak** (`WeakReference`): GC'd at next collection. Used in `WeakHashMap` for canonicalization maps.
- **Phantom** (`PhantomReference`): Enqueued after finalization, before memory reclaim. Used for cleanup (alternative to finalizers). `get()` always returns null.

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
