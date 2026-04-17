# V8 Engine & Performance — Senior Engineer Interview Preparation

---

## 1. V8 Compilation Pipeline

### Overview

V8 is Google's open-source JavaScript engine written in C++. It powers Chrome, Node.js, Deno,
and Cloudflare Workers. Unlike early JS engines that used simple interpretation, V8 employs a
multi-tier compilation strategy that progressively optimizes hot code paths while keeping startup
fast for cold code.

### The Pipeline: Source to Machine Code

```
                    V8 Compilation Pipeline (4-tier, modern V8)

 Source ──► Parse ──► AST ──► Ignition (interpreter) ──► Bytecode
                                    │
                                    ▼  (hot on first run)
                              Sparkplug (non-optimizing baseline JIT, V8 9.1+)
                                    │
                                    ▼  (~300–500 invocations)
                              Maglev   (mid-tier optimizer, default in
                                        Node 22 / V8 11.9+)
                                    │
                                    ▼  (~10,000 invocations, or sooner via OSR)
                              TurboFan (top-tier optimizer)
                                    │
                           Type assumption violated
                                    ▼
                              Deoptimize → back down the tiers
```

**Tier-up thresholds (approximate):**
- **Sparkplug**: compiles immediately on first run for hot functions (no tier-up threshold — it is a fast baseline JIT producing unoptimized machine code from bytecode).
- **Maglev**: ~300–500 invocations. Produces good machine code without TurboFan's full analysis cost.
- **TurboFan**: ~10,000 invocations (or sooner via OSR — On-Stack Replacement inside hot loops).

### Ignition: The Bytecode Interpreter

Ignition is V8's register-based bytecode interpreter. Every JavaScript function is first compiled
to bytecode — never to machine code directly. This gives V8 a fast startup path (bytecode
generation is cheaper than full compilation) and provides the foundation for collecting runtime
type information.

**Bytecode characteristics:**
- Register-based VM (not stack-based like the JVM) — fewer instructions, less shuffling
- Compact bytecode format — reduces memory footprint vs. raw AST
- Each bytecode handler is itself a small piece of machine code (generated at build time)
- Accumulator register used for most operations to minimize register addressing overhead

**Example — viewing V8 bytecode:**

```bash
# Run Node.js with bytecode printing enabled
node --print-bytecode --print-bytecode-filter=add script.js
```

```javascript
function add(a, b) {
  return a + b;
}

add(1, 2);  // first call — interpreted via Ignition
```

```
Bytecode for function add:
Parameter count 3
Register count 0
   0 : Ldar a1          ; Load argument 'b' into accumulator
   2 : Add a0, [0]      ; Add argument 'a' to accumulator, feedback slot [0]
   5 : Return            ; Return accumulator value
```

The `[0]` in `Add a0, [0]` is a **feedback vector slot** — this is where V8 records what types
have been seen at this operation. After enough executions with consistent types, TurboFan can
use this information to generate specialized machine code.

### TurboFan: The Optimizing Compiler

TurboFan is V8's optimizing compiler. It takes bytecode + type feedback and produces highly
optimized machine code. The compilation happens on a background thread to avoid blocking
the main thread.

**Key optimization techniques:**

| Technique | Description |
|-----------|-------------|
| **Speculative optimization** | Assumes types based on feedback; generates fast-path code with guards |
| **Inlining** | Replaces function calls with the function body to eliminate call overhead |
| **Escape analysis** | Determines if an object can be stack-allocated or decomposed into scalars |
| **Loop-invariant code motion** | Hoists computations that don't change across loop iterations |
| **Dead code elimination** | Removes code that doesn't affect the program output |
| **Constant folding** | Evaluates constant expressions at compile time |
| **Range analysis** | Narrows numeric ranges to eliminate overflow checks |
| **Redundancy elimination** | Removes duplicate computations and loads |

**When TurboFan kicks in:**

V8 uses an invocation counter and a backward-branch counter. Hot functions tier up through
Sparkplug → Maglev → TurboFan. Exact thresholds are internal and version-dependent, but roughly:
- **Sparkplug**: immediate baseline JIT on first run for hot functions (no tier-up threshold).
- **Maglev**: ~300–500 invocations.
- **TurboFan**: ~10,000 invocations (or sooner via OSR — on-stack replacement inside hot loops).
- TurboFan and Maglev both compile on background threads.
- When compilation finishes, the next call to the function uses the optimized code.

### Deoptimization (Bailouts)

When the assumptions TurboFan made based on type feedback are violated at runtime, V8 must
**deoptimize** — discard the optimized machine code and fall back to interpreting bytecode.

**Common deoptimization triggers:**

```javascript
// 1. Type change after optimization
function multiply(x, y) {
  return x * y;
}

// Called 10,000 times with numbers — TurboFan optimizes for number * number
for (let i = 0; i < 10000; i++) multiply(i, i);

// Suddenly called with a string — DEOPT!
multiply("hello", 2);  // Bails out, falls back to bytecode
```

```javascript
// 2. Hidden class mismatch
function getX(obj) {
  return obj.x;
}

const a = { x: 1, y: 2 };
const b = { x: 3, y: 4 };
for (let i = 0; i < 10000; i++) getX(i % 2 ? a : b);

// Different shape object — DEOPT if hidden class doesn't match
const c = { y: 5, x: 6 };  // x and y in different order = different hidden class
getX(c);
```

```javascript
// 3. Map deprecation (hidden class transition tree change)
// 4. Out-of-bounds array access after optimization assumed in-bounds
// 5. Prototype chain modification after optimization inlined property access
```

**Detecting deoptimizations:**

```bash
# Print deoptimization reasons
node --trace-deopt script.js

# More verbose: print optimization and deoptimization
node --trace-opt --trace-deopt script.js
```

### Inline Caches (ICs)

Inline caching is V8's mechanism for speeding up property access and function calls by
remembering the shapes of objects seen at each call site.

```
┌──────────────────────────────────────────────────────────┐
│                    IC States                              │
│                                                          │
│  Uninitialized ──► Monomorphic ──► Polymorphic ──► Megamorphic
│  (no info)         (1 shape)       (2-4 shapes)    (5+ shapes)
│                         │                │               │
│                    FASTEST           Still OK         SLOWEST
│                 (direct access)   (linear check)   (hash lookup)
└──────────────────────────────────────────────────────────┘
```

**Monomorphic** (ideal): The property access site always sees objects with the same hidden class.
V8 caches the exact memory offset and performs a single shape check + direct load.

**Polymorphic** (acceptable): 2-4 different hidden classes seen at this site. V8 generates a
linear chain of shape checks — still reasonably fast.

**Megamorphic** (avoid): 5+ different hidden classes. V8 falls back to a generic hash table
lookup — significantly slower. This also prevents TurboFan from optimizing the access.

```javascript
// GOOD: Monomorphic — all objects have the same shape
function getName(user) {
  return user.name;
}

const users = [
  { name: "Alice", age: 30 },
  { name: "Bob", age: 25 },
  { name: "Carol", age: 35 },
];
users.forEach(getName);  // Always same hidden class → monomorphic IC

// BAD: Megamorphic — many different object shapes
function getValue(obj) {
  return obj.value;
}

getValue({ value: 1 });
getValue({ value: 2, extra: true });
getValue({ x: 0, value: 3 });
getValue({ a: 1, b: 2, value: 4 });
getValue({ value: 5, z: null, w: undefined });
// Five+ different shapes → megamorphic → slow generic lookup
```

### Writing V8-Friendly Code

