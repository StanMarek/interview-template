# JFR, JMC, and Diagnostics

The current production-grade tooling story: JFR first, then JMC, `jcmd`, async-profiler, and
heap-dump analysis when you need more detail.

## 8. Profiling Tools

### JFR (Java Flight Recorder)

JFR is a low-overhead (~1-2%) profiling framework built into the JVM. It records "events"
(GC pauses, thread parks, allocations, method samples, I/O, etc.) to a binary format.
JFR went fully open-source in JDK 11 (JEP 328) and is available in every OpenJDK build — the
old "Oracle JDK only" limitation is long gone.

**Starting JFR:**
```bash
# Via JVM flags
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr MyApp

# Continuous recording (ring buffer)
java -XX:StartFlightRecording=disk=true,maxsize=500m,maxage=1h MyApp

# Via jcmd on a running process
jcmd <pid> JFR.start duration=60s filename=recording.jfr
jcmd <pid> JFR.dump filename=snapshot.jfr
jcmd <pid> JFR.stop
```

**Programmatic JFR (custom events):**
```java
import jdk.jfr.*;

@Name("com.example.OrderProcessed")
@Label("Order Processed")
@Category({"Application", "Orders"})
@StackTrace(false)
public class OrderEvent extends Event {
    @Label("Order ID")
    String orderId;

    @Label("Processing Time (ms)")
    @Timespan(Timespan.MILLISECONDS)
    long processingTime;

    @Label("Item Count")
    int itemCount;
}

// Usage
public Order processOrder(OrderRequest request) {
    OrderEvent event = new OrderEvent();
    event.begin();

    Order result = doProcessing(request);

    event.orderId = result.getId();
    event.processingTime = result.getProcessingTime();
    event.itemCount = result.getItems().size();
    event.commit();

    return result;
}
```

**JFR Event Streaming (JEP 349, JDK 14+):**

Traditionally JFR wrote to a file and you analysed it after the fact. JEP 349 exposes events
**as they happen** via `jdk.jfr.consumer.RecordingStream`, which is the foundation of modern
cloud observability pipelines (Datadog, New Relic, Grafana Pyroscope, OpenTelemetry JFR
exporter all consume it):

```java
try (RecordingStream rs = new RecordingStream()) {
    rs.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
    rs.enable("jdk.GarbageCollection");
    rs.enable("jdk.GCPhasePause");

    rs.onEvent("jdk.GCPhasePause", event -> {
        long ms = event.getDuration().toMillis();
        if (ms > 100) {
            log.warn("Long GC pause: {}ms at {}", ms, event.getStartTime());
        }
    });
    rs.onEvent("jdk.CPULoad", event ->
        metrics.gauge("jvm.cpu.load", event.getFloat("machineTotal")));

    rs.startAsync();
}
```

**Remote streaming** via `jdk.management.jfr.RemoteRecordingStream` (JDK 16+) connects
over JMX to another JVM — useful for sidecar agents in Kubernetes. Production deployment
patterns:

- **Always-on continuous recording**: `-XX:StartFlightRecording=disk=true,maxsize=4g,
  maxage=24h,settings=profile` — 24h ring buffer, dumped on SIGTERM via a pre-stop hook.
- **On-demand capture**: `jcmd <pid> JFR.dump` triggered by alerting (e.g., when p99 latency
  spikes, dump the last 10 minutes).
- **Stream to backend**: a tiny Java agent consumes `RecordingStream` events, serialises them
  to protobuf, ships to OTLP collector / Datadog / Pyroscope.

### What's New in JFR (JDK 25)

Three JEPs landed together in JDK 25 and are common interview territory:

| JEP | Feature | Why it matters |
|-----|---------|----------------|
| **JEP 509** | JFR CPU-Time Profiling (Experimental, Linux) | Adds CPU-time sampling on Linux and improves attribution when threads spend time in native code |
| **JEP 518** | JFR Cooperative Sampling | Moves stack walking to safepoints while minimizing, not eliminating, safepoint bias |
| **JEP 520** | JFR Method Timing & Tracing | Bytecode-instrumentation-based exact timing for specific methods (no sampling — every call counted) |

The net effect: JFR in JDK 25 is materially better for production profiling than older mental
models suggest, but async-profiler still remains the sharper tool for some CPU-only deep dives.

### JMC (Java Mission Control)

GUI tool for analyzing JFR recordings. Key views:
- **Automated Analysis**: Machine-generated report of potential issues
- **Memory**: Allocation rate, heap usage, GC pauses timeline
- **Threads**: Lock contention, thread states, latency analysis
- **Hot Methods**: CPU-intensive methods
- **I/O**: File and socket I/O timing
- **Exceptions**: Exception frequency and types

### async-profiler

Open-source profiler (github.com/async-profiler/async-profiler) that uses `perf_events`
(Linux) or `DTrace` (macOS) for accurate sampling. Avoids safepoint bias because it relies
on `AsyncGetCallTrace`, a HotSpot internal API that walks the stack of **any** running thread,
not just threads at a safepoint.

