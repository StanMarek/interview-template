# Core Node.js — Senior Engineer Interview Preparation

---

## 1. V8 Engine Internals

### Compilation Pipeline

V8 is Google's open-source JavaScript engine written in C++. Unlike traditional interpreters, V8 compiles JavaScript to native machine code using a multi-tier pipeline.

```
                        JavaScript Source Code
                               │
                               ▼
                     ┌───────────────────┐
                     │      Parser       │
                     │  (generates AST)  │
                     └────────┬──────────┘
                              │
                              ▼
                     ┌───────────────────┐
                     │     Ignition      │
                     │   (Interpreter)   │
                     │  Generates and    │
                     │  executes bytecode│
                     └────────┬──────────┘
                              │
                    Collects profiling data
                    (type feedback, call counts)
                              │
                  ┌───────────┴───────────┐
                  │  Hot function?         │
                  │  (called many times)   │
                  └───────────┬───────────┘
                         YES  │
                              ▼
                     ┌───────────────────┐
                     │     TurboFan      │
                     │   (Optimizing     │
                     │    Compiler)      │
                     │  Generates        │
                     │  optimized        │
                     │  machine code     │
                     └────────┬──────────┘
                              │
                  ┌───────────┴───────────┐
                  │  Assumptions broken?  │
                  │  (type changed, etc.) │
                  └───────────┬───────────┘
                         YES  │
                              ▼
                     ┌───────────────────┐
                     │   Deoptimization  │
                     │  (bail out back   │
                     │   to Ignition)    │
                     └───────────────────┘
```

**Ignition** (interpreter): Compiles AST to compact bytecode and executes it immediately. Bytecode is ~25-50% the size of equivalent machine code. Ignition also collects **type feedback** — recording what types variables actually hold at runtime.

**TurboFan** (optimizing compiler): When a function is called enough times ("hot"), TurboFan uses the collected type feedback to generate highly optimized machine code with speculative optimizations based on observed types.

### Hidden Classes (Maps) and Inline Caching

V8 assigns every object a **hidden class** (internally called a "Map") that describes its shape — which properties it has, at what offsets, and their types. Objects with the same property names added in the same order share the same hidden class.

```typescript
// Both objects get the SAME hidden class — same properties, same order
const a = { x: 1, y: 2 };
const b = { x: 3, y: 4 };

// This object gets a DIFFERENT hidden class — different property order
const c = { y: 2, x: 1 };
```

**Hidden class transitions** form a tree. Each time a property is added, V8 transitions to a new hidden class:

```
Object {}          →  Map M0
  add .x           →  Map M1 (has x at offset 0)
    add .y         →  Map M2 (has x at offset 0, y at offset 1)
```

**Inline Caching (IC)**: When V8 sees `obj.x`, it records the hidden class and the offset of `x`. On subsequent calls, if the object has the same hidden class, V8 reads the property directly from the known offset — no lookup needed.

| IC State | Meaning | Performance |
|----------|---------|-------------|
| Uninitialized | No data collected yet | Slow |
| Monomorphic | One hidden class seen | Fast — direct offset access |
| Polymorphic | 2-4 hidden classes seen | Moderate — checks a few maps |
| Megamorphic | 5+ hidden classes seen | Slow — generic dictionary lookup |

**Performance implication**: Functions that process objects with consistent shapes (monomorphic) run dramatically faster than those processing objects with varied shapes (megamorphic).

```typescript
// GOOD — monomorphic: all objects have the same shape
interface Point { x: number; y: number; }

function distance(p: Point): number {
  return Math.sqrt(p.x * p.x + p.y * p.y);
}

// All calls pass objects with identical hidden class
distance({ x: 1, y: 2 });
distance({ x: 3, y: 4 });
distance({ x: 5, y: 6 });

// BAD — megamorphic: objects have different shapes
function getX(obj: any): any {
  return obj.x; // IC becomes megamorphic
}
getX({ x: 1 });
getX({ x: 1, y: 2 });
getX({ a: 0, x: 1 });
getX({ x: 1, z: 3, w: 4 });
getX({ name: "test", x: 1 });
```

### Optimization and Deoptimization (Bailouts)

TurboFan makes **speculative assumptions** based on type feedback. When assumptions are violated, V8 **deoptimizes** — throwing away the optimized code and falling back to Ignition bytecode.

Common causes of deoptimization:
- **Type change**: A variable that was always a number suddenly receives a string
- **Hidden class mismatch**: An object's shape changes unexpectedly
- **Out-of-bounds array access**: Accessing sparse arrays or arrays with holes
- **Arguments object**: Using `arguments` in certain ways prevents optimization

```typescript
// This function will be optimized for integers
function add(a: number, b: number): number {
  return a + b;
}

// V8 optimizes assuming a and b are always SMIs (Small Integers)
for (let i = 0; i < 100_000; i++) {
  add(i, i + 1); // hot loop, TurboFan kicks in
}

// This call causes deoptimization — string is not a number
// (TypeScript catches this at compile time, but pure JS would hit this)
// add("hello", "world"); // DEOPT: type assumption broken
```

**Detecting deoptimizations**: Run Node with `--trace-deopt` to see deoptimization events:

```bash
node --trace-deopt app.js
```

### JIT vs AOT Compilation

| Aspect | JIT (V8 default) | AOT (e.g., V8 snapshots) |
|--------|------------------|--------------------------|
| When | At runtime | Before execution |
| Advantage | Can use runtime profiling data for better optimization | Faster startup, no warmup |
| Disadvantage | Warmup cost, memory for compiler | Cannot use runtime type info |
| Node.js example | Normal execution | V8 startup snapshots, `--build-snapshot` |

Node.js uses **V8 snapshots** to speed up startup: the heap state after initializing built-in modules is serialized to disk. On startup, V8 deserializes the snapshot instead of re-executing initialization code.

---

## 2. Event Loop Deep Dive

### Event Loop Architecture

Node.js is single-threaded for JavaScript execution, but delegates I/O to the OS kernel or the libuv thread pool. The event loop is the core mechanism that coordinates callbacks.

