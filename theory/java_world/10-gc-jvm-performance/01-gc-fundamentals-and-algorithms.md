# GC Fundamentals and Core Algorithms

Foundational GC concepts and the classic algorithms that interviewers still expect you to explain.

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