```bash
# CPU profiling — generate flame graph
./profiler.sh -d 30 -f flamegraph.html <pid>

# Allocation profiling — find allocation hotspots (TLAB-based sampling)
./profiler.sh -d 30 -e alloc -f alloc-flame.html <pid>

# Lock profiling — contention hotspots
./profiler.sh -d 30 -e lock -f lock-flame.html <pid>

# Wall-clock profiling — includes time in I/O, locks, parked threads
./profiler.sh -d 30 -e wall -f wall-flame.html <pid>

# Differential flame graph — compare before/after
./profiler.sh -d 30 -f before.html <pid>
# ... apply fix ...
./profiler.sh -d 30 -f after.html <pid>
# render diff in JFR viewer or flamegraph.pl --diff
```

**When to pick async-profiler over JFR:**
- You need CPU profiles with minimal overhead and the richest flame-graph ecosystem.
- You're profiling methods in native libraries (JNI, Netty's native epoll, OpenSSL).
- You care about `cycles:p` / `instructions:p` hardware events rather than method samples.

**When to pick JFR:** structured event correlation (GC + allocation + lock + method samples
in one timeline), long-running continuous recording, and integration with JMC's automated
analysis. In JDK 25, the JEP 518 + 509 improvements narrow the gap significantly.

### Command-Line Diagnostics

```bash
# jcmd — Swiss Army knife; ships in every JDK; requires no attach agent beyond jcmd itself
jcmd <pid> help                        # List all available commands for this JVM
jcmd <pid> VM.version                  # Version, vendor, build info
jcmd <pid> VM.flags                    # All active JVM flags (including auto-tuned)
jcmd <pid> VM.system_properties        # All system properties
jcmd <pid> VM.classloaders             # Classloader hierarchy (useful for leak hunting)
jcmd <pid> GC.heap_info                # Current heap layout
jcmd <pid> GC.heap_dump /tmp/heap.hprof  # Full HPROF heap dump (preferred over jmap)
jcmd <pid> GC.class_histogram          # Class histogram (live)
jcmd <pid> GC.run                      # Request a full GC (use sparingly)
jcmd <pid> Thread.print                # Thread dump
jcmd <pid> Thread.dump_to_file /tmp/thr.json  # JSON thread dump (JDK 21+, JEP 425 virtual threads)
jcmd <pid> VM.native_memory summary    # Native memory tracking (NMT), requires -XX:NativeMemoryTracking=summary
jcmd <pid> VM.native_memory baseline   # Snapshot for diffing
jcmd <pid> VM.native_memory detail.diff  # Diff vs baseline — find native leaks

# JFR via jcmd (see §8 JFR section)
jcmd <pid> JFR.start duration=60s filename=rec.jfr
jcmd <pid> JFR.dump filename=snap.jfr
jcmd <pid> JFR.stop

# jstat — GC statistics
jstat -gcutil <pid> 1000         # GC stats every second
jstat -gccause <pid> 1000        # GC stats + cause of last GC

# jstack — thread dump (deadlock detection)
jstack <pid>                     # Basic thread dump
jstack -l <pid>                  # Include lock info (owned + waiting)

# jmap — legacy; prefer `jcmd GC.heap_dump` which does not require -F on a hung JVM
jmap -histo <pid>                # Object histogram
jmap -histo:live <pid>           # Live-only (triggers GC first)
```

### Heap Dumps — Producing, Sizing, Analysing

```bash
# Automatic on OOM (always enable in production)
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/heapdumps/

# On-demand via jcmd (preferred; works on running JVM without -F)
jcmd <pid> GC.heap_dump /tmp/heap.hprof

# Compressed (gzip) — saves disk space on large heaps (JDK 17+)
jcmd <pid> GC.heap_dump -gz=9 /tmp/heap.hprof.gz

# Via kill signal (Linux; requires -XX:+HeapDumpOnSignal + -XX:HeapDumpPath)
kill -10 <pid>    # SIGUSR1 — takes heap dump, does not kill process
```

**Rules of thumb:**
- A heap dump is approximately the size of live data in the heap (not the whole heap).
- On a 16GB heap with 8GB live, expect ~8GB HPROF file → budget ~20GB disk for uncompressed +
  gzipped copy.
- Always dump with `GC.heap_dump` before restarting a suspected-leak JVM.

### Analysis tooling

- **Eclipse MAT** — de facto standard. Load HPROF, run "Leak Suspects Report", inspect
  Dominator Tree, query with OQL.
- **JDK Mission Control (JMC)** — best for JFR + Oracle's newer heap analyser.
- **Heaphero.io / GCeasy.io** — online analysers for heap dumps and GC logs; convenient but
  think about sensitive data before uploading.
- **VisualVM** — still maintained as a separate project (visualvm.github.io). Good for quick
  interactive use on a dev box; in production prefer JFR + async-profiler.

---
