# JIT, Graal, Leyden, and Object Layout

How HotSpot gets fast, what changed after the in-tree Graal removal, and which newer JVM features
matter for startup, footprint, and warmup.

## 9. JIT Compilation

The JVM uses a multi-tier compilation strategy to balance startup speed with peak performance.

### Tiered Compilation

```
Execution Flow:
                         Compilation
Code         Interpreter ──────────────────► Machine Code
Behavior     (slow, no optimization)         (fast, optimized)

Tiers (per HotSpot source):
┌─────────────┬──────────────────────────────────────────────────┐
│ Tier 0      │ Interpreter — all methods start here             │
│ Tier 1      │ C1 WITHOUT profiling (trivial methods, no counters)│
│ Tier 2      │ C1 with invocation + backedge counters only      │
│             │ (limited profiling)                              │
│ Tier 3      │ C1 with FULL profiling (the normal C1 tier)      │
│ Tier 4      │ C2 — fully optimized                             │
└─────────────┴──────────────────────────────────────────────────┘
```

**Typical compilation path:**
Interpreter → C1 (Tier 3) with profiling → C2 (Tier 4) with full optimization

C1 compiles quickly but produces moderately optimized code. C2 takes longer but applies
aggressive optimizations based on profiling data collected during C1 execution.

### Compilation Thresholds and OSR

Methods are compiled when they exceed an invocation threshold:
- With tiered compilation (default since JDK 8): `Tier3InvocationThreshold=200`,
  `Tier4InvocationThreshold=5000`.
- The legacy `CompileThreshold=10000` applies only with `-XX:-TieredCompilation`.

**On-Stack Replacement (OSR)**: For long-running loops that were entered via the interpreter,
the JVM can compile the loop body and replace the running interpreted frame with compiled
code **while the loop is executing**. This is critical for methods that contain hot loops
but are only called once.

### Key JIT Optimizations

**Method Inlining** — most impactful optimization:
```java
// Before inlining:
public int compute(int x) {
    return helper(x) + 1;
}
private int helper(int x) {
    return x * 2;
}

// After inlining (what C2 sees):
public int compute(int x) {
    return (x * 2) + 1;  // helper() body substituted inline
}
```
- Eliminates call overhead
- Enables further optimizations (constant folding, dead code elimination)
- Controlled by: `-XX:MaxInlineSize=35` (bytecodes for always-inlined methods),
  `-XX:FreqInlineSize=325` (bytecodes for frequently-called methods)
- **Megamorphic call sites** (3+ receiver types) defeat inlining

**Escape Analysis** — determines if an object "escapes" the method/thread:
```java
public int sumPoints(int x1, int y1, int x2, int y2) {
    Point p1 = new Point(x1, y1);  // Does not escape this method
    Point p2 = new Point(x2, y2);  // Does not escape this method
    return p1.x + p2.x + p1.y + p2.y;
}
```

Enables three optimizations:
1. **Scalar replacement**: Replace the object with its fields as local variables (no allocation)
2. **Stack allocation**: HotSpot does NOT do stack allocation for objects; it does scalar replacement only (object broken into primitive fields in registers/stack slots)
3. **Lock elision**: Remove synchronization on non-escaping objects

```java
// Lock elision example:
public void process() {
    StringBuffer sb = new StringBuffer();  // Does not escape
    sb.append("hello");    // StringBuffer.append is synchronized
    sb.append(" world");   // But lock is elided — no contention possible
    return sb.toString();
}
// C2 eliminates all synchronization on sb
```

**Loop optimizations:**
- **Loop unrolling**: Execute multiple iterations per loop cycle
- **Loop vectorization**: Use SIMD instructions for parallel element processing
- **Range check elimination**: Remove array bounds checks when provably safe

**Null check elimination:**
```java
// After proving obj is non-null (e.g., from a prior dereference),
// subsequent null checks on obj are removed
if (obj.field > 0) {       // implicit null check here
    return obj.method();   // null check eliminated — obj already proven non-null
}
```

### Deoptimization

The JIT makes speculative optimizations based on profiling. If assumptions are violated at
runtime, the compiled code is thrown away and execution reverts to the interpreter.

**Common deoptimization triggers (uncommon traps):**
- Class hierarchy changes (loaded subclass invalidates devirtualization)
- Null pointer encountered in a path assumed non-null
- Array store check failure
- Unexpected type at call site (profile-guided assumptions violated)

```
Compile with assumption: "obj is always type Foo"
         │
         ▼
[Running compiled code] ──── obj is actually type Bar ────► DEOPTIMIZE
         │                                                       │
         ▼                                                       ▼
   Continue optimized                                    Back to interpreter
                                                         Recompile with new profile
```

### Diagnostic Flags