```
   ┌───────────────────────────┐
┌─>│         timers             │  ← setTimeout(), setInterval() callbacks
│  └─────────────┬─────────────┘
│  ┌─────────────┴─────────────┐
│  │     pending callbacks      │  ← I/O callbacks deferred from previous cycle
│  └─────────────┬─────────────┘
│  ┌─────────────┴─────────────┐
│  │       idle, prepare        │  ← internal use only (libuv housekeeping)
│  └─────────────┬─────────────┘
│  ┌─────────────┴─────────────┐     ┌──────────────────┐
│  │          poll              │<────┤  incoming I/O     │
│  │  (retrieve new I/O events) │     │  connections,     │
│  │  (execute I/O callbacks)   │     │  data, etc.       │
│  └─────────────┬─────────────┘     └──────────────────┘
│  ┌─────────────┴─────────────┐
│  │          check             │  ← setImmediate() callbacks
│  └─────────────┬─────────────┘
│  ┌─────────────┴─────────────┐
│  │      close callbacks       │  ← socket.on('close'), etc.
│  └─────────────┬─────────────┘
└─────────────────┘

Between EVERY phase transition:
  ┌─────────────────────────────┐
  │  1. process.nextTick queue  │  ← drained completely
  │  2. microtask queue         │  ← drained completely (Promises)
  └─────────────────────────────┘
```

### Phase Details

| Phase | What Executes | Key Details |
|-------|--------------|-------------|
| **timers** | `setTimeout`, `setInterval` callbacks | Executes callbacks whose threshold has elapsed. Not guaranteed to be exact — poll phase may delay them. |
| **pending callbacks** | Deferred I/O callbacks | Some system-level callbacks (e.g., TCP `ECONNREFUSED` on some systems). |
| **idle, prepare** | Internal libuv operations | Not accessible from user code. |
| **poll** | I/O callbacks (fs, net, etc.) | Will block here if no timers scheduled and queue is empty. Calculates how long to block based on nearest timer. |
| **check** | `setImmediate()` callbacks | Executes immediately after poll phase completes. |
| **close** | Close event callbacks | `socket.on('close', ...)`, `server.on('close', ...)`. |

### Microtask Queue vs Macrotask Queue

**Microtasks** (higher priority):
- `Promise.then/catch/finally` callbacks
- `queueMicrotask()` callbacks
- `process.nextTick()` callbacks (even higher priority — separate queue drained first)

**Macrotasks** (lower priority — one per event loop iteration phase):
- `setTimeout()`, `setInterval()`
- `setImmediate()`
- I/O callbacks
- Close callbacks

**Critical rule**: The entire microtask queue (including `process.nextTick`) is drained between every phase of the event loop, and also between every individual macrotask within a phase (since Node.js 11+).

### `process.nextTick()` vs `Promise.resolve()` vs `setImmediate()` vs `setTimeout(0)`

```typescript
// Execution order demonstration
console.log("1: synchronous start");

setTimeout(() => console.log("2: setTimeout"), 0);

setImmediate(() => console.log("3: setImmediate"));

Promise.resolve().then(() => console.log("4: Promise.resolve"));

process.nextTick(() => console.log("5: process.nextTick"));

console.log("6: synchronous end");

// OUTPUT:
// 1: synchronous start
// 6: synchronous end
// 5: process.nextTick        ← nextTick queue drained first
// 4: Promise.resolve         ← microtask queue drained second
// 2: setTimeout              ← timers phase (OR 3 first — see note below)
// 3: setImmediate            ← check phase (OR 2 first — see note below)
```

**Important nuance**: When `setTimeout(fn, 0)` and `setImmediate(fn)` are called from the main module (outside an I/O cycle), the order is **non-deterministic** — it depends on process performance and system timer resolution. However, inside an I/O callback, `setImmediate` **always** executes before `setTimeout`:

```typescript
import * as fs from "fs";

fs.readFile(__filename, () => {
  setTimeout(() => console.log("setTimeout"), 0);
  setImmediate(() => console.log("setImmediate"));
});

// OUTPUT (always deterministic inside I/O callback):
// setImmediate       ← check phase runs right after poll
// setTimeout         ← timers phase on next iteration
```

### Priority ordering

```
┌──────────────────────────────────────────────┐
│  process.nextTick()  ← HIGHEST PRIORITY     │
├──────────────────────────────────────────────┤
│  Promise microtasks  ← HIGH PRIORITY         │
├──────────────────────────────────────────────┤
│  setTimeout(fn, 0)   ← MEDIUM (timers phase)│
├──────────────────────────────────────────────┤
│  setImmediate(fn)    ← MEDIUM (check phase)  │
├──────────────────────────────────────────────┤
│  I/O callbacks       ← DEPENDS ON poll phase │
└──────────────────────────────────────────────┘
```

### Starvation Scenarios

`process.nextTick()` can starve the event loop because the entire nextTick queue is drained before moving to the next phase. If a nextTick callback schedules another nextTick recursively, I/O will never be processed.

```typescript
// DANGER: This starves the event loop — I/O will never be processed
function starveLoop(): void {
  process.nextTick(starveLoop);
}
starveLoop();
// setTimeout, I/O callbacks, setImmediate — NONE of these will ever run

// SAFE alternative: Use setImmediate for recursive patterns
function safeRecursion(): void {
  setImmediate(safeRecursion);
}
safeRecursion();
// Other phases get a chance to run between iterations
```

**Promise microtask starvation** is also possible (since Node.js 11+):

```typescript
// This also starves the event loop
function starveWithPromises(): void {
  Promise.resolve().then(starveWithPromises);
}
starveWithPromises();
```

### Advanced: Nested `nextTick` and microtask interleaving

```typescript
process.nextTick(() => {
  console.log("nextTick 1");
  Promise.resolve().then(() => console.log("promise inside nextTick"));
  process.nextTick(() => console.log("nested nextTick"));
});

process.nextTick(() => console.log("nextTick 2"));

// OUTPUT:
// nextTick 1
// nextTick 2
// nested nextTick        ← all nextTicks drain before promises
// promise inside nextTick
```

---

## 3. Module System

### CommonJS vs ESM

| Feature | CommonJS (CJS) | ECMAScript Modules (ESM) |
|---------|----------------|--------------------------|
| Syntax | `require()` / `module.exports` | `import` / `export` |
| Loading | Synchronous, at runtime | Asynchronous, statically analyzed |
| Parsing | Executed on `require()` | Parsed before execution |
| Top-level `await` | Not supported | Supported |
| `this` at top level | `module.exports` (the exports object) | `undefined` |
| File extension | `.js` (default) or `.cjs` | `.mjs` or `.js` with `"type": "module"` |
| Tree-shaking | Not possible (dynamic) | Possible (static imports) |
| `__dirname` / `__filename` | Available | Not available (use `import.meta.url`) |

```typescript
// CommonJS
const fs = require("fs");
const { join } = require("path");
module.exports = { myFunction };
module.exports.namedExport = value;

// ESM
import fs from "fs";
import { join } from "path";
export function myFunction() {}
export default class MyClass {}
```

### `require()` Resolution Algorithm

When `require("X")` is called from a file at `/home/user/project/app.js`:

```
1. If X is a core module (e.g., "fs", "path", "http"):
   → Return the core module. DONE.

2. If X begins with "./" or "/" or "../":
   a. LOAD_AS_FILE(resolve(dirname(app.js), X))
      → Try: X, X.js, X.json, X.node
   b. LOAD_AS_DIRECTORY(resolve(dirname(app.js), X))
      → Try: X/package.json → "main" field
      → Try: X/index.js, X/index.json, X/index.node

3. Otherwise (bare specifier like "lodash"):
   LOAD_NODE_MODULES(X, dirname(app.js))
   → Walk up directory tree:
      /home/user/project/node_modules/X
      /home/user/node_modules/X
      /home/node_modules/X
      /node_modules/X
```

**Caching**: `require()` caches modules by their resolved filename. Subsequent `require()` calls return the cached `module.exports` object — the module code does NOT re-execute.

```typescript
// module-a.js
console.log("Module A loaded");
module.exports = { count: 0 };

// main.js
const a1 = require("./module-a"); // prints "Module A loaded"
const a2 = require("./module-a"); // prints NOTHING — cached
a1.count++;
console.log(a2.count); // 1 — same reference, same object
console.log(a1 === a2); // true
```

### ESM: Static Analysis, Tree-Shaking, Top-Level Await

ESM imports are **statically analyzed** — the import/export bindings are determined before any code executes. This enables:

**Tree-shaking**: Bundlers (Webpack, Rollup, esbuild) can statically determine which exports are used and eliminate dead code.

```typescript
// utils.ts — both functions are exported
export function used() { return "I am used"; }
export function unused() { return "I am dead code"; }

// main.ts — only imports `used`
import { used } from "./utils";
console.log(used());
// A bundler can eliminate `unused` from the final output
```

**Top-level `await`** (ESM only): Allows `await` outside of `async` functions. The importing module waits for the awaited promise to resolve.

```typescript
// config.mjs
const response = await fetch("https://api.example.com/config");
export const config = await response.json();

// main.mjs
import { config } from "./config.mjs";
// This line only executes after config.mjs has fully resolved
console.log(config);
```

**Caution**: Top-level `await` blocks the entire module graph that depends on it. Use judiciously in library code.

### Dual Package Hazard

When a package supports both CJS and ESM, it is possible for an application to load **two separate copies** — one via `require()` and one via `import`. If the module has mutable state, this causes subtle bugs.

```
┌──────────────────────────────────────┐
│            Application               │
│                                      │
│  const pkgCJS = require("pkg");      │──→ loads CJS version
│  import pkgESM from "pkg";           │──→ loads ESM version
│                                      │
│  pkgCJS === pkgESM  // FALSE!        │  ← Two separate instances!
└──────────────────────────────────────┘
```

**Mitigations**:
1. **Wrapper approach**: The ESM entry point re-exports from the CJS version, ensuring a single instance.
2. **Stateless design**: Ensure the package has no module-level mutable state.
3. **Conditional exports** with a shared state file.

### `package.json` Exports Field and Conditional Exports

The `"exports"` field (Node.js 12.7+) replaces `"main"` and provides encapsulation — only explicitly exported paths are accessible to consumers.

```json
{
  "name": "my-package",
  "exports": {
    ".": {
      "import": "./dist/esm/index.js",
      "require": "./dist/cjs/index.js",
      "types": "./dist/types/index.d.ts"
    },
    "./utils": {
      "import": "./dist/esm/utils.js",
      "require": "./dist/cjs/utils.js"
    }
  }
}
```

**Conditional exports resolution order** matters — Node.js uses the first matching condition:

```json
{
  "exports": {
    ".": {
      "node": "./dist/node.js",
      "browser": "./dist/browser.js",
      "import": "./dist/esm.js",
      "require": "./dist/cjs.js",
      "default": "./dist/fallback.js"
    }
  }
}
```

**Encapsulation**: With `"exports"` defined, deep imports like `require("my-package/internal/secret")` throw `ERR_PACKAGE_PATH_NOT_EXPORTED`. This is intentional — it allows package authors to refactor internals without breaking consumers.

### Circular Dependencies: CJS vs ESM

**CommonJS**: Returns a **partially filled** `module.exports` at the point of the circular require. This often causes unexpected `undefined` values.

```typescript
// a.js
console.log("a.js: start");
exports.done = false;
const b = require("./b.js");
console.log("a.js: b.done =", b.done);
exports.done = true;
console.log("a.js: end");

// b.js
console.log("b.js: start");
const a = require("./a.js"); // gets PARTIAL exports: { done: false }
console.log("b.js: a.done =", a.done); // false — not yet true!
exports.done = true;
console.log("b.js: end");

// Running a.js outputs:
// a.js: start
// b.js: start
// b.js: a.done = false    ← partial export!
// b.js: end
// a.js: b.done = true
// a.js: end
```

**ESM**: Uses **live bindings** — imports are references to the exporting module's binding, not copies. Circular imports work more predictably, but accessing a binding before it is initialized throws a `ReferenceError`.

```typescript
// a.mjs
import { bValue } from "./b.mjs";
export const aValue = "a";
console.log("a.mjs: bValue =", bValue);

// b.mjs
import { aValue } from "./a.mjs";
export const bValue = "b";
console.log("b.mjs: aValue =", aValue);
// If b.mjs executes first and accesses aValue before a.mjs initializes it,
// it throws: ReferenceError: Cannot access 'aValue' before initialization
```

### `node:` Protocol Prefix

Since Node.js 14.18+, core modules can be imported with the `node:` prefix. This eliminates ambiguity with npm packages that share the same name (e.g., a hypothetical `fs` package on npm).

```typescript
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";

// Some modules are ONLY available with the node: prefix
import test from "node:test"; // built-in test runner (Node.js 18+)
```

---

## 4. Streams & Buffers

### Stream Types

Node.js streams are abstract interfaces for working with streaming data. All streams are instances of `EventEmitter`.

```
┌────────────────────────────────────────────────────────┐
│                   Stream Types                          │
├──────────────┬──────────────┬───────────┬──────────────┤
│   Readable   │   Writable   │   Duplex  │  Transform   │
├──────────────┼──────────────┼───────────┼──────────────┤
│ fs.createRead│ fs.createWrit│ net.Socket│ zlib.createGz│
│ Stream()     │ eStream()    │ TCP socket│ ip()         │
│ http.Incoming│ http.Server  │ is both R │ crypto.create│
│ Message      │ Response     │ and W     │ Cipher()     │
│ process.stdin│ process.stdout│          │ (R→transform→W)│
└──────────────┴──────────────┴───────────┴──────────────┘
```

| Type | Read | Write | Use Case |
|------|------|-------|----------|
| Readable | Yes | No | Source of data (file read, HTTP request body) |
| Writable | No | Yes | Destination of data (file write, HTTP response) |
| Duplex | Yes | Yes | Both directions independently (TCP socket) |
| Transform | Yes | Yes | Modifies data passing through (compression, encryption) |