```javascript
// 1. Use consistent object shapes
// BAD
function createUser(name, age, email) {
  const user = {};
  user.name = name;
  if (age) user.age = age;       // Conditional property → different shapes
  if (email) user.email = email;
  return user;
}

// GOOD
function createUser(name, age, email) {
  return {
    name: name,
    age: age ?? null,       // Always present, same shape
    email: email ?? null,
  };
}

// 2. Avoid changing types in variables
// BAD
let result = 0;
result = computeString();  // Now a string — any optimized code using result deopts

// GOOD
const numResult = 0;
const strResult = computeString();

// 3. Use typed arrays for numeric computation
// BAD — V8 must handle Smi, HeapNumber, and potential type changes
const arr = [1.1, 2.2, 3.3, 4.4];

// GOOD — V8 knows it's always Float64
const arr = new Float64Array([1.1, 2.2, 3.3, 4.4]);

// 4. Avoid delete operator (see Section 2)
// BAD
delete obj.unusedProp;  // Transitions object to dictionary mode

// GOOD
obj.unusedProp = undefined;  // Preserves hidden class
```

---

## 2. Hidden Classes (Maps)

### How V8 Tracks Object Shapes

JavaScript objects are dynamic — properties can be added, removed, and changed at any time.
Naively, every property access would require a dictionary lookup. V8 avoids this by assigning
every object a **hidden class** (internally called a **Map**) that describes its shape: which
properties exist, their types, and their memory offsets.

When you access `obj.x`, V8 checks the object's hidden class to find the exact offset of `x`
in memory, turning a dictionary lookup into a direct memory read — similar to how a C struct works.

### Transition Chains

Every time a property is added to an object, V8 creates a new hidden class (or reuses one if it
already exists in the transition tree). Objects that are constructed the same way share hidden
classes.

```
Hidden Class Transition Chain
──────────────────────────────

const point = {};        Map M0: {}
point.x = 10;           Map M1: { x: @offset0 }     M0 ──"x"──► M1
point.y = 20;           Map M2: { x: @offset0,      M1 ──"y"──► M2
                                   y: @offset1 }

const point2 = {};       Uses M0 (same starting shape)
point2.x = 5;            Transitions to M1 (reused!)
point2.y = 15;           Transitions to M2 (reused!)

// point and point2 share the SAME hidden class M2
// This means the inline cache for accessing .x or .y on either works identically
```

```
                    Transition Tree
                    ───────────────
                        M0 {}
                       /       \
                "x"   /         \ "name"
                     ▼           ▼
               M1 {x}        M3 {name}
                  |              |
            "y"   |        "age" |
                  ▼              ▼
            M2 {x,y}      M4 {name,age}
```

### Why Property Order Matters

```javascript
// These two objects have DIFFERENT hidden classes!
const a = { x: 1, y: 2 };  // Map: { x: @0, y: @1 }
const b = { y: 2, x: 1 };  // Map: { y: @0, x: @1 }

// This means a function accessing .x on both will be polymorphic, not monomorphic
function getX(obj) { return obj.x; }
getX(a);  // IC caches Map for 'a'
getX(b);  // Different Map! IC becomes polymorphic
```

**Best practice**: Always initialize object properties in the same order. Use constructor
functions or classes to enforce consistent shapes.

```javascript
// GOOD: Class guarantees consistent property order and shape
class Point {
  constructor(x, y) {
    this.x = x;  // Always added first
    this.y = y;  // Always added second
  }
}

const p1 = new Point(1, 2);
const p2 = new Point(3, 4);
// p1 and p2 share the same hidden class — monomorphic access guaranteed
```

### The `delete` Operator and Dictionary Mode

The `delete` operator is one of the most destructive operations for V8's optimization strategy.
When you delete a property, V8 cannot simply remove it from the hidden class transition chain.
Instead, it transitions the object to **dictionary mode** (also called "slow properties").

```
Fast Properties (in-object)          Dictionary Mode (hash table)
─────────────────────────────         ──────────────────────────────
┌──────────────────────┐              ┌──────────────────────────┐
│  Hidden Class (Map)  │              │  Hidden Class (Map)      │
│  ┌────────┬────────┐ │              │  mode: DICTIONARY        │
│  │ x: @0  │ y: @1  │ │              └──────────┬───────────────┘
│  └────────┴────────┘ │                         │
└──────────────────────┘              ┌──────────▼───────────────┐
                                      │  Hash Table              │
Object in memory:                     │  ┌─────┬───────┬──────┐ │
┌────────┬────────┐                   │  │ key │ value │ attr │ │
│ x: 10  │ y: 20  │                   │  ├─────┼───────┼──────┤ │
└────────┴────────┘                   │  │ "x" │  10   │ ...  │ │
  Direct offset access                │  │ "y" │  20   │ ...  │ │
  (fast — like a struct)              │  └─────┴───────┴──────┘ │
                                      └──────────────────────────┘
                                        Hash lookup per access
                                        (slow — like a Map)
```

```javascript
// Triggers dictionary mode
const user = { name: "Alice", age: 30, role: "admin" };
delete user.role;  // Object transitions to dictionary mode — ALL property access slows down

// Better alternative
user.role = undefined;  // Hidden class preserved, property still exists but is falsy
```

### Fast Properties vs. Slow Properties

V8 uses two storage strategies:

| Strategy | Storage | Access Speed | When Used |
|----------|---------|-------------|-----------|
| **In-object** | Fixed slots in the object itself | Direct offset (fastest) | First ~10 properties |
| **Out-of-object (backing store)** | Separate array pointed to by object | Indexed access (fast) | Properties beyond in-object limit |
| **Dictionary** | Hash table | Hash lookup (slow) | After `delete`, too many dynamic adds |

**Triggers for dictionary mode:**
- Using `delete` on a property
- Adding too many properties dynamically (exceeding a threshold, typically ~1000)
- Non-standard attributes (e.g., `Object.defineProperty` with non-default descriptors)
- Computed property names that aren't compile-time constants

### Inline Caching and Hidden Class Stability

The power of hidden classes comes from their interaction with inline caching. When a property
access site is monomorphic (always sees the same hidden class), V8 can:

1. Check the hidden class pointer (one comparison)
2. Load from the known offset (one memory read)

This is as fast as a C struct field access. But when hidden classes are unstable:

```javascript
// Stable hidden classes — monomorphic IC
function sumPoints(points) {
  let total = 0;
  for (const p of points) {
    total += p.x + p.y;  // IC: monomorphic if all points share Map
  }
  return total;
}

const points = [];
for (let i = 0; i < 10000; i++) {
  points.push({ x: i, y: i * 2 });  // All same shape → same Map
}
sumPoints(points);  // Very fast — IC stays monomorphic

// Unstable hidden classes — megamorphic IC
const mixed = [
  { x: 1, y: 2 },
  { x: 1, y: 2, z: 3 },       // Different Map
  { y: 2, x: 1 },             // Different Map (different order)
  { x: 1, y: 2, w: 4 },      // Different Map
  { x: 1, y: 2, v: 5, u: 6 }, // Different Map
];
sumPoints(mixed);  // Slow — IC becomes megamorphic
```

---

## 3. Garbage Collection (Orinoco)

### Overview

V8's garbage collector, codenamed **Orinoco**, is a generational, incremental, concurrent,
parallel collector. It divides the heap into a **young generation** (for short-lived objects)
and an **old generation** (for long-lived objects), reflecting the generational hypothesis that
most objects die young.

### Heap Layout

```
V8 Heap Structure (modern V8)
─────────────────────────────

┌─────────────────────────────────────────────────────────────────────┐
│                          V8 Heap                                    │
│                                                                     │
│  ┌──────────────────────────────┐  ┌──────────────────────────────┐ │
│  │   Young Generation           │  │   Old Space                  │ │
│  │   ("new space": 2 semi-      │  │   (unified — previously       │ │
│  │    spaces, 1-8 MB default)   │  │    "Old Pointer" + "Old Data" │ │
│  │                              │  │    merged in V8 4.7 / 2016;   │ │
│  │  ┌──────────┐ ┌──────────┐  │  │    "Map Space" merged into   │ │
│  │  │   From   │ │    To    │  │  │    Old Space in V8 11.x)     │ │
│  │  │ [active] │ │ [empty]  │  │  └──────────────────────────────┘ │
│  │  └──────────┘ └──────────┘  │                                   │
│  └──────────────────────────────┘  ┌──────────────────────────────┐ │
│                                    │   Large Object Space         │ │
│  ┌──────────────────────────────┐  │   (objects > kMaxRegularSize)│ │
│  │   Code Space                 │  └──────────────────────────────┘ │
│  │   (compiled code)            │                                   │
│  └──────────────────────────────┘  (Deprecated: Map Space —         │
│                                     merged into Old Space in V8 11) │
└─────────────────────────────────────────────────────────────────────┘
```

