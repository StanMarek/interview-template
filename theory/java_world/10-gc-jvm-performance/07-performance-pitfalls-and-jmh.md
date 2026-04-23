# Performance Pitfalls and JMH

Common mistakes that still hurt real Java services, plus the benchmarking discipline needed to
avoid fooling yourself.

## 10. Common Performance Pitfalls

### Autoboxing in Hot Loops

```java
// BAD: Autoboxing creates ~10M Integer objects
long sum = 0;
Map<Integer, Integer> map = new HashMap<>();
for (int i = 0; i < 10_000_000; i++) {
    map.put(i, i * 2);       // int → Integer (autobox key AND value)
    sum += map.get(i);        // Integer → int (autounbox)
}

// GOOD: Use primitive-specialized collections (Eclipse Collections)
long sum = 0;
IntIntHashMap map = new IntIntHashMap();
for (int i = 0; i < 10_000_000; i++) {
    map.put(i, i * 2);       // No boxing
    sum += map.get(i);        // No boxing
}
```

Note: The Integer cache covers -128 to 127. Within this range, `Integer.valueOf()` returns cached
instances (no allocation). Outside this range, a new Integer is allocated every time.

### String Concatenation in Loops

```java
// BAD: Creates O(n) intermediate String objects, O(n^2) copying
String result = "";
for (String item : items) {
    result += item + ",";     // new StringBuilder + toString each iteration
}

// GOOD: Single StringBuilder
StringBuilder sb = new StringBuilder(items.size() * 20);  // pre-size estimate
for (String item : items) {
    sb.append(item).append(',');
}
String result = sb.toString();
```

Note: Since JDK 9, `javac` uses `invokedynamic`-based string concatenation (`-XX:+CompactStrings`),
which improves single-expression concatenation. But loop concatenation still requires explicit
`StringBuilder`.

### Excessive Object Creation and Pooling

```java
// BAD: Allocating in a hot path (may increase GC pressure)
public void processEvents(List<Event> events) {
    for (Event event : events) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String date = formatter.format(event.getTimestamp());
    }
}

// GOOD: Reuse immutable/thread-safe objects
private static final DateTimeFormatter FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd");

public void processEvents(List<Event> events) {
    for (Event event : events) {
        String date = FORMATTER.format(event.getTimestamp());
    }
}
```

**Object pooling trade-offs**: Pooling was common in early Java but is generally discouraged now.
Modern GCs handle short-lived objects efficiently. Pool only when:
- Object creation is genuinely expensive (database connections, SSL contexts)
- You have evidence from profiling (not speculation)
- The pool itself doesn't become a contention point

### finalize() — Deprecated and Harmful

```java
// BAD: finalize() has severe problems
public class ResourceHolder {
    private native long nativeHandle;

    @Override
    protected void finalize() throws Throwable {
        releaseNative(nativeHandle);  // Unreliable! May never be called.
    }
}
```

Problems with `finalize()`:
- **No guarantee of execution**: The JVM may exit before finalizers run
- **Resurrection**: A finalizer can make the object reachable again
- **Performance**: Finalizable objects require at least two GC cycles to reclaim
  (first GC enqueues to finalizer queue, second GC actually frees)
- **Ordering**: No guaranteed order of finalization
- **Deprecated for removal** since JDK 18 (JEP 421); you can disable finalization with
  `--finalization=disabled` to test whether an application still relies on it

```java
// GOOD: Use try-with-resources + Cleaner
public class ResourceHolder implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private final Cleaner.Cleanable cleanable;
    private final long nativeHandle;

    public ResourceHolder() {
        this.nativeHandle = allocateNative();
        this.cleanable = CLEANER.register(this, new CleanupAction(nativeHandle));
    }

    @Override
    public void close() {
        cleanable.clean();  // Deterministic cleanup
    }

    private static class CleanupAction implements Runnable {
        private final long handle;
        CleanupAction(long handle) { this.handle = handle; }
        @Override
        public void run() { releaseNative(handle); }
    }
}
```

### Reflection Overhead

```java
// BAD: Reflection is slow in hot paths
Method method = obj.getClass().getMethod("process", String.class);
method.invoke(obj, data);

// The JVM can optimize reflection after ~15 invocations (inflation threshold),
// but it still can't inline through reflected calls.

// GOOD: Use MethodHandles or direct invocation
MethodHandle mh = MethodHandles.lookup()
    .findVirtual(MyClass.class, "process",
                 MethodType.methodType(void.class, String.class));
mh.invoke(obj, data);  // Can be inlined by the JIT
```

