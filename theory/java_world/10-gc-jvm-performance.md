# GC, JVM & Performance — Senior Engineer Interview Preparation

> **Java 25 LTS baseline (September 2025).** CMS was removed in JDK 14. The 2026 collector
> landscape is effectively: Serial, Parallel, G1 (default), Generational ZGC (default ZGC mode
> since JDK 21), Generational Shenandoah (production as of JDK 25, JEP 521), Epsilon (no-op).
> This guide targets a senior engineer preparing interviews in 2026 — all examples assume
> JDK 21/25 semantics unless explicitly noted.

---

## 1. GC Fundamentals Review

### Generational Hypothesis

The generational hypothesis states that **most objects die young**. Empirical studies across languages
and workloads consistently show that 80-95% of allocated objects become unreachable within
microseconds of creation. This observation drives the design of generational collectors:

- **Young generation**: Small, collected frequently, fast (most objects are dead).
- **Old generation**: Large, collected infrequently, expensive (most objects are alive).

This works because copying a few survivors is cheaper than scanning a large heap full of live objects.
The hypothesis breaks down for workloads that allocate many long-lived objects (e.g., large in-memory
caches), which is why tuning generation sizes matters.

### Object Allocation: TLABs

Every `new` in Java triggers an allocation. Naively, this would require a global lock on the heap pointer.
**Thread-Local Allocation Buffers (TLABs)** eliminate this contention:

```
Heap (Eden Space)
┌─────────────────────────────────────────────────────────────────┐
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐│
│  │ Thread-1 │  │ Thread-2 │  │ Thread-3 │  │   Unallocated    ││
│  │  TLAB    │  │  TLAB    │  │  TLAB    │  │                  ││
│  │ [bump►]  │  │ [bump►]  │  │ [bump►]  │  │                  ││
│  └──────────┘  └──────────┘  └──────────┘  └──────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

- Each thread gets a private chunk of Eden (typically 1-2% of Eden).
- Allocation is a simple **bump pointer** increment — no locking, no CAS.
- When a TLAB fills up, the thread requests a new one (requires CAS on the shared pointer).
- Objects too large for a TLAB are allocated directly in shared Eden (or Humongous regions in G1).

Key flags:
- `-XX:+UseTLAB` (enabled by default)
- `-XX:TLABSize=<bytes>` — initial TLAB size (JVM auto-tunes this)
- `-XX:-ResizeTLAB` — disable dynamic TLAB resizing

### GC Roots and Reachability Analysis

Java uses **tracing garbage collection** (not reference counting). The collector starts from a set of
**GC roots** and traverses all reachable references. Anything not reached is garbage.

**GC roots include:**
- Local variables and parameters on thread stacks
- Active threads themselves
- Static fields of loaded classes
- JNI references (global and local)
- Monitor locks (synchronized objects)
- Certain JVM internal references (system classloader, etc.)

```
GC Roots                  Heap
──────────                ──────────────────────
                          ┌───┐
[Stack frame] ──────────► │ A │──────► ┌───┐
                          └───┘        │ B │──► ┌───┐
[Static field] ─────────► ┌───┐        └───┘    │ D │ (reachable)
                          │ C │                  └───┘
                          └───┘
                                       ┌───┐
                          ┌───┐  ◄──── │ F │ (unreachable cycle)
                          │ E │──────► └───┘
                          └───┘
```

Objects E and F form a cycle but have no path from any GC root — they are garbage. This is why
Java does not need reference counting and handles cycles naturally.

### Safepoints

A safepoint is a point in program execution where the thread's state is well-described — all GC roots
are known, and the heap is consistent. The JVM needs **all** threads at safepoints to perform
certain operations (including most GC phases).

**When safepoints are inserted:**
- Method returns
- Loop back-edges (but NOT in counted loops with int index — a notorious pitfall)
- JNI calls (threads in native code are considered "at safepoint" by convention)
- Allocation points

**The safepoint problem with counted loops (historical):**
```java
// Pre-JDK 10: No safepoint inside this loop — JVM cannot stop this thread!
for (int i = 0; i < hugeArray.length; i++) {
    sum += hugeArray[i];
}

// Historical fix: Use a long index (not a counted loop) or add explicit safepoint
for (long i = 0; i < hugeArray.length; i++) {  // long → safepoint inserted
    sum += hugeArray[i];
}
```

**Note:** Since JDK 10, `-XX:+UseCountedLoopSafepoints` is the default, so counted `int` loops
DO get safepoints. This issue was historically relevant pre-JDK 10; today the concern is mostly
gone.

**Time-to-safepoint (TTSP)**: The latency between the JVM requesting a safepoint and all threads
reaching one. Even a single slow thread (stuck in a long counted loop or paged-out memory) delays
the entire JVM. Monitor with `-XX:+SafepointTimeout -XX:SafepointTimeoutDelay=<ms>`.

**Safepoint bias in profiling**: Sampling profilers that rely on safepoints (like `jstack`) only see
thread state at safepoints, missing what happens between them. This is why **async-profiler** (which
uses `AsyncGetCallTrace`) gives more accurate results.

---

## 2. GC Algorithms Deep Dive

### Mark-Sweep

1. **Mark phase**: Traverse from GC roots, mark all reachable objects.
2. **Sweep phase**: Scan heap linearly, free unmarked objects.

```
Before:  [A][B][ ][C][ ][D][E][ ][F]
Mark:     *  *      *         *
Sweep:   [A][B][_][C][_][_][_][_][F]
                fragmented!
```

**Trade-offs:**
- Simple to implement
- Does not move objects — no need to update references
- Causes **fragmentation** — free memory scattered in small chunks
- Allocation becomes expensive (free-list management)

### Mark-Compact

1. **Mark phase**: Same as mark-sweep.
2. **Compact phase**: Slide all live objects to one end of the heap.

```
Before:  [A][B][ ][C][ ][ ][ ][ ][F]
Mark:     *  *      *               *
Compact: [A][B][C][F][  free space  ]
```

**Trade-offs:**
- No fragmentation — bump-pointer allocation restored
- Requires updating all references to moved objects (expensive)
- Long pause times for large heaps

### Copying Collector

Divides memory into two semi-spaces. Copies live objects from one to the other.

```
FROM-SPACE:  [A][ ][B][ ][C][ ][ ][ ]
                   ↓ copy live