> **Historical note**: Older V8 diagrams showed "Old Pointer Space" and "Old Data Space" as separate spaces; they were merged into a single **Old Space** in V8 ~4.7 (2016). "Map Space" (for hidden classes) was merged into Old Space in V8 11.x. Only **Young Generation** (new space, two semi-spaces), **Old Space**, **Large Object Space**, and **Code Space** remain.

### Young Generation: Scavenge (Minor GC)

The young generation uses a **semi-space copying collector** (Cheney's algorithm). Memory is
divided into two equal-sized semi-spaces: "from-space" (active) and "to-space" (empty).

```
Scavenge Algorithm
──────────────────

Step 1: Allocation happens in from-space
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│ FROM-SPACE (active)             │  │ TO-SPACE (empty)                │
│ [A][B][C][ ][ ][ ][ ][ ][ ][ ] │  │ [ ][ ][ ][ ][ ][ ][ ][ ][ ][ ] │
└─────────────────────────────────┘  └─────────────────────────────────┘

Step 2: From-space fills up — trigger scavenge
         B and D are unreachable (garbage)
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│ FROM-SPACE                      │  │ TO-SPACE                        │
│ [A][B][C][D][E][F][G][H][I][J] │  │ [ ][ ][ ][ ][ ][ ][ ][ ][ ][ ] │
└─────────────────────────────────┘  └─────────────────────────────────┘

Step 3: Copy live objects to to-space (compacted)
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│ FROM-SPACE (now garbage)        │  │ TO-SPACE (now active)           │
│ [A][B][C][D][E][F][G][H][I][J] │  │ [A][C][E][F][G][H][I][J][ ][ ] │
└─────────────────────────────────┘  └─────────────────────────────────┘

Step 4: Swap roles — to-space becomes from-space
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│ TO-SPACE (empty, reclaimed)     │  │ FROM-SPACE (active)             │
│ [ ][ ][ ][ ][ ][ ][ ][ ][ ][ ] │  │ [A][C][E][F][G][H][I][J][ ][ ] │
└─────────────────────────────────┘  └─────────────────────────────────┘
```

**Promotion (tenuring)**: Objects that survive two scavenges are promoted to old generation.
This prevents repeatedly copying long-lived objects between semi-spaces.

**Parallel scavenge**: V8 uses multiple threads to perform the copying phase, reducing pause
times. The main thread and helper threads each claim portions of the work.

### Old Generation: Mark-Sweep-Compact (Major GC)

Old generation collection is more expensive because it contains many live objects. V8 uses
three phases:

1. **Mark**: Starting from GC roots, traverse the object graph and mark all reachable objects.
2. **Sweep**: Iterate through memory, adding unmarked (dead) objects to a free list.
3. **Compact** (optional): Move live objects to reduce fragmentation. Only done when
   fragmentation exceeds a threshold.

### Incremental Marking

Performing a full mark phase in one go would cause unacceptable pauses for large heaps. V8
breaks marking into small increments interleaved with application execution:

```
Incremental Marking Timeline
─────────────────────────────

   App    GC     App     GC     App    GC     App    GC     App
  ─────┤█████├──────┤█████├──────┤█████├──────┤█████├──────────
       mark      mark       mark       mark
       1KB       1KB        1KB        1KB

  Total marking work: 4KB spread across multiple small pauses
  vs. one big pause of 4KB marking all at once
```

Each increment does a small amount of marking work (controlled by a time budget), then yields
back to the application. V8 uses a **write barrier** to track reference changes made by the
application between increments (tri-color marking with an incremental update approach).

### Concurrent Marking and Sweeping

V8 performs marking and sweeping on background threads while the main thread continues
executing JavaScript:

```
Concurrent GC Timeline
──────────────────────

Main Thread:     [──── JS execution ────────────────── JS execution ────]
                              │ brief pause │
Background:      [─── concurrent marking ───][── concurrent sweeping ──]

Total main-thread pause: Only for root scanning and finalizing marking
Background threads handle the heavy work
```

**Concurrent marking**: Background threads traverse the object graph. A write barrier on the
main thread ensures that reference changes during concurrent marking are correctly handled.

**Concurrent sweeping**: After marking, background threads scan memory to build free lists.
The main thread can allocate from already-swept pages immediately.

### Write Barriers for Cross-Generation References

When an old-generation object references a young-generation object, V8 must know about it
during a scavenge (minor GC) — otherwise it would miss live objects in young gen. V8 uses
a **store buffer** (also called a remembered set):

```javascript
const parent = { child: null };  // Lives in old generation (long-lived)
const child = { data: 42 };     // Just allocated in young generation

parent.child = child;  // Old-gen → young-gen reference!
// V8's write barrier adds this reference to the store buffer
```

```
Store Buffer (Remembered Set)
─────────────────────────────

Old Generation              Young Generation
┌──────────┐                ┌──────────┐
│  parent   │──────────────►│  child   │
│          │                │          │
└──────────┘                └──────────┘
      │
      ▼
Store Buffer: [ &parent.child, ... ]

During scavenge, scan store buffer entries to find
old-gen → young-gen references (treat them as GC roots)
```

### Idle-Time GC Scheduling

V8 hooks into the embedder's (Chrome/Node.js) idle task scheduler. When the application is
idle (e.g., between animation frames in Chrome, or waiting for I/O in Node.js), V8 performs
GC work that would otherwise cause pauses:

- Incremental marking steps during idle time
- Scavenges if the young generation is nearly full
- Memory compaction during idle periods

### Key V8 GC Flags for Node.js

| Flag | Default | Description |
|------|---------|-------------|
| `--max-old-space-size=<MB>` | auto-scaled (see note) | Maximum old generation size |
| `--max-semi-space-size=<MB>` | 16MB | Size of each semi-space in young gen |
| `--expose-gc` | off | Exposes `global.gc()` for manual GC triggering |
| `--trace-gc` | off | Logs GC events with timing and heap sizes |
| `--trace-gc-verbose` | off | Detailed GC event logging |
| `--gc-interval=<N>` | off | Force GC every N allocations (testing only) |
| `--optimize-for-size` | off | Prioritize memory over speed |

> **`--max-old-space-size` default**: Historically ~1.4 GB on 64-bit. **Node 12+** auto-scales based on available system memory. **Node 22+** reads `/proc/meminfo` on Linux to pick a sensible default. Set explicitly in containers where cgroup memory limits may not be visible to Node.

```bash
# Example: Production Node.js process with 4GB heap and GC logging
node --max-old-space-size=4096 --trace-gc server.js

# Output:
# [44592:0x...]  12345 ms: Scavenge 120.3 (140.0) -> 18.5 (140.0) MB, 1.2 / 0.0 ms ...
# [44592:0x...] 123456 ms: Mark-sweep 980.2 (1024.0) -> 650.3 (1024.0) MB, 45.0 / 0.0 ms ...
```

---

## 4. Memory Management & Leak Detection

### Common Memory Leak Patterns in Node.js

Memory leaks in Node.js occur when objects that are no longer needed remain reachable from
GC roots. The old generation grows continuously until the process crashes with an
"out of memory" error or becomes unresponsive due to constant GC pressure.

**Pattern 1: Global variables (accidental and intentional)**

```javascript
// Accidental global — missing 'const/let/var' in non-strict mode
function processRequest(data) {
  results = transform(data);  // Implicitly global! Never GC'd
  return results;
}

// Intentional but unbounded global cache
const cache = {};
function getUser(id) {
  if (!cache[id]) {
    cache[id] = fetchFromDB(id);  // Cache grows forever
  }
  return cache[id];
}
```

**Pattern 2: Closures capturing large scopes**

```javascript
function createProcessor() {
  const largeBuffer = Buffer.alloc(100 * 1024 * 1024);  // 100MB

  return function process(data) {
    // Only uses 'data', but 'largeBuffer' is captured in the closure scope
    // and will never be GC'd as long as 'process' is reachable
    return data.toString();
  };
}

const processor = createProcessor();  // 100MB held forever

// FIX: Null out the reference when done, or restructure the closure
function createProcessor() {
  let largeBuffer = Buffer.alloc(100 * 1024 * 1024);
  const result = processBuffer(largeBuffer);
  largeBuffer = null;  // Allow GC

  return function process(data) {
    return data.toString() + result;
  };
}
```

**Pattern 3: Event listeners not removed**

```javascript
// LEAK: Adding listeners without removing them
class DataStream {
  constructor(emitter) {
    // Every time DataStream is instantiated, a new listener is added
    // Even if the DataStream instance is dereferenced, the listener
    // keeps the entire DataStream closure alive
    emitter.on("data", (chunk) => {
      this.buffer.push(chunk);  // 'this' prevents GC of DataStream
    });
  }
}

// FIX: Track and remove listeners
class DataStream {
  constructor(emitter) {
    this.emitter = emitter;
    this.handler = (chunk) => this.buffer.push(chunk);
    this.emitter.on("data", this.handler);
  }

  destroy() {
    this.emitter.removeListener("data", this.handler);
  }
}
```

**Pattern 4: Timers not cleared**

```javascript
// LEAK: setInterval holds reference to callback and its closure forever
function startPolling(resource) {
  const bigData = loadInitialState();  // Large object captured by closure

  setInterval(() => {
    // Even if nothing references startPolling's return value,
    // the interval callback + bigData stay alive
    console.log(bigData.status);
  }, 5000);
}

// FIX: Store and clear the interval
function startPolling(resource) {
  const bigData = loadInitialState();
  const intervalId = setInterval(() => {
    console.log(bigData.status);
  }, 5000);

  return { stop: () => clearInterval(intervalId) };
}
```

**Pattern 5: Unbounded caches (Map/Set growing forever)**

```javascript
// LEAK: Map grows without bound
const sessionCache = new Map();

app.use((req, res, next) => {
  sessionCache.set(req.sessionId, { timestamp: Date.now(), data: req.body });
  // Never evicts old entries!
  next();
});

// FIX: Use an LRU cache with maximum size
import { LRUCache } from "lru-cache";

const sessionCache = new LRUCache({
  max: 10000,                // Maximum 10,000 entries
  ttl: 1000 * 60 * 30,      // 30-minute TTL
  maxSize: 50 * 1024 * 1024, // 50MB max
  sizeCalculation: (value) => JSON.stringify(value).length,
});
```

**Pattern 6: Circular references in closures**

```javascript
// While V8's tracing GC handles simple circular references,
// closures that cross async boundaries can create subtle leaks
function setupHandler(server) {
  const connections = new Set();

  server.on("connection", (socket) => {
    connections.add(socket);

    socket.on("close", () => {
      // If this handler throws before removing from Set,
      // socket stays in connections forever
      connections.delete(socket);
    });

    socket.on("error", (err) => {
      // Must also clean up here!
      connections.delete(socket);
    });
  });
}
```

### `process.memoryUsage()`

```javascript
const mem = process.memoryUsage();
console.log({
  rss:          `${(mem.rss / 1024 / 1024).toFixed(1)} MB`,  // Resident Set Size (total OS memory)
  heapTotal:    `${(mem.heapTotal / 1024 / 1024).toFixed(1)} MB`,  // V8 heap allocated
  heapUsed:     `${(mem.heapUsed / 1024 / 1024).toFixed(1)} MB`,  // V8 heap used
  external:     `${(mem.external / 1024 / 1024).toFixed(1)} MB`,  // C++ objects bound to JS
  arrayBuffers: `${(mem.arrayBuffers / 1024 / 1024).toFixed(1)} MB`,  // ArrayBuffers + SharedArrayBuffers
});

// Output:
// {
//   rss:          '85.3 MB',   ← Total memory footprint (includes code, stack, heap)
//   heapTotal:    '72.1 MB',   ← V8 heap total (may include unused committed pages)
//   heapUsed:     '65.8 MB',   ← Actually used by live objects
//   external:     '1.2 MB',    ← C++ allocations tied to JS objects (Buffers, etc.)
//   arrayBuffers: '0.5 MB'     ← Subset of external — ArrayBuffer memory
// }
```

```
Memory Relationship
───────────────────

RSS (Resident Set Size)
┌──────────────────────────────────────────────────────────┐
│  ┌──────────────────────────────────────────┐            │
│  │  heapTotal (V8 heap committed)           │            │
│  │  ┌──────────────────────────────┐        │            │
│  │  │  heapUsed (live objects)     │        │  external  │
│  │  │                              │  free  │  (C++      │
│  │  │                              │  heap  │  objects,  │
│  │  └──────────────────────────────┘        │  Buffers)  │
│  └──────────────────────────────────────────┘            │
│                                                  code,   │
│                                                  stack   │
└──────────────────────────────────────────────────────────┘
```

### Heap Snapshots and Profiling

```bash
# Start Node.js with inspector
node --inspect server.js

# Then open chrome://inspect in Chrome
# → Take Heap Snapshot → Compare snapshots to find growing objects
```

```javascript
// Programmatic heap snapshot (for production debugging)
import v8 from "node:v8";
import fs from "node:fs";

function takeHeapSnapshot() {
  const fileName = `heap-${Date.now()}.heapsnapshot`;
  const snapshotStream = v8.writeHeapSnapshot(fileName);
  console.log(`Heap snapshot written to ${snapshotStream}`);
}

// Trigger via signal
process.on("SIGUSR2", takeHeapSnapshot);

// Or via HTTP endpoint
app.get("/debug/heapsnapshot", (req, res) => {
  const fileName = v8.writeHeapSnapshot();
  res.json({ file: fileName });
});
```

### `--expose-gc` for Manual GC

```bash
node --expose-gc script.js
```

```javascript
// Now global.gc() is available
function measureLeaks() {
  const before = process.memoryUsage().heapUsed;
  global.gc();  // Force full GC to get accurate baseline
  const after = process.memoryUsage().heapUsed;
  console.log(`Heap after GC: ${(after / 1024 / 1024).toFixed(1)} MB`);

  // Run suspect operation
  runSuspectCode();

  global.gc();  // Force GC again
  const final = process.memoryUsage().heapUsed;
  console.log(`Heap growth: ${((final - after) / 1024 / 1024).toFixed(1)} MB`);
}
```

### WeakRef and FinalizationRegistry

Introduced in ES2021, these provide fine-grained control over garbage collection:

```javascript
// WeakRef — holds a reference that doesn't prevent GC
class Cache {
  #map = new Map();

  set(key, value) {
    this.#map.set(key, new WeakRef(value));
  }

  get(key) {
    const ref = this.#map.get(key);
    if (!ref) return undefined;

    const value = ref.deref();  // Returns undefined if GC'd
    if (!value) {
      this.#map.delete(key);  // Clean up stale entry
      return undefined;
    }
    return value;
  }
}

// FinalizationRegistry — callback when an object is GC'd
const registry = new FinalizationRegistry((heldValue) => {
  console.log(`Object with key ${heldValue} was garbage collected`);
  // Clean up external resources, close file handles, etc.
});

function createResource(key) {
  const resource = { data: new ArrayBuffer(1024 * 1024) };
  registry.register(resource, key);  // key is the "held value" passed to callback
  return resource;
}
```

### WeakMap and WeakSet

```javascript
// WeakMap — keys are held weakly (GC'd when no other refs exist)
const metadata = new WeakMap();

function processObject(obj) {
  // Metadata is automatically cleaned up when obj is GC'd
  metadata.set(obj, {
    processedAt: Date.now(),
    result: heavyComputation(obj),
  });
}

// Common use case: associating private data with DOM nodes or external objects
// without preventing their garbage collection
```

---

## 5. Profiling & Diagnostics

### CPU Profiling

**Built-in V8 profiler:**

```bash
# Generate a V8 log file
node --prof server.js
# Process the log into human-readable format
node --prof-process isolate-*.log > processed.txt

# Generate a CPU profile file directly (Chrome DevTools compatible)
node --cpu-prof --cpu-prof-dir=./profiles server.js
# Creates a .cpuprofile file you can load in Chrome DevTools
```

**Programmatic profiling:**

```javascript
import { Session } from "node:inspector/promises";

const session = new Session();
session.connect();

async function profileFunction(fn) {
  await session.post("Profiler.enable");
  await session.post("Profiler.start");

  await fn();  // Run the function we want to profile

  const { profile } = await session.post("Profiler.stop");
  await session.post("Profiler.disable");

  // Write profile to disk
  const fs = await import("node:fs");
  fs.writeFileSync("profile.cpuprofile", JSON.stringify(profile));
  console.log("Profile written to profile.cpuprofile");
}
```

### Flame Graphs and How to Read Them

```
Flame Graph (bottom-up — call stack grows upward)
──────────────────────────────────────────────────

  ┌──────────┐
  │  JSON    │   ← This function is a hotspot (wide = lots of CPU time)
  │ .parse() │
  ├──────────┤
  │parseReq()│
  ├──────────┴──────────────────────┐
  │       handleRequest()           │
  ├─────────────────────────────────┤
  │           server.listen()       │
  ├─────────────────────────────────┤
  │              main()             │
  └─────────────────────────────────┘

  Width = time spent in that function (including children)
  Tip: Look for WIDE boxes near the TOP — those are the hotspots
```

**Key principles for reading flame graphs:**
- **Width** represents time (or samples) — wider means more CPU time
- **Y-axis** is call stack depth — bottom is the entry point, top is the leaf
- **Color** is usually arbitrary (not meaningful in most tools)
- Look for **plateaus** — wide flat boxes near the top indicate hot functions
- **Self time** vs **total time**: a function may be wide because it calls other slow functions

### `perf_hooks` Module

```javascript
import { performance, PerformanceObserver } from "node:perf_hooks";

// Mark and Measure API
performance.mark("db-query-start");
const result = await db.query("SELECT * FROM users");
performance.mark("db-query-end");
performance.measure("db-query", "db-query-start", "db-query-end");

// Observe measurements
const obs = new PerformanceObserver((list) => {
  for (const entry of list.getEntries()) {
    console.log(`${entry.name}: ${entry.duration.toFixed(2)}ms`);
  }
});
obs.observe({ entryTypes: ["measure"] });

// timerify — wrap a function to automatically measure it
function heavyComputation(data) {
  // ... expensive work
  return result;
}

const wrapped = performance.timerify(heavyComputation);

const obs2 = new PerformanceObserver((list) => {
  console.log(list.getEntries()[0].duration);
});
obs2.observe({ entryTypes: ["function"] });

wrapped(someData);  // Automatically measured
```

### Async Hooks

```javascript
import { createHook, executionAsyncId, triggerAsyncId } from "node:async_hooks";

// Track all async resources — useful for finding resource leaks
const activeResources = new Map();

const hook = createHook({
  init(asyncId, type, triggerAsyncId, resource) {
    activeResources.set(asyncId, {
      type,
      triggerAsyncId,
      createdAt: Date.now(),
      stack: new Error().stack,  // Capture creation stack trace
    });
  },
  destroy(asyncId) {
    activeResources.delete(asyncId);
  },
});

hook.enable();

// Periodically log resource counts
setInterval(() => {
  const counts = {};
  for (const [, info] of activeResources) {
    counts[info.type] = (counts[info.type] || 0) + 1;
  }
  console.log("Active async resources:", counts);
  // Output: { TCPWRAP: 15, Timeout: 3, PROMISE: 142, ... }
  // A growing TCPWRAP count might indicate connection leaks
}, 30000);
```

### Diagnostic Reports

```bash
# Generate a diagnostic report on fatal error
node --report-on-fatalerror server.js

# Generate on uncaught exception
node --report-uncaught-exception server.js

# Generate on signal
node --report-on-signal --report-signal=SIGUSR2 server.js
# Then: kill -USR2 <pid>
```

```javascript
// Programmatic report generation
process.report.writeReport("./report.json");

// Report contents include:
// - Node.js and OS metadata
// - JavaScript and native call stacks
// - Heap statistics
// - UV handle and request summary (open sockets, timers, etc.)
// - Environment variables
// - Resource usage
```

### Clinic.js Suite

```bash
# Install
npm install -g clinic

# Doctor — identify common issues (event loop blocking, I/O, GC)
clinic doctor -- node server.js
# Generates an HTML report with recommendations

# Flame — CPU profiling as flame graphs
clinic flame -- node server.js
# Generates an interactive flame graph

# BubbleProf — async flow visualization
clinic bubbleprof -- node server.js
# Shows async operations as bubbles — size = time spent
```

### 0x for Flame Graphs

```bash
# Install and run
npx 0x server.js

# Generates an interactive flame graph in the browser
# Supports filtering, searching, and drilling into specific functions
# Color-coded: V8 internals, user code, Node.js core
```

### Tracing

```bash
# Enable trace events for V8, Node.js, and async operations
node --trace-events-enabled --trace-event-categories=v8,node,node.async_hooks server.js

# Generates a trace file loadable in chrome://tracing or Perfetto UI
# Shows detailed timeline of V8 compilation, GC events, async operations
```

---

## 6. Event Loop Performance

### Event Loop Lag Measurement

Event loop lag is the delay between when a callback is scheduled and when it actually executes.
High lag means the event loop is blocked by synchronous work.

```javascript
// Simple lag measurement
function measureLag() {
  const start = process.hrtime.bigint();

  setImmediate(() => {
    const lag = Number(process.hrtime.bigint() - start) / 1_000_000;
    console.log(`Event loop lag: ${lag.toFixed(2)}ms`);
    // Healthy: < 10ms
    // Warning: 10-100ms
    // Critical: > 100ms
  });
}

setInterval(measureLag, 1000);
```

### `monitorEventLoopDelay` API

```javascript
import { monitorEventLoopDelay } from "node:perf_hooks";

// Creates a histogram that samples event loop delay
const histogram = monitorEventLoopDelay({ resolution: 20 });  // 20ms resolution
histogram.enable();

setInterval(() => {
  console.log({
    min:    `${(histogram.min / 1e6).toFixed(2)}ms`,
    max:    `${(histogram.max / 1e6).toFixed(2)}ms`,
    mean:   `${(histogram.mean / 1e6).toFixed(2)}ms`,
    stddev: `${(histogram.stddev / 1e6).toFixed(2)}ms`,
    p50:    `${(histogram.percentile(50) / 1e6).toFixed(2)}ms`,
    p99:    `${(histogram.percentile(99) / 1e6).toFixed(2)}ms`,
  });
  histogram.reset();
}, 5000);

// Output:
// { min: '0.01ms', max: '12.34ms', mean: '0.15ms', stddev: '0.82ms',
//   p50: '0.02ms', p99: '3.45ms' }
```

### Blocking the Event Loop: Symptoms and Detection

```
Blocked Event Loop Timeline
────────────────────────────

Normal (healthy):
  ┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐
  │CB││CB││CB││CB││CB││CB││CB││CB│   CB = callback
  └──┘└──┘└──┘└──┘└──┘└──┘└──┘└──┘
  Callbacks processed rapidly, low lag

Blocked:
  ┌──┐┌──────────────────────────────────┐┌──┐┌──┐┌──┐
  │CB││     SYNC OPERATION (500ms)       ││CB││CB││CB│
  └──┘└──────────────────────────────────┘└──┘└──┘└──┘
  Queued callbacks delayed, high lag, timeouts fire late
```

**Common event loop blockers:**
- `JSON.parse()` / `JSON.stringify()` on large payloads (>1MB)
- Synchronous file operations (`fs.readFileSync`, `fs.writeFileSync`)
- Heavy regex on untrusted input (ReDoS)
- Large array sorting or iteration without yielding
- Crypto operations (`crypto.pbkdf2Sync`, `crypto.randomBytes` sync variant)
- Startup: `require()` of large modules with side effects

```javascript
// BLOCKED: Synchronous JSON processing of large data
app.post("/import", (req, res) => {
  const data = JSON.parse(req.body);  // 50MB JSON — blocks for 200ms+
  processData(data);
  res.send("ok");
});

// FIX: Use streaming JSON parser
import { parser } from "stream-json";
import { streamArray } from "stream-json/streamers/StreamArray";

app.post("/import", (req, res) => {
  const pipeline = req.pipe(parser()).pipe(streamArray());
  pipeline.on("data", ({ value }) => processItem(value));
  pipeline.on("end", () => res.send("ok"));
});
```

### Yielding to the Event Loop

```javascript
// setImmediate yields to the event loop between chunks
async function processLargeArray(items) {
  const CHUNK_SIZE = 1000;

  for (let i = 0; i < items.length; i += CHUNK_SIZE) {
    const chunk = items.slice(i, i + CHUNK_SIZE);
    processChunk(chunk);

    // Yield to the event loop after each chunk
    if (i + CHUNK_SIZE < items.length) {
      await new Promise((resolve) => setImmediate(resolve));
    }
  }
}

// Alternative: Use worker threads for truly CPU-intensive work
import { Worker, isMainThread, parentPort, workerData } from "node:worker_threads";

if (isMainThread) {
  async function offloadToWorker(data) {
    return new Promise((resolve, reject) => {
      const worker = new Worker(new URL(import.meta.url), {
        workerData: data,
      });
      worker.on("message", resolve);
      worker.on("error", reject);
    });
  }
} else {
  // Worker thread — does heavy computation without blocking main event loop
  const result = heavyComputation(workerData);
  parentPort.postMessage(result);
}
```

### Thread Pool Saturation Detection

Node.js uses libuv's thread pool (default size: 4) for file system operations, DNS lookups,
and some crypto operations. When all threads are busy, new operations queue up, causing
unexpected latency.

```javascript
// Detect thread pool saturation by monitoring fs operation latency
import fs from "node:fs/promises";

async function checkThreadPoolHealth() {
  const start = process.hrtime.bigint();
  await fs.access("/tmp");  // Simple fs operation that uses thread pool
  const elapsed = Number(process.hrtime.bigint() - start) / 1_000_000;

  if (elapsed > 100) {
    console.warn(`Thread pool may be saturated: fs.access took ${elapsed.toFixed(0)}ms`);
  }
}

// Increase thread pool size if needed (must be set BEFORE any async I/O)
// Set via environment variable:
// UV_THREADPOOL_SIZE=16 node server.js
```

```
Thread Pool Saturation
──────────────────────

UV_THREADPOOL_SIZE=4 (default)

Thread 1: [fs.readFile ████████████]
Thread 2: [dns.lookup  ████████]
Thread 3: [crypto.pbkdf2 ██████████████████]
Thread 4: [fs.stat     ██████]

Queue:    [fs.readFile] [dns.lookup] [fs.writeFile]  ← waiting!

These queued operations experience added latency
```

---

## 7. Optimization Patterns

### Object Pooling

Reduces GC pressure by reusing objects instead of allocating and discarding them.

```javascript
class ObjectPool<T> {
  private pool: T[] = [];
  private factory: () => T;
  private reset: (obj: T) => void;

  constructor(factory: () => T, reset: (obj: T) => void, initialSize = 100) {
    this.factory = factory;
    this.reset = reset;

    // Pre-allocate
    for (let i = 0; i < initialSize; i++) {
      this.pool.push(factory());
    }
  }

  acquire(): T {
    return this.pool.length > 0 ? this.pool.pop()! : this.factory();
  }

  release(obj: T): void {
    this.reset(obj);
    this.pool.push(obj);
  }
}

// Usage: reusing buffer objects in a hot request path
interface RequestContext {
  id: string;
  timestamp: number;
  data: Record<string, unknown>;
}

const contextPool = new ObjectPool<RequestContext>(
  () => ({ id: "", timestamp: 0, data: {} }),
  (ctx) => {
    ctx.id = "";
    ctx.timestamp = 0;
    for (const key in ctx.data) delete ctx.data[key];
  },
  500,
);

app.use((req, res, next) => {
  const ctx = contextPool.acquire();
  ctx.id = req.id;
  ctx.timestamp = Date.now();

  res.on("finish", () => contextPool.release(ctx));
  next();
});
```

### Buffer Reuse for I/O

```javascript
// BAD: Allocating a new buffer for every read operation
async function processFile(path: string): Promise<void> {
  const handle = await fs.open(path, "r");
  let bytesRead: number;

  do {
    const buffer = Buffer.alloc(64 * 1024);  // 64KB allocated every iteration!
    ({ bytesRead } = await handle.read(buffer, 0, buffer.length));
    processChunk(buffer.subarray(0, bytesRead));
  } while (bytesRead > 0);

  await handle.close();
}

// GOOD: Reuse a single buffer
async function processFile(path: string): Promise<void> {
  const handle = await fs.open(path, "r");
  const buffer = Buffer.alloc(64 * 1024);  // Allocate once
  let bytesRead: number;

  do {
    ({ bytesRead } = await handle.read(buffer, 0, buffer.length));
    processChunk(buffer.subarray(0, bytesRead));
  } while (bytesRead > 0);

  await handle.close();
}
```

### Stream Processing vs Loading Entire Files

```javascript
// BAD: Loading entire file into memory
import fs from "node:fs/promises";

async function processCSV(path: string): Promise<number> {
  const content = await fs.readFile(path, "utf-8");  // 2GB file = 2GB in memory
  const lines = content.split("\n");                   // Now 4GB (original + split array)
  let sum = 0;
  for (const line of lines) {
    sum += parseFloat(line.split(",")[2]);
  }
  return sum;
}

// GOOD: Stream processing — constant memory regardless of file size
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";

async function processCSV(path: string): Promise<number> {
  const rl = createInterface({
    input: createReadStream(path),
    crlfDelay: Infinity,
  });

  let sum = 0;
  for await (const line of rl) {
    sum += parseFloat(line.split(",")[2]);
  }
  return sum;
}
```

### Connection Pooling

```javascript
// Database connection pooling (PostgreSQL example)
import { Pool } from "pg";

const pool = new Pool({
  host: "localhost",
  database: "myapp",
  max: 20,                   // Maximum 20 connections in pool
  idleTimeoutMillis: 30000,  // Close idle connections after 30s
  connectionTimeoutMillis: 2000,  // Error if cannot connect in 2s
});

// Each query borrows a connection, returns it when done
async function getUser(id: number) {
  const { rows } = await pool.query("SELECT * FROM users WHERE id = $1", [id]);
  return rows[0];
}

// HTTP agent connection pooling
import http from "node:http";

const agent = new http.Agent({
  keepAlive: true,
  maxSockets: 50,          // Max concurrent sockets per host
  maxFreeSockets: 10,      // Max idle sockets to keep alive
  timeout: 60000,          // Socket timeout
});

// Use in requests
http.get("http://api.example.com/data", { agent }, (res) => {
  // Connection reused from pool if available
});
```

### Avoiding Megamorphic Call Sites

```javascript
// BAD: Many different shapes at one call site
function processEvent(event: any) {
  return event.type;  // Receives clicks, keys, scrolls, resizes, etc.
}                      // All different shapes → megamorphic

// GOOD: Normalize objects before the hot path
interface NormalizedEvent {
  type: string;
  timestamp: number;
  payload: unknown;
}

function normalizeEvent(raw: any): NormalizedEvent {
  return {
    type: raw.type,
    timestamp: raw.timestamp ?? Date.now(),
    payload: raw,
  };
}

function processEvent(event: NormalizedEvent) {
  return event.type;  // Always same shape → monomorphic
}

// Or use classes (consistent hidden class)
class AppEvent {
  constructor(
    public type: string,
    public timestamp: number,
    public payload: unknown,
  ) {}
}
```

### JSON Performance

```javascript
// V8 added a faster JSON parser (2024) with SIMD optimizations. Performance vs
// hand-constructed object literals depends heavily on size and shape — don't
// assume JSON.parse is always faster; benchmark your specific payload.

// But for very large payloads, consider alternatives:
// 1. Streaming JSON parser for large files
import { parser } from "stream-json";

// 2. Schema-based serialization for known structures
// Protocol Buffers, MessagePack, or Avro — 2-10x faster than JSON

// 3. JSON.stringify() optimization — use replacer to avoid serializing unused fields
const user = { id: 1, name: "Alice", internalToken: "secret", metadata: hugeObject };

// BAD: Serializes everything
JSON.stringify(user);

// GOOD: Only serialize what's needed
JSON.stringify(user, ["id", "name"]);

// 4. For repeated serialization of same-shaped objects, fast-json-stringify
//    pre-compiles a schema-based serializer (2-5x faster)
import fastJson from "fast-json-stringify";

const stringify = fastJson({
  type: "object",
  properties: {
    id: { type: "integer" },
    name: { type: "string" },
  },
});

stringify({ id: 1, name: "Alice" });  // Much faster for known schemas
```

### `structuredClone()` vs Spread vs JSON Round-Trip

```javascript
const original = {
  name: "Alice",
  scores: [1, 2, 3],
  nested: { deep: { value: 42 } },
  date: new Date(),
  regex: /pattern/gi,
  map: new Map([["key", "value"]]),
};

// 1. Spread / Object.assign — SHALLOW copy only
const shallow = { ...original };
shallow.nested.deep.value = 99;  // Mutates original! (shared reference)

// 2. JSON round-trip — deep copy but lossy
const jsonClone = JSON.parse(JSON.stringify(original));
// Loses: Date (→ string), RegExp (→ {}), Map (→ {}), functions, undefined, symbols

// 3. structuredClone() — deep copy, preserves most types
const clone = structuredClone(original);
// Preserves: Date, RegExp, Map, Set, ArrayBuffer, Error
// Cannot clone: functions, DOM nodes, symbols

// Performance comparison (rough):
// Shallow spread:     ~0.001ms  (not a real clone)
// JSON round-trip:    ~0.5ms    (for medium objects)
// structuredClone():  ~0.3ms    (for medium objects, preserves types)
```

### Pre-Allocated Arrays

```javascript
// BAD: Dynamic growth — V8 must reallocate and copy multiple times
function generateRange(n: number): number[] {
  const arr = [];
  for (let i = 0; i < n; i++) {
    arr.push(i);
    // V8 grows internal backing stores by ~1.5x (not 2x) above a threshold.
    // Small arrays still grow to specific sizes (4, 8, 16), but the
    // steady-state multiplier is 1.5.
  }
  return arr;
}

// GOOD: Pre-allocate when size is known
function generateRange(n: number): number[] {
  const arr = new Array(n);
  for (let i = 0; i < n; i++) {
    arr[i] = i;  // No resizing needed
  }
  return arr;
}

// BEST for numeric data: Use typed arrays
function generateRange(n: number): Int32Array {
  const arr = new Int32Array(n);  // Contiguous memory, no boxing
  for (let i = 0; i < n; i++) {
    arr[i] = i;
  }
  return arr;
}
```

### String Concatenation

```javascript
// V8 optimizes string concatenation internally using "cons strings"
// (a tree of string fragments). But be aware of patterns:

// FINE for small/medium strings — V8 handles this well
const greeting = "Hello, " + name + "! Welcome to " + place + ".";

// FINE — template literals compile to essentially the same thing
const greeting = `Hello, ${name}! Welcome to ${place}.`;

// SLOW for building very large strings in a loop
let html = "";
for (const item of items) {  // 100,000 items
  html += `<li>${item.name}</li>`;  // Quadratic behavior for very large strings
}

// FASTER: Use array join
const parts: string[] = [];
for (const item of items) {
  parts.push(`<li>${item.name}</li>`);
}
const html = parts.join("");

// FASTEST for I/O: Use Buffers and streams — avoid string building entirely
import { Writable } from "node:stream";

function renderHTML(items: Item[], output: Writable): void {
  output.write("<ul>");
  for (const item of items) {
    output.write(`<li>${item.name}</li>`);
  }
  output.write("</ul>");
}
```

---

## 8. Benchmarking

### Microbenchmark Pitfalls

V8's optimizer can make microbenchmarks wildly misleading. Understanding these pitfalls is
essential for senior engineers.

```javascript
// PITFALL 1: Dead code elimination
// V8 may optimize away code whose result is never used
function benchmarkSort() {
  const start = performance.now();
  for (let i = 0; i < 1000; i++) {
    const arr = [5, 3, 1, 4, 2];
    arr.sort();  // V8 may eliminate this — result is never observed!
  }
  console.log(`Time: ${performance.now() - start}ms`);  // Misleadingly fast
}

// FIX: Use the result (prevent dead code elimination)
function benchmarkSort() {
  const start = performance.now();
  let checksum = 0;
  for (let i = 0; i < 1000; i++) {
    const arr = [5, 3, 1, 4, 2];
    arr.sort();
    checksum += arr[0];  // Force V8 to keep the sort
  }
  console.log(`Time: ${performance.now() - start}ms, checksum: ${checksum}`);
}
```

```javascript
// PITFALL 2: JIT warming — first runs are slower (interpreted) than later runs (optimized)
function benchmark(fn: () => void, iterations: number) {
  // Warm up — let TurboFan optimize the function
  for (let i = 0; i < 1000; i++) fn();

  // Now measure
  const start = performance.now();
  for (let i = 0; i < iterations; i++) fn();
  const elapsed = performance.now() - start;

  console.log(`${elapsed.toFixed(2)}ms total, ${(elapsed / iterations).toFixed(4)}ms/op`);
}
```

```javascript
// PITFALL 3: Monomorphic bias — benchmarks often test one shape, production sees many
function benchAccess(obj: any) {
  return obj.x + obj.y;
}

// Benchmark: always same shape → monomorphic → fast
// Production: many shapes → polymorphic/megamorphic → slow

// PITFALL 4: Inlining — small functions in tight loops get inlined, eliminating call overhead
// The benchmark shows near-zero overhead, but real-world code may not trigger inlining

// PITFALL 5: GC pauses — short benchmarks may not trigger GC at all
// Allocate enough to trigger GC during measurement for realistic numbers
```

### Proper Statistical Benchmarking

```javascript
// Use Benchmark.js for statistically sound results
import Benchmark from "benchmark";

const suite = new Benchmark.Suite();

suite
  .add("Map#set", () => {
    const map = new Map();
    map.set("foo", "bar");
  })
  .add("Object literal", () => {
    const obj: Record<string, string> = {};
    obj["foo"] = "bar";
  })
  .on("cycle", (event: Benchmark.Event) => {
    console.log(String(event.target));
    // Output: "Map#set x 45,234,567 ops/sec +-1.23% (94 runs sampled)"
  })
  .on("complete", function (this: Benchmark.Suite) {
    console.log(`Fastest is ${this.filter("fastest").map("name")}`);
  })
  .run({ async: true });

// Benchmark.js handles:
// - Statistical significance (margin of error)
// - Adaptive iteration count
// - Outlier detection
// - Clock resolution compensation
```

### Quick Timing with `console.time`

```javascript
// Simple timing — good enough for order-of-magnitude checks
console.time("database-query");
const results = await db.query("SELECT * FROM large_table");
console.timeEnd("database-query");
// Output: database-query: 234.567ms

// With labels for concurrent operations
for (const url of urls) {
  console.time(`fetch-${url}`);
  fetch(url).then(() => console.timeEnd(`fetch-${url}`));
}
```

### `perf_hooks.performance.timerify()`

```javascript
import { performance, PerformanceObserver } from "node:perf_hooks";

// Automatically measure every call to a function
function fibonacci(n: number): number {
  if (n <= 1) return n;
  return fibonacci(n - 1) + fibonacci(n - 2);
}

const measuredFib = performance.timerify(fibonacci);

const obs = new PerformanceObserver((list) => {
  const entries = list.getEntries();
  for (const entry of entries) {
    console.log(`${entry.name}(${(entry as any).detail?.[0]}): ${entry.duration.toFixed(2)}ms`);
  }
});
obs.observe({ entryTypes: ["function"] });

measuredFib(30);  // Automatically logged with timing
measuredFib(35);
```

### HTTP Benchmarking with Autocannon

```bash
# Install
npm install -g autocannon

# Basic benchmark
autocannon -c 100 -d 30 http://localhost:3000/api/users
#   -c 100: 100 concurrent connections
#   -d 30:  30 seconds duration

# Output:
# Stat    2.5%   50%    97.5%  99%    Avg     Stdev   Max
# Latency 1 ms   3 ms   12 ms  25 ms  4.2 ms  5.1 ms  312 ms
#
# Stat      1%      2.5%    50%      97.5%   Avg      Stdev    Min
# Req/Sec   18,431  19,200  23,456   24,100  22,890   1,234    18,400
#
# 687k requests in 30s, 1.2 GB read
```

```javascript
// Programmatic autocannon usage
import autocannon from "autocannon";

const result = await autocannon({
  url: "http://localhost:3000/api/users",
  connections: 100,
  duration: 30,
  headers: {
    authorization: "Bearer test-token",
  },
});

console.log({
  avgLatency: result.latency.average,
  p99Latency: result.latency.p99,
  avgRPS: result.requests.average,
  totalRequests: result.requests.total,
  errors: result.errors,
});
```

### Load Testing with k6

```javascript
// k6 script (runs outside Node.js, but relevant for Node.js API testing)
// save as load-test.js

import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  stages: [
    { duration: "30s", target: 50 },   // Ramp up to 50 users
    { duration: "1m", target: 50 },    // Stay at 50
    { duration: "30s", target: 200 },  // Ramp up to 200 users
    { duration: "2m", target: 200 },   // Stay at 200
    { duration: "30s", target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ["p(95)<500"],  // 95% of requests must complete in <500ms
    http_req_failed: ["rate<0.01"],    // Error rate must be <1%
  },
};

export default function () {
  const res = http.get("http://localhost:3000/api/users");
  check(res, {
    "status is 200": (r) => r.status === 200,
    "response time < 200ms": (r) => r.timings.duration < 200,
  });
  sleep(1);
}

// Run: k6 run load-test.js
```

### Realistic Benchmarking Methodology

```
Benchmarking Methodology Checklist
───────────────────────────────────

 1. Define what you're measuring
    □ Throughput (requests/sec)?
    □ Latency (p50, p95, p99)?
    □ Memory usage under load?
    □ CPU utilization?

 2. Environment preparation
    □ Dedicated machine (no other workloads)
    □ Same OS, Node.js version, V8 version as production
    □ Disable CPU scaling (performance governor)
    □ Warm up: Run workload for 30-60s before measuring

 3. Realistic workload
    □ Production-like data sizes
    □ Mix of operations (reads, writes, computes)
    □ Multiple client connections (not single-threaded)
    □ Include I/O latency (database, network)

 4. Statistical rigor
    □ Run for sufficient duration (>60s for HTTP, >10K ops for micro)
    □ Multiple runs (at least 5, report median + percentiles)
    □ Report standard deviation / margin of error
    □ Check for bimodal distributions (GC pauses)

 5. Compare fairly
    □ Same data, same machine, same conditions
    □ A/B test: alternate between implementations
    □ Watch for confounding variables (caching, JIT state)

 6. Common traps to avoid
    □ "First request" bias (cold cache, no JIT)
    □ Coordinated omission (load generator backs off when server slows)
    □ Measuring the wrong thing (client overhead, network)
    □ Ignoring tail latency (p50 looks fine, p99 is 10x worse)
```

```javascript
// Complete benchmark harness example
import { performance } from "node:perf_hooks";

interface BenchmarkResult {
  name: string;
  opsPerSec: number;
  avgMs: number;
  p50Ms: number;
  p99Ms: number;
  maxMs: number;
  samples: number;
}

async function benchmark(
  name: string,
  fn: () => void | Promise<void>,
  options: { warmup?: number; duration?: number } = {},
): Promise<BenchmarkResult> {
  const { warmup = 1000, duration = 5000 } = options;

  // Warmup phase
  for (let i = 0; i < warmup; i++) await fn();

  // Measurement phase
  const timings: number[] = [];
  const deadline = performance.now() + duration;

  while (performance.now() < deadline) {
    const start = performance.now();
    await fn();
    timings.push(performance.now() - start);
  }

  // Calculate statistics
  timings.sort((a, b) => a - b);
  const totalMs = timings.reduce((sum, t) => sum + t, 0);

  return {
    name,
    opsPerSec: Math.round((timings.length / totalMs) * 1000),
    avgMs: totalMs / timings.length,
    p50Ms: timings[Math.floor(timings.length * 0.5)],
    p99Ms: timings[Math.floor(timings.length * 0.99)],
    maxMs: timings[timings.length - 1],
    samples: timings.length,
  };
}

// Usage
const results = await Promise.all([
  benchmark("JSON.parse small", () => JSON.parse('{"a":1,"b":2}')),
  benchmark("JSON.parse large", () => JSON.parse(largeJsonString)),
  benchmark("structuredClone", () => structuredClone(testObject)),
]);

console.table(results);
```

---

## Quick Reference: V8 Diagnostic Flags

| Flag | Purpose |
|------|---------|
| `--trace-opt` | Log optimized function compilations |
| `--trace-deopt` | Log deoptimizations with reasons |
| `--trace-gc` | Log GC events with heap sizes |
| `--trace-gc-verbose` | Detailed GC logging |
| `--print-bytecode` | Print Ignition bytecode for functions |
| `--print-opt-code` | Print TurboFan optimized machine code |
| `--allow-natives-syntax` | Enable `%DebugPrint()`, `%OptimizeFunctionOnNextCall()`, etc. |
| `--expose-gc` | Make `global.gc()` available |
| `--max-old-space-size=N` | Set max old generation size (MB) |
| `--max-semi-space-size=N` | Set max semi-space size (MB) |
| `--cpu-prof` | Generate CPU profile on exit |
| `--heap-prof` | Generate heap profile on exit |
| `--inspect` | Enable Chrome DevTools inspector |
| `--inspect-brk` | Enable inspector and break on first line |
| `--trace-events-enabled` | Enable trace event recording |
| `--report-on-fatalerror` | Generate diagnostic report on crash |

## Quick Reference: Key Metrics for Production Monitoring

```javascript
// Essential metrics to track in production
import { monitorEventLoopDelay } from "node:perf_hooks";

const h = monitorEventLoopDelay({ resolution: 20 });
h.enable();

setInterval(() => {
  const mem = process.memoryUsage();

  const metrics = {
    // Memory
    heap_used_mb: mem.heapUsed / 1024 / 1024,
    heap_total_mb: mem.heapTotal / 1024 / 1024,
    rss_mb: mem.rss / 1024 / 1024,
    external_mb: mem.external / 1024 / 1024,

    // Event loop
    event_loop_lag_p50_ms: h.percentile(50) / 1e6,
    event_loop_lag_p99_ms: h.percentile(99) / 1e6,
    event_loop_lag_max_ms: h.max / 1e6,

    // Process
    active_handles: process._getActiveHandles().length,
    active_requests: process._getActiveRequests().length,
    uptime_seconds: process.uptime(),
  };

  // Send to your metrics system (Prometheus, Datadog, etc.)
  emitMetrics(metrics);
  h.reset();
}, 10000);
```
