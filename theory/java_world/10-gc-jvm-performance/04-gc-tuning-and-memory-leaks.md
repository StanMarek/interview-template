# GC Tuning and Memory Leaks

Practical tuning heuristics, log-reading tips, and the leak patterns interviewers expect you to
diagnose quickly.

## 6. GC Tuning

### Key JVM Flags

| Flag | Description | Typical Value |
|------|-------------|---------------|
| `-Xms` | Initial heap size | Same as `-Xmx` for predictability |
| `-Xmx` | Maximum heap size | Based on available RAM (50-75%) |
| `-Xmn` | Young generation size (Serial/Parallel) | 1/3 to 1/4 of heap |
| `-XX:NewRatio` | Old/Young ratio (default 2 → 1/3 young) | 2-4 |
| `-XX:SurvivorRatio` | Eden/Survivor ratio (default 8 → Eden=80%) | 6-10 |
| `-XX:MaxGCPauseMillis` | Target max pause goal (mainly G1) | 50-200 |
| `-XX:GCTimeRatio` | Throughput target (Parallel GC) | 19 (=5% GC overhead) |
| `-XX:MaxMetaspaceSize` | Limit metaspace growth | 256m-512m |
| `-XX:MaxDirectMemorySize` | Limit direct byte buffers | Based on workload |

### GC Logging

Modern GC logging uses the **unified logging framework** (JDK 9+). The old
`-XX:+PrintGCDetails -Xloggc:...` flags were removed in JDK 9 — any legacy script still using
them will fail on modern JVMs.

```bash
# Basic GC logging (recommended production default)
-Xlog:gc:file=gc.log:time,uptime,level,tags:filecount=5,filesize=50m

# Detailed GC logging — all GC subsystems
-Xlog:gc*:file=gc.log:time,uptime,level,tags:filecount=10,filesize=100m

# Include safepoint + classloading + heap info
-Xlog:gc*,safepoint,classload:file=gc.log::filecount=10,filesize=100m

# Heap-dump-on-OOM (production safety net)
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/heapdumps/
-XX:+ExitOnOutOfMemoryError   # optional: terminate JVM instead of limping
```

**Reading G1 GC logs:**
```
[2024-01-15T10:30:45.123+0000][info][gc] GC(42) Pause Young (Normal)
    (G1 Evacuation Pause) 1024M->256M(4096M) 12.345ms
       ↑                        ↑     ↑    ↑      ↑
   GC cycle #             before after heap   pause time
```

**Reading generational ZGC logs:**
```
[3.142s][info][gc] GC(7) Minor Collection (Allocation Rate) 128M(12%)->64M(6%) 0.421ms
[3.200s][info][gc] GC(8) Major Collection (Proactive) 1G(50%)->512M(25%) 0.812ms
```

Note: ZGC reports **Minor** (young) and **Major** (old) cycles separately under the
generational model. Pauses are reported in milliseconds with sub-ms precision; if you see
multi-millisecond pauses in ZGC logs, investigate time-to-safepoint (TTSP), not GC itself.

Key things to look for (any collector):
- **Pause times trending up**: Heap pressure, too many mixed GCs
- **Full GCs**: Indicates G1/ZGC falling behind — increase heap or tune IHOP / SoftMaxHeapSize
- **To-space exhausted**: Evacuation failure — increase `-XX:G1ReservePercent`
- **Humongous allocation**: Objects > region_size/2 — increase region size
- **Allocation stalls (ZGC)**: Mutator blocked waiting for free pages — raise `-Xmx` or
  `-XX:ConcGCThreads`

### Analyzing GC Logs at Scale

- **GCeasy** (gceasy.io) — free online analyser for any OpenJDK GC log, produces latency
  percentiles, throughput %, and anomaly report.
- **GCViewer** — offline desktop tool for pause-time graphs.
- **JFR** can emit `jdk.GarbageCollection`, `jdk.GCPhasePause`, `jdk.PromotionFailed` and
  many more events that correlate GC with allocation sources and thread behavior — prefer
  JFR over raw GC logs in production observability pipelines.