### Synchronized vs Concurrent Collections

```java
// BAD: Global lock on every operation — blocks all threads
Map<String, Data> map = Collections.synchronizedMap(new HashMap<>());

// GOOD: Lock striping — concurrent reads, segmented writes
Map<String, Data> map = new ConcurrentHashMap<>();

// BAD: Compound operations still need external sync with synchronizedMap
synchronized (map) {
    if (!map.containsKey(key)) {
        map.put(key, computeValue(key));
    }
}

// GOOD: Atomic compound operations
map.computeIfAbsent(key, k -> computeValue(k));
```

---

## 11. JMH Benchmarking

**JMH (Java Microbenchmark Harness)** is the standard framework for writing reliable Java
microbenchmarks. Developed by the JVM engineers who build the JIT — they know every trick
the compiler uses to defeat naive benchmarks.

### Setup and Annotations

```java
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)       // Measure average time per operation
@OutputTimeUnit(TimeUnit.NANOSECONDS)   // Report in nanoseconds
@Warmup(iterations = 5, time = 1)       // 5 warmup iterations of 1 second
@Measurement(iterations = 10, time = 1) // 10 measurement iterations of 1 second
@Fork(2)                                 // Run in 2 separate JVM processes
@State(Scope.Benchmark)                  // State shared across all threads
public class CollectionBenchmark {

    private List<Integer> arrayList;
    private LinkedList<Integer> linkedList;

    @Param({"100", "10000", "1000000"})  // Parameterized benchmark
    private int size;

    @Setup(Level.Trial)                   // Run once per trial
    public void setup() {
        arrayList = new ArrayList<>();
        linkedList = new LinkedList<>();
        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }
    }

    @Benchmark
    public int arrayListGet() {
        return arrayList.get(size / 2);   // O(1) random access
    }

    @Benchmark
    public int linkedListGet() {
        return linkedList.get(size / 2);  // O(n) traversal
    }

    @Benchmark
    public void arrayListIterate(Blackhole bh) {
        for (int val : arrayList) {
            bh.consume(val);  // Prevent dead code elimination
        }
    }

    @Benchmark
    public void linkedListIterate(Blackhole bh) {
        for (int val : linkedList) {
            bh.consume(val);
        }
    }
}
```

### Common Benchmark Pitfalls

**Dead code elimination (DCE):**
```java
// BAD: JIT eliminates the entire computation (result is unused)
@Benchmark
public void badBenchmark() {
    int result = expensiveComputation();
    // result is discarded — JIT may remove the call entirely
}

// GOOD: Return the result (JMH framework consumes it)
@Benchmark
public int goodBenchmark() {
    return expensiveComputation();
}

// GOOD: Use Blackhole for multiple results
@Benchmark
public void goodBenchmarkMultiple(Blackhole bh) {
    bh.consume(computation1());
    bh.consume(computation2());
}
```

**Constant folding:**
```java
// BAD: JIT computes this at compile time
@Benchmark
public int badConstant() {
    return 42 * 31 + 17;  // Folded to a constant by C2
}

// GOOD: Use @State fields (opaque to the compiler at compile time)
@State(Scope.Thread)
public static class MyState {
    int a = 42, b = 31, c = 17;
}

@Benchmark
public int goodComputation(MyState state) {
    return state.a * state.b + state.c;
}
```

**Loop optimization interference:**
```java
// BAD: JIT may optimize this loop away or hoist computations out
@Benchmark
public int badLoop() {
    int sum = 0;
    for (int i = 0; i < 1000; i++) {
        sum += i * i;  // Loop-invariant? Predictable pattern?
    }
    return sum;
}
// JMH handles iteration internally — do NOT add your own measurement loop
```

### Running Benchmarks

```bash
# Build and run
mvn clean install
java -jar target/benchmarks.jar

# Run specific benchmark
java -jar target/benchmarks.jar CollectionBenchmark

# Override settings
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -t 4
#                                ↑     ↑    ↑    ↑
#                           warmup  measure fork threads

# Output formats
java -jar target/benchmarks.jar -rf json -rff results.json
```

---