TO-SPACE:    [A][B][C][  free space  ]
```

**Trade-offs:**
- Very fast for young generation (few survivors to copy)
- No fragmentation
- Wastes half the memory (two semi-spaces)
- Good for high mortality rates (young gen), bad for old gen

### Why Java Doesn't Use Reference Counting

Reference counting tracks the number of references to each object. When the count drops to zero,
the object is freed. Languages like Python and Swift use it (with cycle detection).

**Problems:**
1. **Cyclic references**: A → B → A — both have count 1, never freed.
2. **Thread safety**: Incrementing/decrementing counts on every reference assignment requires
   atomic operations — very expensive in multi-threaded Java.
3. **Cache pollution**: Every reference write touches the object header (count field).
4. **No batching**: Deallocation cascades can cause latency spikes.

Java's tracing GC handles cycles naturally and can batch collection work.

### Tri-Color Marking

Used by concurrent collectors (G1, ZGC, Shenandoah) to allow the application (mutator) to run
during marking. Each object is classified:

| Color | Meaning |
|-------|---------|
| **White** | Not yet visited — potentially garbage |
| **Grey** | Visited, but its references not yet fully scanned |
| **Black** | Visited, all references scanned |

```
Start:     All objects white, GC roots → grey
Process:   Pick grey object, scan its references (color them grey), color it black
End:       Only white and black remain — white objects are garbage
```

**The SATB (Snapshot-At-The-Beginning) invariant** (used by G1):
The key problem with concurrent marking is that the mutator can change references while the
collector is scanning. The **lost object problem** occurs when:
1. A black object gets a new reference to a white object
2. The grey object that previously referenced the white object loses that reference

Without intervention, the white object would be incorrectly collected. SATB records the old value
of any reference overwritten during marking via a **write barrier**, ensuring the original object
graph is preserved.

**The incremental update approach** (used historically by CMS, now mainly a contrast point):
Instead of recording old values, record when a black object gains a reference to a white object.
Mark the black object grey again so it gets rescanned. CMS was removed in JDK 14 (JEP 363); SATB
is the approach used by G1, ZGC, and Shenandoah today.

### Write Barriers and Card Tables

Cross-generational references (old → young) are common and problematic: we want to collect young
gen without scanning the entire old gen.

**Card table** (used by Serial, Parallel, and partially by G1):
- Divides the heap into 512-byte "cards"
- One byte per card in a card table array
- When an old-gen object's reference field is written, the corresponding card is "dirtied"
- During young GC, only dirty cards are scanned for cross-gen references

```
Old Generation Heap
┌─────┬─────┬─────┬─────┬─────┬─────┐
│Card0│Card1│Card2│Card3│Card4│Card5│
└─────┴─────┴─────┴─────┴─────┴─────┘

Card Table (1 byte per card)
[clean][DIRTY][clean][DIRTY][clean][clean]
         ↓              ↓
    Scan these cards during young GC
```

The write barrier is injected by the JIT compiler at every reference store:

```java
// Source code:
parent.child = newChild;

// What the JIT actually emits (pseudocode):
parent.child = newChild;
cardTable[((uintptr_t)&parent.child) >> 9] = DIRTY;  // mark card dirty
```

This barrier adds ~1-2 instructions per reference write — a small price for avoiding full old-gen scans.

---

## 3. G1 Garbage Collector Internals

G1 (Garbage-First) is the default collector since Java 9. It is a **regionalized**, **generational**,
**mostly concurrent** collector designed to meet pause-time goals.

### Region-Based Heap

G1 divides the heap into **equally-sized regions** (1-32MB, typically targeting ~2048 regions).
Each region is dynamically assigned a role:

```
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│Eden │ Old │Surv │ Old │Eden │Free │ Old │Eden │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│Free │Hum. │Hum. │Eden │ Old │Surv │Free │ Old │
│     │start│cont.│     │     │     │     │     │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│ Old │Eden │Free │ Old │Free │Eden │ Old │Free │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘

Region types: Eden | Survivor | Old | Humongous | Free
```

### Young GC (Evacuation Pause)

Triggered when Eden regions are exhausted. **Stop-the-world**.

1. Identify the **collection set (CSet)**: all Eden + Survivor regions.
2. Copy live objects from CSet to Survivor or Old regions (tenuring).
3. Reclaim CSet regions (now free).

Young GC is always stop-the-world but typically fast (5-50ms) because Eden is sized to meet
the pause-time target.

### Concurrent Marking Cycle

G1 runs a concurrent marking cycle to identify old-gen regions with the most garbage.

| Phase | STW? | Description |
|-------|------|-------------|
| Initial Mark | Yes (piggybacked on young GC) | Mark objects directly reachable from GC roots |
| Concurrent Mark | No | Traverse object graph, uses SATB write barrier |
| Remark | Yes | Drain SATB buffers, process remaining grey objects |
| Cleanup | Partly | Calculate liveness per region, reclaim empty regions |

### Mixed GC

After concurrent marking completes, G1 performs **mixed collections**: young GC + selected old
regions with the highest garbage ratio.

```
Collection Set Selection:
1. All Eden and Survivor regions (mandatory)
2. Old regions ranked by garbage ratio (top N)
   ┌──────────┬─────────────┬────────────┐
   │Region #42│ Region #17  │ Region #88 │  ← most garbage → least garbage
   │ 90% dead │  85% dead   │  70% dead  │
   └──────────┴─────────────┴────────────┘
