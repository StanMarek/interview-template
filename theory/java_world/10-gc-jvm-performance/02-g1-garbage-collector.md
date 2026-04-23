# G1 Garbage Collector

How HotSpot's default collector works, where its pause-time model comes from, and which details are
still worth knowing in interviews.

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

### Region Pinning (JEP 423, JDK 22)

Before JDK 22, G1 had to **disable the entire GC** while any Java thread was inside a JNI
critical region (`GetPrimitiveArrayCritical`). A long-running critical region could stall
every allocator thread and produce fake OOMs ("GCLocker deadlock"). JEP 423 fixes this by
**pinning only the regions that contain critical objects** — G1 maintains a per-region
critical-object counter and skips pinned regions during evacuation while collecting everything
else normally.

This removes a long-standing latency cliff for code that calls into native libraries
(JNI, SciMark-style numeric kernels, OpenCV, OpenSSL) and is transparent to application code.

### String Deduplication

`-XX:+UseStringDeduplication` finds Strings whose `char[]`/`byte[]` backing array has the same
content and rewrites one instance to share the other's backing array. Collector support has
expanded over time, so if the exact matrix matters for a specific runtime, verify it on that JDK
instead of memorizing an old table. Studies at Oracle and Netflix showed
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