### Common Tuning Scenarios

**High allocation rate (many short-lived objects):**
- Increase Eden size (`-XX:G1NewSizePercent`)
- Ensure TLABs are not too small
- Consider ZGC (generational) which handles high allocation rates well
- Profile allocation hotspots with JFR or async-profiler

**Long GC pauses:**
- Reduce `-XX:MaxGCPauseMillis` (G1 will shrink Eden to compensate)
- Increase concurrent GC threads (`-XX:ConcGCThreads`)
- Switch to ZGC for <1ms requirement
- Check for humongous allocations causing full GCs

**Frequent Full GCs:**
- Concurrent marking starting too late — lower IHOP or trust adaptive IHOP
- Too many mixed GC candidates — increase `-XX:G1MixedGCCountTarget`
- Heap too small for live set — increase `-Xmx`
- Memory leak — take heap dump and analyze

**Promotion failures:**
- Increase old gen (decrease `-XX:G1NewSizePercent`)
- Increase `-XX:G1ReservePercent` to keep evacuation headroom
- Check for premature promotion (tenuring threshold too low)

### Collector Decision Tree (JDK 25)

```
What is your primary concern?
│
├── Throughput (batch processing, analytics, Spark executors)
│   └── Use Parallel GC (-XX:+UseParallelGC)
│       └── Tune: -XX:GCTimeRatio, -XX:MaxGCPauseMillis
│
├── Balanced latency + throughput (typical REST services, 1-32GB heap)
│   └── Use G1 GC (default, no flag needed)
│       └── Tune: -XX:MaxGCPauseMillis=50-200 (soft target)
│       └── Consider: -XX:+UseStringDeduplication if heap has many duplicate Strings
│
├── Strict latency SLA (<1ms p99), any heap size, headroom available
│   └── Use Generational ZGC (-XX:+UseZGC)  // JDK 25: ZGC is generational-only
│       └── Minimal tuning needed (self-adaptive)
│       └── Validate memory overhead with production-like load; don't guess
│
├── Low latency on a Shenandoah-enabled Temurin/Red Hat build
│   └── Use Generational Shenandoah
│       (-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational)
│
├── Memory-constrained (containers, small heaps < 256MB, CLI tools)
│   └── Use Serial GC (-XX:+UseSerialGC)
│
└── Short-lived process, zero-GC overhead acceptable (benchmarks, CI runs)
    └── Use Epsilon (-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC)
        // JVM halts on OOM — never reclaims memory
```

### Historical Note: CMS is Gone

The **Concurrent Mark Sweep (CMS)** collector was deprecated in JDK 9 (JEP 291) and **removed
entirely in JDK 14** (JEP 363). Any advice mentioning CMS tuning (e.g.,
`-XX:+UseConcMarkSweepGC`, `CMSInitiatingOccupancyFraction`) is obsolete; on modern JDKs those
flags cause the JVM to fail at startup. G1 is the drop-in replacement for CMS's original
"mostly-concurrent, moderate-latency" niche; ZGC/Shenandoah fill the "sub-ms pause" niche CMS
never reached.

---

## 7. Memory Leaks

In Java, a "memory leak" means objects that are still reachable (not garbage) but no longer
needed by the application. The GC cannot collect them because they have a path from a GC root.

### Common Causes

**1. Static collections that grow unboundedly:**
```java
// LEAK: entries never removed
public class EventRegistry {
    private static final List<EventHandler> handlers = new ArrayList<>();

    public static void register(EventHandler handler) {
        handlers.add(handler);
    }
    // No unregister method!
}
```

**2. Listener/callback accumulation:**
```java
// LEAK: anonymous listener holds reference to enclosing object
button.addActionListener(e -> processClick(e));
// If button outlives the enclosing object, the enclosing object is retained

// FIX: Use weak references or explicitly remove listeners
button.removeActionListener(listener);
```

