# ZGC and Shenandoah

The two low-pause HotSpot collectors most likely to come up in modern senior-level interviews.

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

### Generational ZGC (JEP 439 in JDK 21; default in JDK 23; non-generational mode removed in JDK 24)

Before JDK 21, ZGC was **non-generational** — it treated the entire heap uniformly. This was
suboptimal because:
- Short-lived objects pollute the marking and relocation work
- Allocation rate sensitivity: non-generational ZGC struggles if objects die quickly
  (frequent cycles needed)

**Generational ZGC** shipped via JEP 439 in JDK 21, became the default mode via JEP 474 in
JDK 23, and the non-generational mode was removed via JEP 490 in JDK 24:
- Separate young and old generations, each with their own collection cycle
- Young gen collected more frequently (fast, most objects dead)
- Old gen collected less often (more work, but fewer cycles)
- Uses **store barriers** (in addition to load barriers) to track cross-generational references —
  dirties a region-level remembered set rather than a card table
- Typically reduces GC overhead on workloads with many short-lived objects, but the exact gains
  are workload-dependent
- Each generation still uses colored pointers and forwarding tables, so both phases remain
  fully concurrent with sub-millisecond STW pauses

```bash
# JDK 21 — generational is opt-in
-XX:+UseZGC -XX:+ZGenerational

# JDK 24+ — `UseZGC` selects the only remaining ZGC mode
-XX:+UseZGC
```

**Key generational-ZGC tuning flags (JDK 25):**
| Flag | Purpose |
|------|---------|
| `-XX:SoftMaxHeapSize` | Soft upper bound the collector tries to stay under |
| `-XX:ZUncommitDelay` | Seconds before unused memory is returned to the OS |
| `-XX:ZCollectionInterval` | Force a maximum interval between GC cycles |
| `-XX:ZAllocationSpikeTolerance` | Multiplier for heuristic allocation-rate response |

### When to Choose G1 vs ZGC vs Shenandoah (2026)

A three-way decision — not two — now that Generational Shenandoah is a product feature.

| Criterion | G1 | Generational ZGC | Generational Shenandoah |
|-----------|-----|------------------|-------------------------|
| Main trade-off | Best general balance | Best latency | Low-latency alternative where available |
| Typical fit | Most services | Strict pause-time SLOs | Low-pause services on Shenandoah-enabled builds |
| Ships in Oracle JDK | Yes | Yes | No (Red Hat/Temurin) |
| JDK status | Default since 9 | Default ZGC mode since 23; generational-only since 24 | Generational production in 25 |

**Choose G1** when: throughput matters more than latency, heap < 32GB, 100-200ms pauses are
acceptable (typical web services), or you need maximum ecosystem compatibility.

**Choose Generational ZGC** when: you need very low pause times and you are optimizing for
latency consistency over absolute throughput.

**Choose Generational Shenandoah** when: you want ZGC-class latency on mid-size heaps
and you ship on a Shenandoah-enabled distribution such as Red Hat or Temurin.

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
as generational ZGC, but it stays a separate operational choice: different barriers,
different availability by JDK distribution, and a different tuning/playbook surface.

### Comparison with ZGC

| Aspect | Generational ZGC | Generational Shenandoah |
|--------|------------------|-------------------------|
| Reference interception | Load barrier + colored pointers | Brooks pointer + load/store barriers |
| Primary design goal | Lowest pauses at scale | Low pauses with a different relocation strategy |
| JDK distribution | All OpenJDK builds (including Oracle JDK) | Not in Oracle JDK; ships in Red Hat/Adoptium/Temurin |
| Generational | Default since JDK 23; only mode since JDK 24 | Product feature since JDK 25 (still opt-in) |

---