```

G1 spreads mixed collections across multiple cycles (`-XX:G1MixedGCCountTarget=8` by default)
to avoid long pauses.

### Full GC

A last-resort stop-the-world compacting collection of the entire heap. Triggers when:
- Evacuation failure (no free regions to copy to)
- Concurrent marking cannot keep up with allocation rate
- Humongous allocation failure

Full GC in G1 is **single-threaded** in older JDK versions (very slow) but **multi-threaded**
since JDK 10.

### Remembered Sets (RSets)

Each region maintains a remembered set — a data structure tracking which other regions contain
pointers into it. This allows G1 to collect a subset of regions without scanning the entire heap.

```
Region A (Old)                    Region B (Old, in CSet)
┌─────────────────┐              ┌─────────────────┐
│ obj1.ref = ──────────────────► │ obj2            │
│                 │              │ RSet: {A:card7} │
│                 │              │                 │
└─────────────────┘              └─────────────────┘
```

**Memory overhead**: RSets typically consume 1-5% of heap on modern G1 (JDK 11+); the 5-20%
figure is legacy pre-JDK 9. The finer-grained the tracking, the more memory used. G1 uses a
multi-level scheme:
- Sparse: direct card list (few entries)
- Fine: per-region card bitmap (moderate entries)
- Coarse: single bit per region (many entries, less precise)

### Humongous Objects

Objects larger than half a region are **humongous**. They are:
- Allocated directly in contiguous old-gen regions
- Never moved (until JDK 8u40, which added humongous reclamation in young GC)
- Can cause fragmentation if many different-sized humongous objects exist

If you see frequent humongous allocations, consider increasing `-XX:G1HeapRegionSize`.

### Region Pinning (JEP 423, JDK 22+)

Before JDK 22, G1 had to **disable the entire GC** while any Java thread was inside a JNI
critical region (`GetPrimitiveArrayCritical`). A long-running critical region could stall
every allocator thread and produce fake OOMs ("GCLocker deadlock"). JEP 423 fixes this by
**pinning only the regions that contain critical objects** — G1 maintains a per-region
critical-object counter and skips pinned regions during evacuation while collecting everything
else normally.

This removes a long-standing latency cliff for code that calls into native libraries
(JNI, SciMark-style numeric kernels, OpenCV, OpenSSL) and is transparent to application code.

### String Deduplication

`-XX:+UseStringDeduplication` (G1 only historically; extended to Parallel, ZGC, and Shenandoah
in JDK 18+) finds Strings whose `char[]`/`byte[]` backing array has the same content and
rewrites one instance to share the other's backing array. Studies at Oracle and Netflix showed
~25% of live heap is Strings, ~half of which are duplicates — typical savings are 5-15% of
live set.

Eligibility: a String is considered after it survives `StringDeduplicationAgeThreshold=3`
GC cycles (tunable). Deduplication runs on a background thread, so it adds minimal pause-time
cost.

### Adaptive IHOP

IHOP (Initiating Heap Occupancy Percent) determines when to start the concurrent marking cycle.

- **Static**: `-XX:InitiatingHeapOccupancyPercent=45` (default)
- **Adaptive** (default since JDK 9): G1 learns from previous cycles to predict when to start
  marking so that collection finishes before old gen fills up.

If marking starts too late, you get evacuation failures and full GCs.

### G1 Tuning Flags

| Flag | Default | Purpose |
|------|---------|---------|
| `-XX:MaxGCPauseMillis` | 200 | Target pause time (soft goal) |
| `-XX:G1HeapRegionSize` | Auto (1-32MB) | Region size, must be power of 2 |
| `-XX:G1NewSizePercent` | 5 | Min young gen as % of heap |
| `-XX:G1MaxNewSizePercent` | 60 | Max young gen as % of heap |
| `-XX:G1MixedGCCountTarget` | 8 | Spread mixed GC over N cycles |
| `-XX:G1MixedGCLiveThresholdPercent` | 85 | Skip regions with liveness above this |
| `-XX:G1HeapWastePercent` | 5 | Stop mixed GC when reclaimable < this % |
| `-XX:InitiatingHeapOccupancyPercent` | 45 | Trigger concurrent mark (if adaptive IHOP off) |
| `-XX:G1ReservePercent` | 10 | Reserve heap % to reduce evacuation failure |
| `-XX:ConcGCThreads` | Auto | Threads for concurrent marking |
| `-XX:ParallelGCThreads` | Auto | Threads for STW phases |

---

## 4. ZGC Internals

ZGC is a **scalable, low-latency** garbage collector designed for sub-millisecond pauses regardless
of heap size. Available since JDK 11, production-ready since JDK 15.

### Colored Pointers

ZGC stores GC metadata **in the pointer itself** rather than in the object header.
On 64-bit platforms, only 48 bits are used for addressing (on most architectures). ZGC uses
the upper bits as metadata:

```
64-bit pointer layout (ZGC):
┌──────┬───┬───┬───┬───┬──────────────────────────────────────┐
│Unused│ M0│ M1│Rem│Fin│         Object Address (44 bits)      │
│(16)  │(1)│(1)│(1)│(1)│         = 16 TB address space         │
└──────┴───┴───┴───┴───┴──────────────────────────────────────┘

M0/M1  = Marked bits (alternating between GC cycles)
Rem    = Remapped (reference has been updated after relocation)
Fin    = Finalizable (reachable only through a finalizer)
```

Key insight: Multiple pointer "views" can reference the same physical memory. ZGC uses
**multi-mapping** — the same physical page is mapped at multiple virtual addresses, one for each
metadata state. This means that dereferencing any colored pointer reaches the same object.

### Load Barriers

ZGC uses a **load barrier** — code injected by the JIT at every reference load (not store).
When a thread loads a reference, the barrier checks the pointer's color bits:

```java
// Source code:
Object ref = obj.field;

// What the JIT emits (pseudocode):
Object ref = obj.field;
if (ref.colorBits != expectedColor) {
    ref = zgcSlowPath(ref);  // heal the pointer
}
// ref is now guaranteed to be "good"
```

The load barrier is what enables **concurrent relocation** — if a thread loads a stale pointer
(pointing to the old location of a relocated object), the barrier transparently redirects it
to the new location using forwarding tables.

**Self-healing**: After the slow path fixes a reference, ZGC updates the source field in-place.
Subsequent loads of the same field won't hit the slow path. This makes the overhead diminish
over time within a GC cycle.

### Concurrent Relocation

ZGC performs nearly all work concurrently:

| Phase | STW? | Description |
|-------|------|-------------|
| Pause Mark Start | Yes (~0.1ms) | Scan GC roots, begin concurrent mark |
| Concurrent Mark | No | Traverse object graph using colored pointers |
| Pause Mark End | Yes (~0.1ms) | Synchronize marking completion |
| Concurrent Process Non-Strong Refs | No | Handle soft/weak/phantom references |
| Concurrent Reset Relocation Set | No | Select regions to relocate |
| Concurrent Relocate | No | Move objects, update forwarding tables |

```
Relocation (concurrent):