### Backpressure and `highWaterMark`

Backpressure occurs when a writable stream cannot consume data as fast as a readable stream produces it. Without handling backpressure, data accumulates in memory and can cause OOM crashes.

```
┌──────────┐    data     ┌──────────┐    data     ┌──────────┐
│ Readable │────────────>│ Internal │────────────>│ Writable │
│ (fast)   │             │  Buffer  │             │ (slow)   │
└──────────┘             └──────────┘             └──────────┘
                          highWaterMark
                         (16KB default for
                          streams, 16 objects
                          for objectMode)

When buffer exceeds highWaterMark:
  - readable.push() returns false
  - writable.write() returns false → signal to pause reading
```

**Manual backpressure handling**:

```typescript
import { createReadStream, createWriteStream } from "node:fs";

const readable = createReadStream("large-file.dat");
const writable = createWriteStream("output.dat");

readable.on("data", (chunk: Buffer) => {
  const canContinue = writable.write(chunk);
  if (!canContinue) {
    // Buffer is full — pause reading until writable drains
    readable.pause();
    writable.once("drain", () => {
      readable.resume();
    });
  }
});

readable.on("end", () => {
  writable.end();
});
```

### `pipe()` vs `pipeline()` — Error Handling Differences

**`pipe()`**: Connects readable to writable and handles backpressure automatically. However, it does **NOT** propagate errors or clean up on failure.

```typescript
import { createReadStream, createWriteStream } from "node:fs";

const source = createReadStream("input.txt");
const dest = createWriteStream("output.txt");

// PROBLEM: If source errors, dest is NOT automatically closed
// Memory leak and dangling file descriptor
source.pipe(dest);

// You must manually handle errors on EVERY stream in the chain
source.on("error", (err) => {
  dest.destroy();
  console.error("Source error:", err);
});
dest.on("error", (err) => {
  source.destroy();
  console.error("Dest error:", err);
});
```

**`pipeline()`** (Node.js 10+): The correct way to pipe streams. Propagates errors, destroys all streams on failure, and supports a callback or returns a Promise.

```typescript
import { pipeline } from "node:stream";
import { createReadStream, createWriteStream } from "node:fs";
import { createGzip } from "node:zlib";

// Callback form
pipeline(
  createReadStream("input.txt"),
  createGzip(),
  createWriteStream("output.txt.gz"),
  (err) => {
    if (err) console.error("Pipeline failed:", err);
    else console.log("Pipeline succeeded");
  }
);

// Promise form (preferred in modern code)
import { pipeline as pipelineAsync } from "node:stream/promises";

async function compress(): Promise<void> {
  await pipelineAsync(
    createReadStream("input.txt"),
    createGzip(),
    createWriteStream("output.txt.gz")
  );
  console.log("Compression complete");
}
```

### Buffer Allocation

Buffers are fixed-length sequences of bytes. They are backed by `ArrayBuffer` and are NOT subject to V8 garbage collection — they use memory outside the V8 heap.

```typescript
// SAFE: Allocates zero-filled buffer (slower, but no data leakage)
const safeBuf = Buffer.alloc(1024);

// UNSAFE: Allocates uninitialized memory (faster, may contain old data)
// Use ONLY when you will immediately overwrite all bytes
const unsafeBuf = Buffer.allocUnsafe(1024);

// From string
const strBuf = Buffer.from("Hello, world!", "utf-8");

// From array
const arrBuf = Buffer.from([0x48, 0x65, 0x6c, 0x6c, 0x6f]);
```

| Method | Zero-filled | Speed | Security |
|--------|-------------|-------|----------|
| `Buffer.alloc(size)` | Yes | Slower | Safe — no data leakage |
| `Buffer.allocUnsafe(size)` | No | Faster | Dangerous — may expose old memory |
| `Buffer.allocUnsafeSlow(size)` | No | Faster | No pooling, for long-lived buffers |