**3. ThreadLocal leaks (especially in thread pools):**
```java
// LEAK: Thread pool reuses threads — ThreadLocal values persist
private static final ThreadLocal<UserContext> context = new ThreadLocal<>();

public void handleRequest(Request req) {
    context.set(new UserContext(req.getUser()));
    processRequest(req);
    // Missing: context.remove() — UserContext leaks if thread is reused
}

// FIX: Always clean up in a finally block
public void handleRequest(Request req) {
    context.set(new UserContext(req.getUser()));
    try {
        processRequest(req);
    } finally {
        context.remove();
    }
}
```

**Virtual threads change the calculus**: Virtual threads (JEP 444, JDK 21) are created per
task and discarded — they don't pool, so a "missing `remove()`" bug no longer leaks forever.
But `ThreadLocal` allocates a fresh copy per virtual thread, and millions of virtual threads
can amplify per-thread state into substantial heap pressure. Prefer **`ScopedValue`**
(JEP 506, finalized in JDK 25) for request-scoped context under virtual threads — it's
immutable, inherited by child structured-concurrency tasks, and doesn't require cleanup.

**4. Classloader leaks (common in application servers):**
When a web application is redeployed, a new classloader is created. If any object from the old
classloader is retained (via a static reference, a thread, or a JVM-level cache), the entire
old classloader and all its classes cannot be garbage collected.

Common culprits: JDBC drivers registered in DriverManager, logging frameworks holding references,
`ThreadLocal` values referencing classes from the old classloader.

**5. Unclosed resources holding native memory:**
```java
// LEAK: InputStream holds native file descriptor and potentially a buffer
public byte[] readFile(String path) throws IOException {
    FileInputStream fis = new FileInputStream(path);
    return fis.readAllBytes();
    // fis never closed if readAllBytes() throws
}

// FIX: try-with-resources
public byte[] readFile(String path) throws IOException {
    try (FileInputStream fis = new FileInputStream(path)) {
        return fis.readAllBytes();
    }
}
```

**6. String.intern() abuse:**
```java
// LEAK: the string table (hash table of interned entries) lives in native memory since
// JDK 7; the interned String objects themselves live in the regular Java heap and are
// subject to GC once unreachable. But as long as the table entries remain reachable from
// somewhere, interned strings stay alive.
for (String data : hugeDataSet) {
    processedData.add(data.intern());  // millions of unique strings interned
}
```

### Detection

**Heap dump analysis:**
```bash
# Trigger heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Or via jcmd (preferred)
jcmd <pid> GC.heap_dump /path/to/heap.hprof

# Or automatically on OOM
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/to/dumps/
```

**Analyze with Eclipse MAT (Memory Analyzer Tool):**
1. Open the heap dump
2. Run "Leak Suspects Report" — automatically identifies suspicious retention paths
3. Check "Dominator Tree" — objects retaining the most memory
4. Use OQL (Object Query Language) to query specific classes

**Live monitoring:**
```bash
# Monitor heap usage over time
jstat -gcutil <pid> 1000

# Output:
#  S0     S1     E      O      M     CCS    YGC   YGCT    FGC  FGCT   CGC  CGCT    GCT
#  0.00  97.02  65.19  45.12  98.01  95.62   42   0.356    2  0.289    8  0.076   0.721
#                              ↑
#                     Old gen growing over time = potential leak
```

### Prevention Patterns

- Use `WeakHashMap` or Guava's `CacheBuilder` with `weakKeys()`/`softValues()` for caches
- Prefer bounded caches with eviction policies (LRU, time-based)
- Always use try-with-resources for `AutoCloseable` resources
- Remove listeners/callbacks when they are no longer needed
- Always call `ThreadLocal.remove()` when done (especially in thread pools)
- Use `-XX:+HeapDumpOnOutOfMemoryError` in production as a safety net

---