Before:     Region X                    Region Y (empty)
            ┌─────────────┐            ┌─────────────┐
            │[A][ ][B][ ] │            │             │
            └─────────────┘            └─────────────┘

During:     Region X                    Region Y
            ┌─────────────┐            ┌─────────────┐
            │[A'][ ][B][ ]│     ──►    │[A]          │
            └─────────────┘            └─────────────┘
            forwarding table:
            A_old → A_new

After:      Region X (freed)           Region Y
            ┌─────────────┐            ┌─────────────┐
            │  (empty)    │            │[A][B]       │
            └─────────────┘            └─────────────┘
```

Application threads that access a relocated object during relocation hit the load barrier,
which consults the forwarding table and self-heals the reference.

### Multi-Terabyte Heaps, Sub-Millisecond Pauses

ZGC's STW phases are O(1) with respect to heap size and live data — they only scan thread
stacks and a small set of GC roots. This means:

- **4 TB heap**: same ~0.1ms pauses as a 256 MB heap
- Pause times are proportional to the number of GC roots (threads, JNI references),
  not the heap size or live set size

### Generational ZGC (JEP 439, default since JDK 23)

Before JDK 21, ZGC was **non-generational** — it treated the entire heap uniformly. This was
suboptimal because:
- Short-lived objects pollute the marking and relocation work
- Allocation rate sensitivity: non-generational ZGC struggles if objects die quickly
  (frequent cycles needed)

**Generational ZGC** (JEP 439, previewed in JDK 21, became the default in JDK 23+; the
non-generational mode was removed in JDK 25 (JEP 490); deprecated for removal in JDK 24 (JEP 458)):
- Separate young and old generations, each with their own collection cycle
- Young gen collected more frequently (fast, most objects dead)
- Old gen collected less often (more work, but fewer cycles)
- Uses **store barriers** (in addition to load barriers) to track cross-generational references —
  dirties a region-level remembered set rather than a card table
- Significantly reduces overhead for typical workloads: 50-70% less CPU, ~25% lower heap
  headroom required compared to non-generational ZGC
- Each generation still uses colored pointers and forwarding tables, so both phases remain
  fully concurrent with sub-millisecond STW pauses

```bash
# JDK 21 — generational is opt-in
-XX:+UseZGC -XX:+ZGenerational

# JDK 23+ — generational is the default; flag is a no-op and is being removed
-XX:+UseZGC
```

**Key generational-ZGC tuning flags (JDK 25):**
| Flag | Purpose |
|------|---------|
| `-XX:SoftMaxHeapSize` | Soft upper bound the collector tries to stay under |
| `-XX:ZUncommitDelay` | Seconds before unused memory is returned to the OS |
| `-XX:ZCollectionIntervalMinor` | Force minor cycle cadence (rarely needed) |
| `-XX:ZAllocationSpikeTolerance` | Multiplier for heuristic allocation-rate response |

### When to Choose G1 vs ZGC vs Shenandoah (2026)

A three-way decision — not two — now that Generational Shenandoah is a product feature.

| Criterion | G1 | Generational ZGC | Generational Shenandoah |
|-----------|-----|------------------|-------------------------|
| Typical average pause | 10-50ms | <1ms | 1-10ms |
| Typical p99 pause | 100-200ms | ~1ms | ~10-20ms |
| Sweet-spot heap size | 1-32GB | 32GB – multi-TB | 2-128GB |
| Throughput | Highest (simpler barriers) | ~3-5% lower | ~2-4% lower |
| Memory headroom required | ~10% reserve | ~25% (plus multi-mapping) | ~10-15% |
| Compressed oops support | Yes | No (uses full 64-bit colored pointers) | Yes |
| Ships in Oracle JDK | Yes | Yes | No (Red Hat/Temurin) |
| JDK status | Default since 9 | Default ZGC mode since 23 | Generational production in 25 |

**Choose G1** when: throughput matters more than latency, heap < 32GB, 100-200ms pauses are
acceptable (typical web services), or you need maximum ecosystem compatibility.

**Choose Generational ZGC** when: you need <1ms p99 pauses, heap ≥ 32GB, you can afford
~25% heap headroom, and you do not need compressed oops (object density matters less to you
than consistent latency).

**Choose Generational Shenandoah** when: you want ZGC-class latency on mid-size heaps
(2-128GB) with compressed oops, you face bursty allocation spikes, or you ship on a
Temurin/Red Hat distribution. Good fit for containerized services with heap budgets
under 64GB.

---

## 5. Shenandoah

Shenandoah is a **concurrent, low-pause** collector developed by Red Hat. Like ZGC, it aims
for sub-millisecond pauses, but uses a different mechanism.

### Brooks Pointers

Every object in Shenandoah has an **extra word in the object header** — a forwarding pointer
(also called a Brooks pointer) that initially points to the object itself:

```
Normal state:
┌──────────────┬─────────────────────┐
│ Brooks ptr ──┼──► (self)           │
│ [mark word]  │ [class ptr] [fields]│
└──────────────┴─────────────────────┘

After relocation:
┌──────────────┬─────────────────────┐     ┌─────────────────────┐
│ Brooks ptr ──┼─────────────────────┼──►  │ Object (new copy)   │
│ [mark word]  │ [class ptr] [fields]│     │ Brooks ptr → (self) │
└──────────────┴─────────────────────┘     └─────────────────────┘
      old copy (forwarded)                       new copy
```

Every access to an object goes through the Brooks pointer. When an object is relocated, only
the forwarding pointer in the old copy needs to be updated.

### Concurrent Evacuation

Shenandoah can evacuate (move) objects concurrently using a CAS on the Brooks pointer:
1. GC thread copies the object to a new location
2. CAS the old Brooks pointer from self to new location
3. If CAS fails, another thread (GC or mutator) already relocated it — one copy wins

### Generational Shenandoah (JEP 404 → JEP 521)

Shenandoah followed ZGC into a generational model. The timeline:
- **JEP 404** (JDK 24) shipped Generational Shenandoah as experimental
- **JEP 521** (JDK 25) promoted it to a production feature

Enable: `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational`
(single-generation mode is still the default; you opt into generational explicitly.)

Why it matters in interviews: Shenandoah's generational mode targets the same workload class
as generational ZGC but keeps Shenandoah's advantages — compressed oops support, smaller
memory headroom, and workable performance on mid-size heaps where ZGC's fixed metadata
budget feels heavy.

### Comparison with ZGC

| Aspect | Generational ZGC | Generational Shenandoah |
|--------|------------------|-------------------------|
| Reference interception | Load barrier + colored pointers | Brooks pointer + load/store barriers |
| Memory overhead | Multi-mapped virtual memory, ~25% heap headroom | Extra word per object (~4-8 bytes); compressed oops supported |
| Heap size support | Multi-terabyte (tested up to 16TB) | Practical up to ~hundreds of GB |
| JDK distribution | All OpenJDK builds (including Oracle JDK) | Not in Oracle JDK; ships in Red Hat/Adoptium/Temurin |
| Generational | Default since JDK 23 | Production since JDK 25 (opt-in) |
| Typical p99 pause | ~0.1-1ms | ~1-10ms |
| Allocation spike resilience | Good; relies on concurrent relocation | Excellent; concurrent evacuation handles bursts well |

---

## 6. GC Tuning

### Key JVM Flags

| Flag | Description | Typical Value |
|------|-------------|---------------|
| `-Xms` | Initial heap size | Same as `-Xmx` for predictability |
| `-Xmx` | Maximum heap size | Based on available RAM (50-75%) |
| `-Xmn` | Young generation size (Serial/Parallel) | 1/3 to 1/4 of heap |
| `-XX:NewRatio` | Old/Young ratio (default 2 → 1/3 young) | 2-4 |
| `-XX:SurvivorRatio` | Eden/Survivor ratio (default 8 → Eden=80%) | 6-10 |
| `-XX:MaxGCPauseMillis` | Target max pause (G1, ZGC) | 50-200 |
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
│   └── Use Generational ZGC (-XX:+UseZGC)  // generational is default in JDK 23+
│       └── Minimal tuning needed (self-adaptive)
│       └── Plan for ~25% heap headroom above live set
│
├── Low latency on mid-size heap (2-128GB) with compressed oops, Temurin/Red Hat
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
| **JEP 509** | JFR CPU-Time Profiling (Experimental, Linux) | Uses `SIGPROF` via `CPU-time` perf events, measures actual CPU time (not wall-clock), sees native frames |
| **JEP 518** | JFR Cooperative Sampling | Reworks the sampler so stack walks happen from a safepoint **but without safepoint bias**; concurrent with ZGC, much more scalable |
| **JEP 520** | JFR Method Timing & Tracing | Bytecode-instrumentation-based exact timing for specific methods (no sampling — every call counted) |

The net effect: JFR in 2026 is no longer a "black box" that sample-biases toward idle methods.
It competes directly with async-profiler for accuracy while retaining its
structured-event/low-overhead advantages.

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

**History caveat (important in 2026):** the experimental AOT and JIT compiler (including the
in-tree Graal JIT) was removed from OpenJDK in JDK 17 via JEP 410 ("Remove the Experimental
AOT and JIT Compiler"). The in-JDK `-XX:+UseJVMCICompiler` flag still exists
but is hollow on recent builds. If you want Graal as a JIT, run GraalVM itself (Oracle GraalVM
or community "Mandrel" builds for Native Image) — otherwise you're on HotSpot C1/C2.

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
| **JEP 483** | JDK 24 (2025-03) | Ahead-of-Time Class Loading & Linking — ~40% faster startup |
| **JEP 514** | JDK 25 (2025-09) | AOT Command-Line Ergonomics — one-step cache creation |
| **JEP 515** | JDK 25 (2025-09) | AOT Method Profiling — JIT starts compiling hot methods at boot |
| **JEP 516** | JDK 26 (targeted 2026-09) | GC-agnostic AOT cache (unlocks ZGC) |

**Workflow (JDK 25, simplified by JEP 514):**

```bash
# 1. Training run — record classes loaded + method profiles under representative load
java -XX:AOTCacheOutput=app.aot -jar app.jar run-representative-load

# 2. Production run — consumes cache, skips class loading/linking and primes the JIT
java -XX:AOTCache=app.aot -jar app.jar
```

Reported benefits on Spring PetClinic with JDK 25: **~40-45% faster startup**, ~21k classes
pre-linked at boot, and measurable warm-up reduction because hot methods already have
profile data available to C2 on the first invocation. Unlike Native Image, the JVM keeps full
dynamic capabilities (reflection, dynamic class loading, JVMTI).

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
- **Deprecated for removal** since JDK 18 (JEP 421); from JDK 21 you can disable finalization entirely with `--finalization=disabled`

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

## 12. Heap vs Off-Heap Memory

### Direct ByteBuffer

```java
// Allocate off-heap memory
ByteBuffer direct = ByteBuffer.allocateDirect(1024 * 1024);  // 1 MB off-heap

// Use like a normal ByteBuffer
direct.putInt(42);
direct.putDouble(3.14);
direct.flip();
int value = direct.getInt();
```

**When to use:**
- I/O-heavy applications (avoids copy between Java heap and OS buffers)
- Channel-based NIO operations (zero-copy when possible)
- Large buffers that would pressure GC

**Deallocation gotcha**: Direct buffers are freed when their `ByteBuffer` wrapper is GC'd,
which triggers a `Cleaner`. But GC may not run if heap pressure is low — leading to
native OOM even though Java heap has plenty of space. Track with `-XX:MaxDirectMemorySize`.

```java
// Force deallocation (internal API, use with caution)
import sun.misc.Unsafe;

// Safer approach: use explicit deallocation with try-with-resources
// or trigger GC: System.gc() (not guaranteed but hints the JVM)
```

### Memory-Mapped Files

```java
// Map a file into memory — OS handles paging
try (FileChannel channel = FileChannel.open(path, READ, WRITE)) {
    MappedByteBuffer mapped = channel.map(
        FileChannel.MapMode.READ_WRITE, 0, channel.size());

    // Access file contents as memory — OS pages in/out transparently
    int header = mapped.getInt(0);
    mapped.putInt(0, header + 1);

    mapped.force();  // Flush to disk (like fsync)
}
```

**Use cases:**
- Large file processing (files larger than heap)
- Inter-process communication (shared memory via mapped file)
- Database implementations (memory-mapped storage)
- Read-mostly workloads with OS page cache benefits

### MemorySegment (Foreign Function & Memory API)

Since JDK 22 (finalized), the FFM API provides safe, deterministic off-heap memory management:

```java
import java.lang.foreign.*;

// Deterministic allocation and deallocation
try (Arena arena = Arena.ofConfined()) {
    // Allocate off-heap
    MemorySegment segment = arena.allocate(1024);
    segment.set(ValueLayout.JAVA_INT, 0, 42);
    int value = segment.get(ValueLayout.JAVA_INT, 0);

    // Allocate a struct-like layout
    MemoryLayout pointLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE.withName("x"),
        ValueLayout.JAVA_DOUBLE.withName("y")
    );
    MemorySegment point = arena.allocate(pointLayout);
}  // All memory freed deterministically here
```

Advantages over `Unsafe`:
- Bounds-checked access (no segfaults)
- Deterministic deallocation via `Arena`
- Thread confinement safety
- Official public API (not internal)

### When Off-Heap Makes Sense

| Scenario | Why Off-Heap? |
|----------|---------------|
| Large caches (multi-GB) | Avoids GC scanning overhead |
| Serialization buffers | Avoids double-copying (heap → native → I/O) |
| IPC / shared memory | Memory-mapped files for cross-process communication |
| Native library interop | FFM API for calling C libraries |
| Real-time systems | Predictable latency, no GC interference |

**Warning**: Off-heap memory is harder to debug. No heap dump visibility, no automatic leak
detection. Use it only when you have clear evidence that GC pressure is the bottleneck.

---

## 13. Project Valhalla — Value Objects and Null-Restricted Types

Project Valhalla is the long-running OpenJDK effort to add **value objects** to Java.
Status in April 2026: value classes and null-restricted types are in **preview** via
early-access builds; production landing is expected in a future LTS (JDK 27 is the most-likely
target). Interview-relevance is high because it touches directly on memory layout, GC, and
performance — all the material preceding sections cover.

### The Problem Valhalla Solves

Today every `Integer`, `LocalDate`, `Point`, `Pair<K,V>` is an **identity object**: it has
a header, lives on the heap, is referenced by pointer, and can be `null`, `synchronized`-on,
and compared with `==`. That's expensive for tiny objects:

```
Integer[1_000_000]            // array of Integer references
Memory layout today:
  [array header][ptr][ptr][ptr]...     // 4 MB of pointers
  + 16 bytes per Integer (12 header + 4 int + padding) × 1M
  = ~20 MB, with ~1M pointer hops, ~1M cache misses