```bash
# Show what's being compiled
-XX:+PrintCompilation

# Output:
# 42  3  4  com.example.MyClass::hotMethod (58 bytes)
# ↑   ↑  ↑        ↑                          ↑
# id  │  tier   method                     bytecode size
#   compile level

# Show inlining decisions
-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining

# Output:
# @ 12   com.example.Helper::compute (8 bytes)   inline (hot)
# @ 25   java.util.HashMap::get (25 bytes)        too big

# Log compilations to file
-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=compilation.log
# Analyze with JITWatch (GUI tool)
```

### Graal JIT Compiler

Graal is a JIT compiler written in Java (as opposed to C2, which is written in C++):
- Easier to maintain and extend
- Better optimizations for some workloads (especially **partial escape analysis** — elides
  allocations on any control-flow path where the object doesn't escape, even if it escapes
  on another path)
- Foundation for GraalVM (polyglot runtime, native-image AOT compilation)

**History caveat (important in 2026):** JEP 410 removed the experimental in-tree Graal JIT and
`jaotc` from OpenJDK in JDK 17, while keeping JVMCI so external compilers can still plug in.
If you want Graal as your JIT, use a GraalVM distribution rather than stock HotSpot.

### GraalVM Native Image

GraalVM **Native Image** performs a **closed-world AOT compilation**: it analyses the whole
program, removes unused code, and produces a standalone native executable. No JVM at runtime.

| Aspect | HotSpot + JIT | GraalVM Native Image |
|--------|---------------|----------------------|
| Startup | ~1-10s (framework dependent) | ~10-100ms |
| Peak throughput | 100% baseline | ~70-85% of HotSpot C2 |
| Memory (RSS) | 1-2 GB typical | 100-400 MB typical |
| Warm-up required | Yes (minutes to peak) | No |
| Runtime reflection | Free | Requires config (`reachability-metadata.json`) |
| Dynamic classloading | Free | Not supported |
| Sweet spot | Long-running services | Serverless, CLIs, short-lived jobs |

Typical use cases in 2026: Spring Boot 3.x and Quarkus 3.x produce Native Image binaries with
one command; AWS Lambda cold-start drops from ~4s to ~150ms; Kubernetes pods scale up faster
under bursty load.

### Project Leyden — AOT Class Loading, Linking, and Profiling

Project Leyden is a multi-release effort in OpenJDK to bring AOT benefits to **stock HotSpot**
without the closed-world restrictions of Native Image. Status in April 2026:

| JEP | Release | Feature |
|-----|---------|---------|
| **JEP 483** | JDK 24 | Ahead-of-Time Class Loading & Linking |
| **JEP 514** | JDK 25 | AOT Command-Line Ergonomics |
| **JEP 515** | JDK 25 | AOT Method Profiling |
| **JEP 516** | JDK 26 | GC-agnostic AOT cache |

**Workflow (JDK 25, simplified by JEP 514):**

```bash
# 1. Training run — record classes loaded + method profiles under representative load
java -XX:AOTCacheOutput=app.aot -jar app.jar run-representative-load

# 2. Production run — consumes cache, skips class loading/linking and primes the JIT
java -XX:AOTCache=app.aot -jar app.jar
```

The public story is faster startup and better warmup without Native Image's closed-world
restrictions. Unlike Native Image, the JVM keeps full dynamic capabilities
(reflection, dynamic class loading, JVMTI).

**Mental model:**
- CDS (JDK 5+) — share read-only metadata for JDK classes
- AppCDS (JDK 10+) — extend CDS to application classes
- AOT Class Loading (JEP 483) — pre-link classes so they skip verification/loading
- AOT Method Profiling (JEP 515) — pre-seed the JIT profile database

### Compact Object Headers (JEP 450 → JEP 519)

Traditionally every Java object on a 64-bit JVM with compressed oops carries a **12-byte
header** (8-byte mark word + 4-byte Klass pointer). Compact Object Headers shrink this to
**8 bytes** by packing the compressed Klass pointer directly into the mark word.

| JEP | Release | Status |
|-----|---------|--------|
| **JEP 450** | JDK 24 | Experimental |
| **JEP 519** | JDK 25 | Product feature (not default) |

Enable: `-XX:+UseCompactObjectHeaders`

Impact (from SPECjbb2015 and Amazon/Oracle production benchmarks):
- ~10-20% lower live heap for object-heavy workloads
- Up to 22% less heap on SPECjbb2015
- ~8% lower CPU in the same workload (fewer cache misses, less GC)
- Allocation-heavy apps see 5-30% CPU savings

Interview-worthy detail: compressing the Klass pointer from 32 to 22 bits capped the number
of loaded classes at ~4 million — a limit nobody hits in practice but one you might be asked
to justify.

---