**`Buffer.allocUnsafe()` security risk**: The uninitialized memory may contain sensitive data from previous allocations (passwords, keys, other users' data). Never return an `allocUnsafe` buffer without fully writing to it first.

### Streaming Patterns for Large File Processing

**Line-by-line processing without loading entire file into memory**:

```typescript
import { createReadStream } from "node:fs";
import { createInterface } from "node:readline";

async function processLargeFile(filePath: string): Promise<void> {
  const fileStream = createReadStream(filePath);
  const rl = createInterface({
    input: fileStream,
    crlfDelay: Infinity, // treat \r\n as single newline
  });

  let lineCount = 0;
  for await (const line of rl) {
    lineCount++;
    // Process each line without buffering the entire file
    if (line.includes("ERROR")) {
      console.log(`Line ${lineCount}: ${line}`);
    }
  }
  console.log(`Total lines: ${lineCount}`);
}
```

**Custom Transform stream**:

```typescript
import { Transform, TransformCallback } from "node:stream";

class CSVToJSON extends Transform {
  private headers: string[] | null = null;

  constructor() {
    super({ objectMode: true }); // output objects, not buffers
  }

  _transform(
    chunk: Buffer,
    encoding: string,
    callback: TransformCallback
  ): void {
    const line = chunk.toString().trim();
    if (!this.headers) {
      this.headers = line.split(",");
      callback();
      return;
    }
    const values = line.split(",");
    const obj: Record<string, string> = {};
    this.headers.forEach((h, i) => (obj[h] = values[i]));
    callback(null, JSON.stringify(obj) + "\n");
  }
}
```

### Web Streams API vs Node Streams

Node.js 16+ includes the **Web Streams API** (`ReadableStream`, `WritableStream`, `TransformStream`) from the WHATWG standard, providing cross-platform compatibility with browsers.

| Aspect | Node.js Streams | Web Streams |
|--------|----------------|-------------|
| API | Event-based (`on('data')`) | Pull-based (`reader.read()`) |
| Backpressure | Implicit via `highWaterMark` | Explicit via `desiredSize` |
| Error handling | Events (`'error'`) | Promise rejection |
| Ecosystem | Deep Node.js integration | Browser/Deno/Node cross-compat |
| Maturity | Stable, battle-tested | Newer in Node.js, fewer utilities |

```typescript
// Web Streams API in Node.js
import { ReadableStream, WritableStream } from "node:stream/web";

const readable = new ReadableStream({
  start(controller) {
    controller.enqueue("Hello");
    controller.enqueue("World");
    controller.close();
  },
});

const writable = new WritableStream({
  write(chunk) {
    console.log("Received:", chunk);
  },
});

await readable.pipeTo(writable);

// Converting between Node Streams and Web Streams
import { Readable } from "node:stream";
const nodeReadable = Readable.fromWeb(someWebReadableStream);
const webReadable = Readable.toWeb(someNodeReadable);
```

---

## 5. Process Model & libuv

### Architecture Overview

```
┌────────────────────────────────────────────────────────────────┐
│                    Node.js Process                              │
│                                                                 │
│  ┌────────────────────────────────────────────────────────┐    │
│  │                 V8 JavaScript Engine                    │    │
│  │              (single-threaded JS execution)             │    │
│  └────────────────────────┬───────────────────────────────┘    │
│                           │                                     │
│  ┌────────────────────────┴───────────────────────────────┐    │
│  │                 Node.js Bindings (C++)                   │    │
│  │          (bridge between JS and C/C++ code)             │    │
│  └────────────────────────┬───────────────────────────────┘    │
│                           │                                     │
│  ┌────────────────────────┴───────────────────────────────┐    │
│  │                      libuv                              │    │
│  │                                                         │    │
│  │  ┌─────────────┐    ┌──────────────────────────────┐   │    │
│  │  │  Event Loop  │    │      Thread Pool              │   │    │
│  │  │  (1 thread)  │    │  (4 threads by default)       │   │    │
│  │  │              │    │                                │   │    │
│  │  │  epoll/kqueue│    │  - DNS lookups (getaddrinfo)  │   │    │
│  │  │  /IOCP       │    │  - File system operations     │   │    │
│  │  │              │    │  - crypto (pbkdf2, randomBytes)│   │    │
│  │  │  Handles:    │    │  - zlib compression            │   │    │
│  │  │  - TCP/UDP   │    │  - Custom C++ addons           │   │    │
│  │  │  - Pipes     │    │                                │   │    │
│  │  │  - Signals   │    └──────────────────────────────┘   │    │
│  │  │  - Timers    │                                       │    │
│  │  └─────────────┘                                        │    │
│  └─────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
```

### Thread Pool vs OS Async

**Operations using the libuv thread pool** (blocking operations offloaded to background threads):
- File system operations (`fs.*`)
- DNS lookups (`dns.lookup()`, NOT `dns.resolve()`)
- `crypto.pbkdf2()`, `crypto.randomBytes()`, `crypto.scrypt()`
- `zlib` compression/decompression
- Custom C++ addons using `uv_queue_work`

**Operations using OS-level async** (epoll/kqueue/IOCP — no thread pool needed):
- TCP/UDP sockets (`net`, `dgram`)
- HTTP/HTTPS connections
- `dns.resolve()` (uses c-ares library, not thread pool)
- Pipes
- Timers
- Child process signals

```typescript
// This is thread pool bound — limited by UV_THREADPOOL_SIZE
import { pbkdf2 } from "node:crypto";

// If you run 100 of these concurrently, only 4 run at a time (default pool)
for (let i = 0; i < 100; i++) {
  pbkdf2("password", "salt", 100000, 64, "sha512", (err, key) => {
    console.log(`Done: ${i}`);
  });
}

// This is OS-async — no thread pool involved
import { createConnection } from "node:net";
const socket = createConnection({ port: 80, host: "example.com" });
```

**Configuring thread pool size**:

```bash
# Default is 4, maximum is 1024
# MUST be set before any require/import
UV_THREADPOOL_SIZE=16 node app.js
```

**Important**: Increasing the thread pool size is not free — each thread consumes ~1MB stack memory. Profile before changing.

### `child_process` Module

| Method | Shell | Buffered | IPC | Best For |
|--------|-------|----------|-----|----------|
| `spawn` | No (default) | No (stream) | Optional | Long-running processes, large output |
| `execFile` | No | Yes | No | Execute a file without shell overhead |
| `fork` | No | No (stream) | Yes (built-in) | Node.js child processes with IPC |

**Note**: `child_process.exec()` runs commands through a shell and buffers all output. It is convenient for short commands that need shell features (pipes, globbing), but passing unsanitized user input creates a **shell injection vulnerability**. Prefer `spawn` or `execFile` with an arguments array for safety.

```typescript
import { spawn, execFile, fork } from "node:child_process";

// spawn — streaming output, no shell, safe argument passing
const ls = spawn("ls", ["-la", "/tmp"]);
ls.stdout.on("data", (data: Buffer) => console.log(`stdout: ${data}`));
ls.stderr.on("data", (data: Buffer) => console.error(`stderr: ${data}`));
ls.on("close", (code: number | null) => console.log(`exited: ${code}`));

// execFile — buffered output, no shell, safe argument passing
execFile("ls", ["-la", "/tmp"], (err, stdout, stderr) => {
  if (err) throw err;
  console.log(stdout);
});

// fork — Node.js child with IPC channel
// parent.ts
const child = fork("./worker.ts");
child.send({ type: "compute", data: [1, 2, 3] });
child.on("message", (msg: any) => console.log("Result:", msg));

// worker.ts
process.on("message", (msg: any) => {
  const result = msg.data.reduce((a: number, b: number) => a + b, 0);
  process.send!({ type: "result", value: result });
});
```

### `cluster` Module for Multi-Core Utilization

The cluster module creates child processes (workers) that share server ports. The primary process distributes incoming connections to workers using round-robin (default on non-Windows) or OS-level load balancing.

```typescript
import cluster from "node:cluster";
import http from "node:http";
import { availableParallelism } from "node:os";

const numCPUs = availableParallelism();

if (cluster.isPrimary) {
  console.log(`Primary ${process.pid} is running`);

  // Fork workers
  for (let i = 0; i < numCPUs; i++) {
    cluster.fork();
  }

  cluster.on("exit", (worker, code, signal) => {
    console.log(`Worker ${worker.process.pid} died (${signal || code})`);
    // Restart dead workers
    cluster.fork();
  });
} else {
  // Workers share the same TCP port
  http.createServer((req, res) => {
    res.writeHead(200);
    res.end(`Handled by worker ${process.pid}\n`);
  }).listen(8000);

  console.log(`Worker ${process.pid} started`);
}
```

**Cluster architecture**:

```
┌──────────────────────────────────────────┐
│              Primary Process              │
│                                          │
│   Listens on port 8000                   │
│   Distributes connections (round-robin)  │
│                                          │
│   ┌──────────┐  ┌──────────┐            │
│   │ Worker 1 │  │ Worker 2 │  ...       │
│   │ PID 1234 │  │ PID 1235 │            │
│   │ (fork)   │  │ (fork)   │            │
│   │          │  │          │            │
│   │ Own V8   │  │ Own V8   │            │
│   │ Own heap │  │ Own heap │            │
│   │ Own loop │  │ Own loop │            │
│   └──────────┘  └──────────┘            │
│                                          │
│   IPC channels for message passing       │
│   (no shared memory by default)          │
└──────────────────────────────────────────┘
```

**Cluster vs `worker_threads`**:

| Aspect | `cluster` | `worker_threads` |
|--------|-----------|-------------------|
| Memory | Separate V8 heap per worker | Separate V8 heap, but can share `SharedArrayBuffer` |
| Communication | IPC (serialized messages) | IPC + `SharedArrayBuffer` + `Atomics` |
| Use case | Multi-process HTTP servers | CPU-intensive tasks (image processing, crypto) |
| Overhead | High (full process fork) | Lower (threads within same process) |

### IPC Channels Between Processes

```typescript
// Primary
import cluster from "node:cluster";

if (cluster.isPrimary) {
  const worker = cluster.fork();

  // Send message to worker
  worker.send({ type: "config", port: 3000 });

  // Receive message from worker
  worker.on("message", (msg: any) => {
    if (msg.type === "ready") {
      console.log(`Worker ${worker.process.pid} is ready`);
    }
  });
} else {
  // Worker
  process.on("message", (msg: any) => {
    if (msg.type === "config") {
      // Start server on configured port
      startServer(msg.port);
      process.send!({ type: "ready" });
    }
  });
}
```

---

## 6. Error Handling

### Error-First Callbacks

The Node.js convention for asynchronous operations: the first argument of a callback is reserved for an error object (or `null` on success).

```typescript
import { readFile } from "node:fs";

readFile("/path/to/file", "utf-8", (err: NodeJS.ErrnoException | null, data?: string) => {
  if (err) {
    // ALWAYS check err first
    if (err.code === "ENOENT") {
      console.error("File not found");
    } else {
      throw err; // unexpected error
    }
    return;
  }
  console.log(data);
});
```

**Anti-pattern**: Ignoring the error argument. Every callback MUST check `err`. Ignoring it leads to silent failures.

### Unhandled Rejections and `unhandledRejection`

When a Promise is rejected and no `.catch()` or `try/catch` handles it, Node.js emits an `unhandledRejection` event. Since Node.js 15+, unhandled rejections **terminate the process by default** (`--unhandled-rejections=throw`).

```typescript
// PROBLEM: Unhandled rejection — will crash the process (Node 15+)
async function riskyOperation(): Promise<void> {
  throw new Error("Something failed");
}
riskyOperation(); // no .catch(), no await-in-try/catch

// SOLUTION 1: Catch at call site
riskyOperation().catch((err) => console.error("Caught:", err));

// SOLUTION 2: Global safety net (log and decide)
process.on("unhandledRejection", (reason: unknown, promise: Promise<unknown>) => {
  console.error("Unhandled Rejection at:", promise, "reason:", reason);
  // In production: log, track metrics, and decide whether to shut down
  // Do NOT swallow silently — it hides bugs
});
```

### `uncaughtException` — When to Use and When NOT to Use

`uncaughtException` fires when a synchronous error propagates to the event loop uncaught. The process is in an **undefined state** after this event.

```typescript
process.on("uncaughtException", (err: Error, origin: string) => {
  console.error(`Uncaught Exception (${origin}):`, err);

  // DO: Log the error, flush logs, notify monitoring
  // DO: Attempt graceful shutdown with a timeout
  // DO NOT: Continue normal operation — state may be corrupted

  // Graceful shutdown
  server.close(() => {
    process.exit(1);
  });

  // Force exit if graceful shutdown hangs
  setTimeout(() => {
    process.exit(1);
  }, 5000).unref();
});
```

**When to use**: As a last-resort safety net to log and perform cleanup before exiting.

**When NOT to use**: As a general error handling mechanism. After an uncaught exception, your application state is unreliable — open database transactions may be partially committed, file handles may be dangling, in-flight requests are abandoned.

### Operational vs Programmer Errors

| Type | Definition | Examples | How to Handle |
|------|-----------|----------|---------------|
| **Operational** | Runtime problems in correctly written code | Network timeout, file not found, DB connection refused, invalid user input | Handle gracefully: retry, fallback, return error to client |
| **Programmer** | Bugs in the code | `TypeError`, reading property of `undefined`, off-by-one, wrong argument type | Fix the code. Crash immediately (fail fast). |

```typescript
// Operational error — handle gracefully
async function fetchUser(id: string): Promise<User> {
  try {
    const response = await fetch(`/api/users/${id}`);
    if (!response.ok) {
      // Operational: API returned error
      throw new AppError(`User not found: ${id}`, "USER_NOT_FOUND", 404);
    }
    return response.json();
  } catch (err) {
    if (err instanceof AppError) throw err; // re-throw known errors
    // Operational: network failure
    throw new AppError("Service unavailable", "NETWORK_ERROR", 503, err);
  }
}

// Custom error class for operational errors
class AppError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly statusCode: number,
    public readonly cause?: unknown
  ) {
    super(message);
    this.name = "AppError";
  }

  get isOperational(): boolean {
    return true;
  }
}
```

### Error Propagation in Streams

Errors in streams do NOT propagate through `pipe()` chains. Each stream must have its own error handler, or use `pipeline()`.

```typescript
import { Transform } from "node:stream";
import { pipeline as pipelineAsync } from "node:stream/promises";

// Custom transform that may error
class JSONParser extends Transform {
  _transform(chunk: Buffer, encoding: string, callback: Function): void {
    try {
      const parsed = JSON.parse(chunk.toString());
      callback(null, parsed);
    } catch (err) {
      // Pass error to callback — this emits 'error' on the stream
      callback(new Error(`Invalid JSON: ${(err as Error).message}`));
    }
  }
}

// CORRECT: Use pipeline for proper error propagation
try {
  await pipelineAsync(source, new JSONParser(), destination);
} catch (err) {
  // Catches errors from ANY stream in the chain
  console.error("Stream processing failed:", err);
}
```

### Graceful Shutdown Pattern

A production Node.js server must handle shutdown signals cleanly — finish in-flight requests, close database connections, and flush logs.

```typescript
import http from "node:http";

const server = http.createServer(handler);
let isShuttingDown = false;

function gracefulShutdown(signal: string): void {
  if (isShuttingDown) return; // prevent double-shutdown
  isShuttingDown = true;
  console.log(`Received ${signal}. Starting graceful shutdown...`);

  // 1. Stop accepting new connections
  server.close((err) => {
    if (err) {
      console.error("Error closing server:", err);
      process.exit(1);
    }

    // 2. Close database connections, flush queues
    Promise.all([
      database.close(),
      messageQueue.flush(),
      logger.flush(),
    ])
      .then(() => {
        console.log("Graceful shutdown complete.");
        process.exit(0);
      })
      .catch((closeErr) => {
        console.error("Error during cleanup:", closeErr);
        process.exit(1);
      });
  });

  // 3. Force exit if graceful shutdown takes too long
  setTimeout(() => {
    console.error("Forced shutdown — graceful shutdown timed out");
    process.exit(1);
  }, 30_000).unref();
}

// Handle termination signals
process.on("SIGTERM", () => gracefulShutdown("SIGTERM")); // Docker/K8s sends this
process.on("SIGINT", () => gracefulShutdown("SIGINT"));   // Ctrl+C

// Middleware to reject new requests during shutdown
function handler(req: http.IncomingMessage, res: http.ServerResponse): void {
  if (isShuttingDown) {
    res.writeHead(503, { "Connection": "close" });
    res.end("Service Unavailable — shutting down");
    return;
  }
  // ... normal request handling
}
```

---

## 7. Node.js Globals & Built-in APIs

### `process` Object

The `process` object is a global that provides information about and control over the current Node.js process.

```typescript
// Environment variables
const port = process.env.PORT || 3000;
const nodeEnv = process.env.NODE_ENV || "development";

// Command-line arguments
// node app.js --port 3000 --verbose
// process.argv = ["node", "app.js", "--port", "3000", "--verbose"]
const args = process.argv.slice(2); // drop "node" and script path

// Exit codes
process.exit(0);  // success
process.exit(1);  // generic error
// Preferred: set exit code without forcing immediate exit
process.exitCode = 1;

// Signals
process.on("SIGTERM", () => { /* graceful shutdown */ });
process.on("SIGINT", () => { /* Ctrl+C */ });
process.on("SIGHUP", () => { /* reload config */ });

// Memory usage
const mem = process.memoryUsage();
// {
//   rss: 30_000_000,        ← Resident Set Size (total allocated)
//   heapTotal: 7_000_000,   ← V8 heap allocated
//   heapUsed: 5_000_000,    ← V8 heap actually used
//   external: 1_000_000,    ← C++ objects bound to JS (Buffers)
//   arrayBuffers: 500_000   ← SharedArrayBuffer + ArrayBuffer
// }

// High-resolution time (for benchmarking)
const start = process.hrtime.bigint(); // nanosecond precision
doExpensiveWork();
const end = process.hrtime.bigint();
console.log(`Took ${(end - start) / 1_000_000n}ms`);

// process.nextTick — schedule callback before I/O
process.nextTick(() => {
  // Executes after current operation, before any I/O
});
```

### `crypto` Module Essentials

```typescript
import {
  createHash,
  createHmac,
  randomBytes,
  randomUUID,
  scrypt,
  timingSafeEqual,
  createCipheriv,
} from "node:crypto";

// Hashing
const hash = createHash("sha256").update("password").digest("hex");

// HMAC (Hash-based Message Authentication Code)
const hmac = createHmac("sha256", "secret-key").update("message").digest("hex");

// Random values
const token = randomBytes(32).toString("hex"); // 64-char hex string
const uuid = randomUUID(); // v4 UUID

// Password hashing with scrypt (preferred over pbkdf2)
async function hashPassword(password: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const salt = randomBytes(16).toString("hex");
    scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) reject(err);
      resolve(`${salt}:${derivedKey.toString("hex")}`);
    });
  });
}

async function verifyPassword(password: string, stored: string): Promise<boolean> {
  return new Promise((resolve, reject) => {
    const [salt, storedHash] = stored.split(":");
    scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) reject(err);
      // IMPORTANT: Use timing-safe comparison to prevent timing attacks
      resolve(timingSafeEqual(
        Buffer.from(storedHash, "hex"),
        derivedKey
      ));
    });
  });
}

// AES-256-GCM encryption
function encrypt(
  text: string,
  key: Buffer
): { encrypted: string; iv: string; tag: string } {
  const iv = randomBytes(12); // 96-bit IV for GCM
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  let encrypted = cipher.update(text, "utf-8", "hex");
  encrypted += cipher.final("hex");
  const tag = cipher.getAuthTag().toString("hex");
  return { encrypted, iv: iv.toString("hex"), tag };
}
```

### `fs/promises` vs Callback `fs`

```typescript
// MODERN: fs/promises (use this)
import { readFile, writeFile, mkdir, stat, readdir } from "node:fs/promises";

async function processFile(): Promise<void> {
  try {
    const content = await readFile("./data.json", "utf-8");
    const data = JSON.parse(content);
    data.updatedAt = new Date().toISOString();
    await writeFile("./data.json", JSON.stringify(data, null, 2));
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === "ENOENT") {
      console.log("File does not exist");
    } else {
      throw err;
    }
  }
}

// Check if file/dir exists (the modern way)
import { access, constants } from "node:fs/promises";

async function fileExists(path: string): Promise<boolean> {
  try {
    await access(path, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

// Recursively read directory
async function walkDir(dir: string): Promise<string[]> {
  const files: string[] = [];
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const fullPath = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      files.push(...await walkDir(fullPath));
    } else {
      files.push(fullPath);
    }
  }
  return files;
}

// LEGACY: Callback style (avoid in new code)
import { readFile as readFileCb } from "node:fs";
readFileCb("./data.json", "utf-8", (err, data) => {
  if (err) throw err;
  console.log(data);
});

// NOTE: fs.existsSync() is acceptable in startup/config code
// where blocking the event loop is fine
import { existsSync } from "node:fs";
if (existsSync("./config.json")) {
  // load config during startup
}
```

### `path` Module Cross-Platform Gotchas

```typescript
import path from "node:path";

// GOOD: Use path.join() — handles OS-specific separators
const filePath = path.join("users", "data", "file.txt");
// Linux/Mac: "users/data/file.txt"
// Windows:   "users\\data\\file.txt"

// GOOD: Use path.resolve() for absolute paths
const absPath = path.resolve("src", "index.ts");
// Resolves relative to cwd: "/home/user/project/src/index.ts"

// Parsing paths
const parsed = path.parse("/home/user/file.txt");
// { root: '/', dir: '/home/user', base: 'file.txt', ext: '.txt', name: 'file' }

// Common gotcha: path.join vs string concatenation
// BAD — breaks on Windows, doesn't normalize
const bad = "users/" + name + "/data";
// GOOD — cross-platform and normalizes
const good = path.join("users", name, "data");

// ESM equivalent of __dirname and __filename
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

// path.resolve() vs path.join()
path.join("/a", "b", "..", "c");     // "/a/c"     — just concatenation + normalize
path.resolve("/a", "b", "..", "c");  // "/a/c"     — same here, but...
path.resolve("a", "b");             // "/cwd/a/b"  — resolve makes it absolute
path.join("a", "b");                // "a/b"       — join keeps it relative
```

### `url` and `URLSearchParams`

```typescript
// Modern WHATWG URL API (preferred)
const myUrl = new URL("https://example.com:8080/path?key=value&lang=en#section");

console.log(myUrl.hostname);       // "example.com"
console.log(myUrl.port);           // "8080"
console.log(myUrl.pathname);       // "/path"
console.log(myUrl.hash);           // "#section"
console.log(myUrl.searchParams);   // URLSearchParams { 'key' => 'value', 'lang' => 'en' }

// URLSearchParams — easy query string manipulation
const params = new URLSearchParams({ page: "1", limit: "20", sort: "name" });
params.append("filter", "active");
params.set("page", "2");
params.delete("sort");

console.log(params.toString()); // "page=2&limit=20&filter=active"

// Iterating
for (const [key, value] of params) {
  console.log(`${key}: ${value}`);
}

// Constructing URLs safely
const apiUrl = new URL("/api/users", "https://api.example.com");
apiUrl.searchParams.set("id", userId); // automatically encoded
// No need to worry about URL injection

// Legacy url.parse — avoid in new code
import { parse } from "node:url"; // deprecated
```

### `AbortController` and `AbortSignal` for Cancellation

`AbortController` provides a standard way to cancel asynchronous operations — it works with `fetch`, `fs/promises`, `stream`, `setTimeout`, and many other APIs.

```typescript
// Basic cancellation
const controller = new AbortController();
const { signal } = controller;

// Cancel after 5 seconds
setTimeout(() => controller.abort(), 5000);

try {
  const response = await fetch("https://slow-api.example.com/data", { signal });
  const data = await response.json();
  console.log(data);
} catch (err) {
  if ((err as Error).name === "AbortError") {
    console.log("Request was cancelled");
  } else {
    throw err;
  }
}

// AbortSignal.timeout() — built-in timeout (Node.js 17.3+)
try {
  const response = await fetch("https://api.example.com/data", {
    signal: AbortSignal.timeout(5000), // auto-abort after 5s
  });
} catch (err) {
  if ((err as Error).name === "TimeoutError") {
    console.log("Request timed out");
  }
}

// Cancellable file operations
import { readFile } from "node:fs/promises";

const ac = new AbortController();
setTimeout(() => ac.abort(), 1000);

try {
  const content = await readFile("huge-file.dat", { signal: ac.signal });
} catch (err) {
  if ((err as Error).name === "AbortError") {
    console.log("File read was cancelled");
  }
}

// Combining multiple signals (Node.js 20+)
const userCancel = new AbortController();
const timeoutSignal = AbortSignal.timeout(10_000);
const combinedSignal = AbortSignal.any([userCancel.signal, timeoutSignal]);

await fetch(url, { signal: combinedSignal });

// Cancellable custom async functions
async function pollWithCancellation(
  url: string,
  intervalMs: number,
  signal: AbortSignal
): Promise<void> {
  while (!signal.aborted) {
    const response = await fetch(url, { signal });
    const data = await response.json();
    console.log("Poll result:", data);

    // Cancellable sleep
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(resolve, intervalMs);
      signal.addEventListener("abort", () => {
        clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      }, { once: true });
    });
  }
}
```

### `node:test` — Built-in Test Runner

Since Node.js 18, there is a built-in test runner that requires no external dependencies:

```typescript
import { describe, it, beforeEach, mock } from "node:test";
import assert from "node:assert/strict";

describe("Calculator", () => {
  let calc: Calculator;

  beforeEach(() => {
    calc = new Calculator();
  });

  it("should add two numbers", () => {
    assert.strictEqual(calc.add(2, 3), 5);
  });

  it("should throw on division by zero", () => {
    assert.throws(() => calc.divide(1, 0), {
      message: "Division by zero",
    });
  });

  // Built-in mocking
  it("should mock dependencies", () => {
    const mockFetch = mock.fn(async () => ({
      json: async () => ({ data: "mocked" }),
    }));

    // mock.fn tracks calls
    mockFetch();
    assert.strictEqual(mockFetch.mock.calls.length, 1);
  });
});
```

### Common Senior-Level Interview Questions

**Q: Why does `setTimeout(fn, 0)` not execute immediately?**
Because `setTimeout` schedules the callback in the timers phase of the next event loop iteration. Even with a 0ms delay, the callback must wait for the current synchronous code to finish, all microtasks to drain, and the event loop to reach the timers phase. The actual minimum delay is typically 1ms (clamped by the system timer resolution).

**Q: How would you debug a memory leak in a Node.js application?**
1. Use `--inspect` flag and Chrome DevTools heap snapshots
2. Take multiple heap snapshots over time and compare them (three-snapshot technique)
3. Look for growing arrays, maps, or event listener counts
4. Use `process.memoryUsage()` to track RSS and heap growth over time
5. Check for: unclosed streams/connections, growing caches without eviction, event listeners not removed, closures capturing large objects, global variable accumulation

**Q: Explain why `require()` is synchronous but Node.js is "non-blocking."**
`require()` is intentionally synchronous because module loading happens at startup before the server begins handling requests. Once the application is initialized and the event loop is running, all I/O operations are non-blocking. The synchronous nature of `require()` is a design choice — it simplifies the module dependency graph and ensures modules are fully loaded before dependent code executes.

**Q: What is the difference between `spawn` and `execFile` in `child_process`?**
`spawn` streams stdout/stderr as data arrives and does not use a shell — it is suitable for long-running processes with large output. `execFile` buffers the entire output in memory (default 1MB, configurable via `maxBuffer`) and also does not use a shell — suitable for short-lived commands where you want the full output at once. Both accept arguments as arrays, which prevents shell injection.

**Q: How does Node.js handle 10,000 concurrent connections with a single thread?**
Node.js delegates I/O to the OS kernel (epoll on Linux, kqueue on macOS, IOCP on Windows). The kernel notifies Node.js when data is ready, and Node.js processes callbacks one at a time on the event loop. Because JavaScript callbacks are typically short-lived (just processing data and initiating the next I/O), a single thread can multiplex thousands of connections. The bottleneck shifts from thread count to callback execution time — if any callback blocks the event loop (CPU-intensive work), all connections are affected.

**Q: What happens if you `await` inside a `forEach`?**

```typescript
// BROKEN: forEach does not await — all iterations fire concurrently
const ids = [1, 2, 3];
ids.forEach(async (id) => {
  const data = await fetchData(id); // these run in parallel, uncontrolled
  console.log(data);
});
console.log("Done"); // prints BEFORE any fetchData completes

// FIX: Use for...of for sequential execution
for (const id of ids) {
  const data = await fetchData(id);
  console.log(data);
}

// FIX: Use Promise.all for controlled parallel execution
const results = await Promise.all(ids.map((id) => fetchData(id)));
```