```

An `int[1_000_000]` by contrast is a flat 4 MB block with zero indirection.

### Value Classes

A **value class** has no identity — two instances with the same field values are
indistinguishable. The JVM is free to flatten them into containing structures, skip the
header, and pass them in registers.

```java
// Preview syntax (Java 24/25 preview)
public value class Point {
    double x;
    double y;
}

Point[] points = new Point[1_000_000];  // Flat array: 16 MB, no indirection
```

Key guarantees:
- No identity — `==` is equivalent to field equality
- No mutable state (all fields implicitly final)
- No `synchronized` on instances
- Can be flattened into arrays, fields of other classes, and local variables

### Null-Restricted Types

To make value-object flattening safe, Valhalla introduces **null-restricted** types
(exclamation mark) and **nullable** types (question mark):

```java
Point!    p1 = new Point(1.0, 2.0);    // Non-null — JVM flattens
Point?    p2 = null;                    // Nullable — reference layout
```

The `!` form lets the compiler and JVM omit null checks and flatten aggressively. The type
system catches `null` escapes at compile time, eliminating a class of NPEs.

### Performance Implications (When It Lands)

- **Generic collections without boxing**: `List<Integer!>` can use a flat `int[]` under the
  hood; no more `Integer` autobox pressure on GC.
- **Removal of many primitive-specialized APIs**: Eclipse Collections `IntArrayList`,
  fastutil `Int2IntOpenHashMap`, etc., become less necessary.
- **Improved cache density**: Flat arrays of small value classes fit in far fewer cache lines.
- **Reduced GC work**: Value objects do not live on the heap as separate allocations, so they
  do not participate in marking/sweeping/relocation.

### Interview-Ready Summary

"Valhalla brings value objects — identity-less, flattenable, null-restricted — to Java.
Value classes skip the object header, eliminate pointer indirection in arrays, and let the
JIT pass them in registers. Null-restricted types (`Point!`) enforce non-null at compile time,
making flattening safe. Expected production release in a future LTS (likely JDK 27).
Combined with Compact Object Headers (JEP 519) and generational ZGC/Shenandoah, Valhalla is
the biggest memory-layout change to the JVM since generics — it should eliminate most
primitive-vs-object trade-offs in hot code."

---

## 14. Common Senior Interview Questions

**Q1: Your production service is experiencing long GC pauses (>500ms). Walk through your
troubleshooting process.**

A: First, enable GC logging if not already active (`-Xlog:gc*`). Check which GC phase is causing the
pause — is it young GC, mixed GC, or full GC? If it is full GC, the concurrent marking cycle is not
keeping up, and I would check IHOP settings, increase heap size, or look for a memory leak. If it is
mixed GC, old gen has too many regions to collect — spread over more cycles
(`-XX:G1MixedGCCountTarget`) or reduce liveness threshold. I would also check for humongous
allocations (objects > region_size/2) in the logs and increase `-XX:G1HeapRegionSize` if needed.
For systematic analysis, I would capture a JFR recording to correlate GC pauses with allocation
rate and identify allocation hotspots. If the requirement is <10ms pauses, I would switch to ZGC.

**Q2: Explain how ZGC achieves sub-millisecond pauses regardless of heap size.**

A: ZGC uses colored pointers — metadata bits embedded in the reference pointer itself — combined
with load barriers injected at every reference load. This allows ZGC to perform marking and
relocation fully concurrently. The only stop-the-world phases are root scanning (thread stacks, JNI
references) which takes O(1) time relative to heap size and live set size. During concurrent
relocation, objects are moved while the application runs. If a thread accesses a relocated object,
the load barrier detects the stale pointer via the color bits, consults a forwarding table, and
self-heals the reference in place. Multi-mapping maps the same physical memory at different virtual
addresses for each color, so any colored pointer dereferences to the correct object.

**Q3: What is escape analysis and what optimizations does it enable?**

A: Escape analysis determines whether an object allocated inside a method "escapes" — either by
being returned, stored in a field, or passed to another method that retains it. If an object does not
escape, the JIT can apply: (1) **Scalar replacement** — decompose the object into its primitive
fields as local variables, eliminating the heap allocation entirely. (2) **Lock elision** — remove
synchronization on non-escaping objects since no other thread can access them.
(3) **Stack allocation** — in theory, allocate on the stack instead of heap, though HotSpot
primarily uses scalar replacement instead. Escape analysis is defeated by megamorphic call sites,
very large methods, or objects passed to un-inlinable methods.

**Q4: Your application is leaking memory — heap usage grows linearly until OOM. How do you
diagnose and fix it?**

A: First, enable `-XX:+HeapDumpOnOutOfMemoryError` if not set. Take a heap dump with
`jcmd <pid> GC.heap_dump` while the leak is in progress (before OOM). Open the dump in Eclipse MAT
and run the Leak Suspects report. The dominator tree shows which objects retain the most memory.
Common causes: static collections that grow without bounds (fix: add eviction or use bounded cache),
`ThreadLocal` values not cleaned up in thread pools (fix: always call `remove()` in finally blocks),
listener registrations without corresponding deregistration, classloader leaks during redeployment.
For real-time monitoring, use `jstat -gcutil` to watch old gen fill rate and correlate with
application behavior. I would also check for off-heap leaks (native memory) using
`-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory`.

**Q5: Explain the difference between G1's remembered sets and card tables. Why does G1 need
a different approach?**

A: Traditional card tables (used by Serial/Parallel collectors) are a single global byte array where
each byte covers a 512-byte heap region. When an old-gen reference is updated, the corresponding
card is dirtied. During young GC, dirty cards are scanned for cross-generational pointers. This works
well for a two-generation model. G1 needs a different approach because it collects arbitrary
**subsets of regions** (not just "young" vs "old"). G1's remembered sets are **per-region** data
structures that track which other regions contain pointers into a given region. This allows G1 to
collect any arbitrary set of regions: for each region in the collection set, its RSet identifies
exactly which external regions contain references into it, so only those cards need scanning.
The trade-off is memory overhead — RSets can consume 5-20% of heap.

**Q6: What causes "safepoint bias" in Java profiling, and how do you avoid it?**

A: Most JVM-based profilers (VisualVM, `jstack`-based tools) collect thread stack traces at
safepoints — points where the JVM knows the exact state of all threads. The problem is that
safepoints only occur at specific locations: method returns, loop back-edges (but not counted
`int` loops), allocation points. If a thread spends most of its time in a tight loop without
safepoints, the profiler will never see it there. Instead, it will over-report time spent at
safepoint locations, creating a distorted view. The fix is to use **async-profiler**, which uses
`AsyncGetCallTrace` to sample threads at arbitrary points (not just safepoints), giving accurate
results. JFR's method sampling events also have improved (though not fully immune) sampling.

**Q7: When would you choose off-heap memory over increasing the heap size?**

A: Off-heap memory makes sense when: (1) You have multi-gigabyte caches that are long-lived — these
would create GC scanning overhead in old gen even though they rarely change. (2) You are doing heavy
I/O and want to avoid the copy between Java heap buffers and native buffers that NIO channel
operations require. (3) You need deterministic memory control for latency-sensitive workloads where
GC pauses are unacceptable. (4) Shared memory for inter-process communication via memory-mapped
files. However, off-heap memory loses many Java benefits: no automatic garbage collection, no heap
dump visibility, harder to debug leaks, bounds-checking only with the newer FFM API. I would always
profile first and only move to off-heap when GC pressure is a proven bottleneck.

**Q8: Explain tiered compilation and how the JIT decides to optimize a method.**

A: HotSpot uses tiered compilation with five tiers. Tier 0 is the interpreter — all methods start
here. Tier 1-3 use the C1 compiler with varying levels of profiling instrumentation. Tier 4 uses
the C2 compiler with aggressive optimizations. The typical path is: interpreter collects basic
profiles, C1 compiles at Tier 3 with full profiling (branch frequencies, type profiles, call
counts), then when the invocation threshold is exceeded, C2 compiles at Tier 4 using the rich
profiling data. C2 applies speculative optimizations — devirtualization based on observed types,
branch prediction, inlining based on call frequency. If a speculative assumption is violated at
runtime (e.g., a new subclass is loaded), the JVM performs deoptimization: discards the compiled
code, transfers execution back to the interpreter at the exact point, and recompiles with updated
profile data.

**Q9: A colleague suggests adding object pooling to improve GC performance. When is this
appropriate and when is it harmful?**

A: Object pooling was common in early Java (pre-2000s) when GC was primitive and allocation was
expensive. Modern generational GCs (especially G1 and ZGC) are optimized for short-lived objects —
young generation collection is very fast when most objects are dead. Pooling is **appropriate** for
objects that are genuinely expensive to create: database connections, SSL contexts, thread pools,
large byte buffers. Pooling is **harmful** when: objects are cheap to create (most POJOs), the pool
itself becomes a contention point (threads blocking on pool access), pooled objects grow stale and
accumulate state bugs (not properly reset between uses), or the pool keeps objects alive in old gen
that would otherwise be quickly collected in young gen. Always profile before pooling — premature
pooling often makes performance worse, not better.

**Q10: How does Generational ZGC differ from non-generational ZGC, and why was it introduced?**

A: Non-generational ZGC treats the entire heap uniformly — every GC cycle marks and potentially
relocates across the full heap. This means short-lived objects (which are the majority) still
contribute to marking work and relocation overhead. Generational ZGC (JEP 439) was previewed in
JDK 21 and became the default ZGC mode in JDK 23; it splits the heap into young and old
generations. Young gen is collected frequently with minimal overhead (most objects are dead),
while old gen is collected independently and less often. This required adding **store barriers**
(in addition to load barriers) to track cross-generational references via region-level remembered
sets. The result is significant: 50-70% less CPU overhead for GC, better handling of high
allocation rates, and ~25% less heap headroom required. The generational approach is now the
default because the generational hypothesis — most objects die young — is so universally true
that exploiting it is almost always beneficial. The non-generational mode is being removed in
upcoming releases.

**Q11: In JDK 25, how would you pick between G1, Generational ZGC, and Generational Shenandoah
for a new service?**

A: Default choice for most services with 1-32GB heaps and p99 latency SLAs above ~50ms is G1 —
it has the best throughput, smallest memory headroom, and the richest tooling. For strict
latency SLAs (p99 < 1ms) on larger heaps (32GB+), or anywhere pause-time consistency is
business-critical (trading, ad-serving, gaming matchmaking), I'd use Generational ZGC — now
the default ZGC mode since JDK 23 via JEP 439. I'd plan for ~25% heap headroom above the live
set and not rely on compressed oops. Generational Shenandoah (product feature in JDK 25 via
JEP 521) is the choice when I want ZGC-class latency on mid-size heaps (2-128GB) with
compressed oops, for bursty-allocation workloads, or when shipping on Temurin/Red Hat
distributions. Shenandoah is not in Oracle JDK. In all three cases I'd enable
`-XX:+UseStringDeduplication` if the heap has a high share of duplicate Strings and
`-XX:+UseCompactObjectHeaders` (JEP 519, product in JDK 25) to shrink headers from 12 to 8
bytes for object-heavy apps.

**Q12: What does Project Leyden bring that CDS/AppCDS did not, and how do you use it in JDK 25?**

A: CDS and AppCDS share read-only class metadata across JVM processes to reduce startup cost
and memory. Project Leyden goes further: JEP 483 (JDK 24) added **AOT class loading and
linking** — classes are pre-linked (verified, resolved, constant-pool-patched) in the cache,
not just loaded. JEP 515 (JDK 25) added **AOT method profiling**, recording which methods the
JIT should compile hot on next startup. JEP 514 (JDK 25) collapses the old three-step workflow
(record / assemble / run) into a one-step training run: `java -XX:AOTCacheOutput=app.aot
-jar app.jar run-load` and then deploy with `java -XX:AOTCache=app.aot -jar app.jar`. Reported
gains on Spring PetClinic: ~40-45% faster startup and meaningfully reduced warm-up, because
the JIT has profile data immediately. Unlike GraalVM Native Image, Leyden keeps full dynamic
JVM capabilities (reflection, JVMTI, dynamic class loading). JEP 516 in JDK 26 (targeted 2026)
makes the AOT cache GC-agnostic, which is what unlocks it for ZGC.

**Q13: Your monitoring platform wants per-endpoint latency with per-GC-pause correlation.
How would you build it?**

A: I'd use **JFR event streaming** (`jdk.jfr.consumer.RecordingStream`, JEP 349) in-process
to subscribe to `jdk.GCPhasePause`, `jdk.ObjectAllocationInNewTLAB`, and custom `Event`
subclasses for my endpoints. The custom event records `endpoint`, `httpStatus`, `duration`,
and a correlation id. I enable `-XX:StartFlightRecording=disk=true,maxsize=4g,maxage=24h` as
a continuous ring buffer so an operator can always dump the last 24h with
`jcmd <pid> JFR.dump`. A tiny agent thread consumes the live stream, serialises events to
OTLP, and ships to a collector (Grafana Tempo / Datadog / Pyroscope). In JDK 25 I also enable
JEP 509 CPU-time profiling (Linux) for accurate CPU attribution that sees native frames, and
I rely on JEP 518 cooperative sampling which removes the safepoint bias that made older JFR
CPU profiles unreliable. For ad-hoc deep dives I still reach for **async-profiler** to get
flame graphs with hardware-event sampling (`cycles:p`) — JFR and async-profiler are
complementary, not competing.

**Q14: What are Compact Object Headers and when would you enable them?**

A: Compact Object Headers (JEP 450 experimental in JDK 24, JEP 519 product feature in JDK 25)
shrink the object header on 64-bit HotSpot from 12 bytes to **8 bytes** by packing the
compressed Klass pointer directly into the mark word. Enable with
`-XX:+UseCompactObjectHeaders`. Benchmarks show ~10-20% reduction in live heap for
object-heavy workloads, up to 22% on SPECjbb2015, and ~8% CPU improvement from better cache
density and lower GC pressure. I'd enable it after validating that my application doesn't
break on the feature (it reshapes the mark word so some low-level tooling or `sun.misc.Unsafe`
hackery might behave differently), and it pairs especially well with G1 and generational
Shenandoah which benefit most from reduced live-set size. One caveat: the compressed Klass
pointer encoding caps loaded classes at ~4 million — irrelevant in practice but worth knowing.

**Q15: How is finalize() being replaced, and what's the migration path for legacy code that
still uses it?**

A: `Object.finalize()` is deprecated for removal (JEP 421 in JDK 18 deprecated finalization
itself). On JDK 21+ you can run with `--finalization=disabled` to prove an app doesn't rely
on it. The replacement is **`java.lang.ref.Cleaner`** for native resource cleanup combined
with `AutoCloseable` + try-with-resources for deterministic cleanup. Migration path:
(1) Add `implements AutoCloseable` and an explicit `close()` method, (2) register a `Cleaner`
as a safety net that runs the same cleanup if the caller forgets to close, (3) ensure the
`Cleanable.Runnable` captures only native handles and primitive state — never a reference to
the enclosing object (otherwise the cleaner itself keeps the object alive and nothing ever
cleans). For off-heap memory specifically, prefer the **FFM API `Arena`** over
`ByteBuffer.allocateDirect` — `Arena.ofConfined()` gives deterministic release via
try-with-resources and doesn't depend on the Cleaner running.
