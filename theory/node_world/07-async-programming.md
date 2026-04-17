# Async Programming & Concurrency — Senior Engineer Interview Preparation

---

## 1. Promises Deep Dive

### Promise States

A Promise exists in exactly one of three states at any given time. Transitions are **one-way and irreversible** — once settled, a Promise's state and value are frozen forever.

```
                    resolve(value)
    ┌─────────┐ ──────────────────► ┌───────────┐
    │ PENDING │                     │ FULFILLED │  ── .then(onFulfilled)
    │         │                     │ value: T  │
    │ (init)  │                     └───────────┘
    │         │
    │         │    reject(reason)
    │         │ ──────────────────► ┌───────────┐
    └─────────┘                     │ REJECTED  │  ── .then(_, onRejected)
                                    │ reason: E │      .catch(onRejected)
                                    └───────────┘

    ┌─────────────────────────────────────────────────────┐
    │  SETTLED = FULFILLED or REJECTED                    │
    │  Once settled, state and value/reason are immutable │
    └─────────────────────────────────────────────────────┘
```

| State | `value` / `reason` | `.then()` callback | Settled? |
|-------|--------------------|--------------------|----------|
| Pending | undefined | Queued for later execution | No |
| Fulfilled | The resolved value | `onFulfilled` called with value | Yes |
| Rejected | The rejection reason | `onRejected` called with reason | Yes |

### Promise Resolution Procedure

The resolution procedure is what happens when you call `resolve(x)` inside a Promise constructor. It is **not** simply "set value to x" — there is a recursive unwrapping algorithm:

```typescript
// resolve(x) follows these rules:
// 1. If x is the promise itself → reject with TypeError (circular reference)
// 2. If x is a Promise → adopt its state (wait for it)
// 3. If x is a thenable (has .then method) → call x.then(resolve, reject)
// 4. Otherwise → fulfill with x

// GOTCHA: resolve() does NOT always fulfill!
const p = new Promise<string>((resolve) => {
  resolve(Promise.reject("oops")); // p becomes REJECTED, not fulfilled
});

p.catch(err => console.log(err)); // "oops"

// Double-wrapping is automatically flattened:
const inner = Promise.resolve(42);
const outer = Promise.resolve(inner);
// outer is fulfilled with 42, NOT fulfilled with a Promise<number>
console.log(await outer); // 42, not Promise { 42 }
```

**Interview gotcha**: `resolve()` and `fulfill` are NOT synonyms. Calling `resolve(anotherPromise)` makes the current promise "follow" the other promise. Only `resolve(nonThenable)` actually fulfills.

### Promise Combinators

```typescript
const p1 = fetch("/api/users");
const p2 = fetch("/api/orders");
const p3 = fetch("/api/products");
const pFail = Promise.reject(new Error("fail"));
```

| Combinator | Short-circuits? | Resolves when | Rejects when | Return type |
|-----------|----------------|---------------|--------------|-------------|
| `Promise.all([p1,p2,p3])` | Yes (on first rejection) | All fulfill | Any one rejects | `Promise<T[]>` |
| `Promise.allSettled([p1,p2,p3])` | Never | All settle | Never rejects | `Promise<PromiseSettledResult<T>[]>` |
| `Promise.race([p1,p2,p3])` | Yes (on first settlement) | First fulfills | First rejects | `Promise<T>` |
| `Promise.any([p1,p2,p3])` | Yes (on first fulfillment) | Any one fulfills | All reject | `Promise<T>` |

```typescript
// Promise.all — fails fast on first rejection
try {
  const [users, orders, products] = await Promise.all([p1, p2, p3]);
} catch (err) {
  // Only get the FIRST rejection — other results are lost
}

// Promise.allSettled — never rejects, gives status of every promise
const results = await Promise.allSettled([p1, p2, pFail]);
// [
//   { status: "fulfilled", value: Response },
//   { status: "fulfilled", value: Response },
//   { status: "rejected",  reason: Error("fail") }
// ]

// Promise.race — first to settle wins (fulfilled OR rejected)
const winner = await Promise.race([
  fetch("/api/fast"),
  new Promise((_, reject) => setTimeout(() => reject(new Error("timeout")), 5000))
]);

// Promise.any — first to FULFILL wins; only rejects if ALL reject
try {
  const fastest = await Promise.any([p1, p2, pFail]);
  // pFail is ignored because p1 or p2 fulfill
} catch (err) {
  // AggregateError — only thrown if EVERY promise rejected
  console.log(err.errors); // array of all rejection reasons
}
```

### Promise Chaining vs Nesting

```typescript
// WRONG — nested promises (callback hell with promises)
function fetchUserData(userId: string) {
  return fetch(`/users/${userId}`).then(res => {
    return res.json().then(user => {
      return fetch(`/orders/${user.orderId}`).then(res => {
        return res.json().then(order => {
          return { user, order }; // deeply nested
        });
      });
    });
  });
}

// CORRECT — flat chaining (each .then returns a promise)
function fetchUserData(userId: string) {
  let savedUser: User;
  return fetch(`/users/${userId}`)
    .then(res => res.json())
    .then(user => {
      savedUser = user;
      return fetch(`/orders/${user.orderId}`);
    })
    .then(res => res.json())
    .then(order => ({ user: savedUser, order }));
}

// BEST — async/await (covered in section 2)
async function fetchUserData(userId: string) {
  const userRes = await fetch(`/users/${userId}`);
  const user = await userRes.json();
  const orderRes = await fetch(`/orders/${user.orderId}`);
  const order = await orderRes.json();
  return { user, order };
}
```

### Common Mistakes

```typescript
// MISTAKE 1: Forgetting to return in .then()
fetch("/api/data")
  .then(res => {
    res.json(); // NOT returned — .then() receives undefined
  })
  .then(data => {
    console.log(data); // undefined!
  });

// MISTAKE 2: Swallowing errors — no .catch() at the end
fetch("/api/data")
  .then(res => res.json())
  .then(data => process(data));
// If any step throws, the rejection is silently lost
// Node.js will emit UnhandledPromiseRejection

// MISTAKE 3: Using Promise constructor unnecessarily
// BAD — explicit construction anti-pattern
function fetchData() {
  return new Promise((resolve, reject) => {
    fetch("/api/data")
      .then(res => res.json())
      .then(data => resolve(data))
      .catch(err => reject(err));
  });
}
// GOOD — just return the promise chain
function fetchData() {
  return fetch("/api/data").then(res => res.json());
}

// MISTAKE 4: Using .then() and .catch() on the same promise
const p = riskyOperation();
p.then(handleSuccess);   // branch 1
p.catch(handleError);    // branch 2 — separate chain!
// These are TWO separate chains from the same promise.
// If handleSuccess throws, handleError will NOT catch it.

// CORRECT — single chain
riskyOperation()
  .then(handleSuccess)
  .catch(handleError); // catches errors from riskyOperation AND handleSuccess
```

### Microtask Queue Behavior

Promise callbacks execute as **microtasks**, which have higher priority than macrotasks (setTimeout, setInterval, I/O callbacks). The microtask queue is fully drained after each macrotask before the next macrotask runs.

```typescript
// INTERVIEW CLASSIC: What is the output order?
console.log("1 — sync");

setTimeout(() => console.log("2 — macrotask"), 0);

Promise.resolve()
  .then(() => console.log("3 — microtask"))
  .then(() => console.log("4 — microtask (chained)"));

queueMicrotask(() => console.log("5 — microtask (explicit)"));

console.log("6 — sync");

// Output:
// 1 — sync
// 6 — sync
// 3 — microtask
// 5 — microtask (explicit)
// 4 — microtask (chained)
// 2 — macrotask
```

```
Execution order visualization:

┌──────────────────────────────────────────────────┐
│                  Call Stack                       │
│  log("1"), setTimeout(), Promise.resolve(),      │
│  queueMicrotask(), log("6")                      │
└──────────────┬───────────────────────────────────┘
               │ (call stack empty)
               ▼
┌──────────────────────────────────────────────────┐
│              Microtask Queue                     │
│  [.then → log("3")] [queueMicrotask → log("5")] │
│  then → [.then → log("4")]  ← added after "3"   │
└──────────────┬───────────────────────────────────┘
               │ (microtask queue drained)
               ▼
┌──────────────────────────────────────────────────┐
│              Macrotask Queue                     │
│  [setTimeout → log("2")]                         │
└──────────────────────────────────────────────────┘
```

**Key rule**: Microtasks spawned by microtasks are drained in the same tick. A recursive microtask can starve the macrotask queue indefinitely.

```typescript
// DANGER: Infinite microtask loop — starves macrotask queue, blocks I/O
function recurse(): void {
  Promise.resolve().then(recurse);
}
recurse(); // setTimeout callbacks will NEVER run
```

### Promise.withResolvers() (ES2024)

Previously you needed the "deferred pattern" with leaked variables. `Promise.withResolvers()` makes it clean:

```typescript
// OLD PATTERN — leaking resolve/reject out of constructor
let resolve!: (value: string) => void;
let reject!: (reason: Error) => void;
const promise = new Promise<string>((res, rej) => {
  resolve = res;
  reject = rej;
});

// NEW — Promise.withResolvers() (ES2024 / Node 22+)
const { promise, resolve, reject } = Promise.withResolvers<string>();

// Real use case: wrapping event-based APIs
function waitForEvent(emitter: EventEmitter, event: string): Promise<unknown> {
  const { promise, resolve, reject } = Promise.withResolvers<unknown>();
  emitter.once(event, resolve);
  emitter.once("error", reject);
  return promise;
}
```

---

## 2. async/await Internals

### How async/await Desugars

`async/await` is syntactic sugar over generators + promises. The engine transforms async functions into a state machine:

```typescript
// What you write:
async function fetchUser(id: string): Promise<User> {
  const response = await fetch(`/users/${id}`);
  const user = await response.json();
  return user;
}

// Conceptual desugaring (simplified):
function fetchUser(id: string): Promise<User> {
  return new Promise((resolve, reject) => {
    const gen = (function* () {
      try {
        const response = yield fetch(`/users/${id}`);
        const user = yield response.json();
        resolve(user);
      } catch (err) {
        reject(err);
      }
    })();

    function step(nextFn: () => IteratorResult<any>) {
      let result: IteratorResult<any>;
      try {
        result = nextFn();
      } catch (err) {
        reject(err);
        return;
      }
      if (result.done) return;
      Promise.resolve(result.value).then(
        val => step(() => gen.next(val)),
        err => step(() => gen.throw(err))
      );
    }

    step(() => gen.next());
  });
}
```

**Key insight**: Each `await` is a suspension point. The function yields control back to the caller, and the remainder is scheduled as a microtask when the awaited promise settles.

### Error Handling: try/catch vs .catch()

```typescript
// APPROACH 1: try/catch — preferred for sequential code
async function processOrder(orderId: string): Promise<void> {
  try {
    const order = await getOrder(orderId);
    const payment = await chargePayment(order);
    await sendConfirmation(order, payment);
  } catch (err) {
    // Catches errors from ANY of the three awaits
    if (err instanceof PaymentError) {
      await refund(orderId);
    }
    throw err; // re-throw to propagate
  } finally {
    // Cleanup runs regardless of success/failure
    await releaseInventoryLock(orderId);
  }
}

// APPROACH 2: .catch() on individual promises — for selective handling
async function loadDashboard(): Promise<Dashboard> {
  const user = await getUser().catch(() => defaultUser);
  const prefs = await getPreferences().catch(() => defaultPrefs);
  // Each failure is independently recoverable
  return buildDashboard(user, prefs);
}

// APPROACH 3: Go-style tuple pattern — popular in codebases avoiding try/catch
type Result<T> = [null, T] | [Error, null];

async function safeAsync<T>(promise: Promise<T>): Promise<Result<T>> {
  try {
    const data = await promise;
    return [null, data];
  } catch (err) {
    return [err instanceof Error ? err : new Error(String(err)), null];
  }
}

// Usage
const [err, user] = await safeAsync(getUser(id));
if (err) {
  console.error("Failed to fetch user:", err);
  return;
}
console.log(user.name); // TypeScript knows user is non-null here
```

### Sequential vs Parallel Execution

```typescript
// SEQUENTIAL — each await waits for the previous to complete
// Total time = sum of all individual times
async function sequential(): Promise<void> {
  const users = await fetchUsers();     // 200ms
  const orders = await fetchOrders();   // 300ms
  const products = await fetchProducts(); // 150ms
  // Total: ~650ms
}

// PARALLEL — start all at once, await all together
// Total time = max of all individual times
async function parallel(): Promise<void> {
  const [users, orders, products] = await Promise.all([
    fetchUsers(),     // 200ms ┐
    fetchOrders(),    // 300ms ├── all run concurrently
    fetchProducts(),  // 150ms ┘
  ]);
  // Total: ~300ms
}

// PARALLEL WITH INDEPENDENT ERROR HANDLING
async function parallelSafe(): Promise<void> {
  const results = await Promise.allSettled([
    fetchUsers(),
    fetchOrders(),
    fetchProducts(),
  ]);

  const users = results[0].status === "fulfilled" ? results[0].value : [];
  const orders = results[1].status === "fulfilled" ? results[1].value : [];
  const products = results[2].status === "fulfilled" ? results[2].value : [];
}

// CONTROLLED CONCURRENCY — limit parallel operations
async function parallelBatched<T>(
  items: string[],
  fn: (item: string) => Promise<T>,
  concurrency: number
): Promise<T[]> {
  const results: T[] = [];
  for (let i = 0; i < items.length; i += concurrency) {
    const batch = items.slice(i, i + concurrency);
    const batchResults = await Promise.all(batch.map(fn));
    results.push(...batchResults);
  }
  return results;
}
```

```
Sequential vs Parallel timeline:

SEQUENTIAL:
  t=0ms       t=200ms     t=500ms     t=650ms
  |──users───|──orders───|──products──|
              ↑ blocked   ↑ blocked

PARALLEL:
  t=0ms       t=150ms  t=200ms  t=300ms
  |──users─────────────|        |
  |──orders────────────────────|  ← total time
  |──products──|               |

BATCHED (concurrency=2):
  t=0ms       t=200ms     t=300ms  t=450ms
  |──users──────────────|         |
  |──orders─────────────────────|  batch 1
                                |──products──|  batch 2
```

### for await...of for Async Iteration

```typescript
// Async iterables implement [Symbol.asyncIterator]()
// which returns { next(): Promise<IteratorResult<T>> }

// Reading a stream line by line
import { createReadStream } from "fs";
import { createInterface } from "readline";

async function processLargeFile(path: string): Promise<void> {
  const rl = createInterface({
    input: createReadStream(path),
    crlfDelay: Infinity,
  });

  for await (const line of rl) {
    await processLine(line); // backpressure: next line read only after processing
  }
}

// Paginated API consumption
async function* fetchAllPages<T>(baseUrl: string): AsyncGenerator<T[]> {
  let cursor: string | null = null;
  do {
    const url = cursor ? `${baseUrl}?cursor=${cursor}` : baseUrl;
    const res = await fetch(url);
    const data = await res.json();
    cursor = data.nextCursor;
    yield data.items;
  } while (cursor);
}

for await (const page of fetchAllPages<User>("/api/users")) {
  for (const user of page) {
    console.log(user.name);
  }
}
```

### Top-Level Await

Top-level `await` is available in **ES modules** (not CommonJS). It turns the module itself into an async function.

```typescript
// config.ts — ES module with top-level await
const response = await fetch(process.env.CONFIG_URL!);
export const config = await response.json();

// Importing modules use top-level await — the import blocks until the
// awaited promise resolves:
// main.ts
import { config } from "./config.js"; // blocks until config is loaded
console.log(config.dbHost);
```

**Implications and gotchas**:
- Modules importing a top-level-await module will wait for it to complete before executing
- Creates a dependency graph where module initialization is async
- Can introduce subtle startup delays — a slow fetch in a deeply imported module blocks the entire import chain
- Deadlock risk if two modules with top-level await circularly depend on each other
- CommonJS `require()` cannot import an ES module that uses top-level await

### Common Anti-Patterns

```typescript
// ANTI-PATTERN 1: Unnecessary async — wrapping a return value
// BAD
async function getUser(id: string): Promise<User> {
  return userCache.get(id); // already synchronous
}
// GOOD — just return the value (or return a Promise directly)
function getUser(id: string): User {
  return userCache.get(id);
}

// ANTI-PATTERN 2: await in a loop — serial execution of independent work
// BAD — processes items one at a time
async function processItems(ids: string[]): Promise<Result[]> {
  const results: Result[] = [];
  for (const id of ids) {
    const result = await processItem(id); // sequential!
    results.push(result);
  }
  return results;
}
// GOOD — parallel
async function processItems(ids: string[]): Promise<Result[]> {
  return Promise.all(ids.map(id => processItem(id)));
}

// ANTI-PATTERN 3: Mixing await and .then()
// BAD — confusing and error-prone
async function fetchData(): Promise<void> {
  const data = await fetch("/api")
    .then(res => res.json())
    .then(json => json.data);
  // Pick one style — don't mix
}
// GOOD
async function fetchData(): Promise<void> {
  const res = await fetch("/api");
  const json = await res.json();
  const data = json.data;
}

// ANTI-PATTERN 4: Returning await (usually unnecessary)
// REDUNDANT — the async function already wraps the return in a Promise
async function getUser(): Promise<User> {
  return await fetchUser(); // await is unnecessary
}
// EXCEPTION — return await IS needed inside try/catch!
async function getUser(): Promise<User> {
  try {
    return await fetchUser(); // NEEDED — without await, catch won't trigger
  } catch (err) {
    return fallbackUser();
  }
}
```

---

## 3. Event Emitters

### EventEmitter Class and API

`EventEmitter` is the backbone of Node.js's event-driven architecture. Streams, HTTP servers, and many core modules inherit from it.

```typescript
import { EventEmitter } from "events";

// Typed event emitter (covered in detail below)
interface ServerEvents {
  connection: [socket: Socket];
  error: [err: Error];
  listening: [port: number];
}

class Server extends EventEmitter<ServerEvents> {
  start(port: number): void {
    // ... setup ...
    this.emit("listening", port);
  }
}

const server = new Server();

// Register listeners
server.on("connection", (socket) => handleConnection(socket));
server.on("listening", (port) => console.log(`Listening on ${port}`));

// Core API
server.on(event, listener);       // add listener
server.once(event, listener);     // add one-time listener
server.off(event, listener);      // remove specific listener (alias: removeListener)
server.removeAllListeners(event); // remove all listeners for event
server.emit(event, ...args);      // synchronously invoke all listeners
server.listenerCount(event);      // number of listeners for event
server.eventNames();              // array of event names with listeners
server.setMaxListeners(n);        // set max listeners threshold (default: 10)
```

### Memory Leaks from Unremoved Listeners

The #1 cause of memory leaks in Node.js applications is accumulating event listeners without removing them.

```typescript
// LEAK: Adding listener on every request
import { EventEmitter } from "events";

const bus = new EventEmitter();

function handleRequest(req: Request): void {
  // BUG: new listener added every request, never removed
  bus.on("config-change", () => {
    // uses req — closure keeps req in memory
    updateResponse(req);
  });
}
// After 10,000 requests: 10,000 listeners, all holding references to old Request objects

// FIX 1: Use once() for one-time listeners
bus.once("config-change", () => updateResponse(req));

// FIX 2: Track and remove listeners
function handleRequest(req: Request): () => void {
  const listener = () => updateResponse(req);
  bus.on("config-change", listener);
  // Return cleanup function
  return () => bus.off("config-change", listener);
}

// FIX 3: Use AbortSignal (Node 18.7+)
function handleRequest(req: Request, signal: AbortSignal): void {
  bus.on("config-change", () => updateResponse(req), { signal });
  // Listener is automatically removed when signal is aborted
}
```

Node.js warns you when a single emitter has more than 10 listeners for the same event (default `maxListeners`). This is a heuristic — set it higher only if you know the listeners are intentional.

### Error Event Special Behavior

The `"error"` event has unique semantics in Node.js: if an `"error"` event is emitted and there are **no listeners**, Node.js throws the error as an uncaught exception and crashes the process.

```typescript
const emitter = new EventEmitter();

// NO error listener — this crashes the process!
emitter.emit("error", new Error("something broke"));
// Throws: Error: something broke

// Always add an error listener
emitter.on("error", (err) => {
  console.error("Caught:", err.message);
});

// For streams, unhandled error events are particularly dangerous:
const readable = fs.createReadStream("/nonexistent");
// If you don't add an error handler, the process crashes
readable.on("error", (err) => {
  console.error("File error:", err.message);
});
```

### events.on() for Async Iteration

`events.on()` converts events into an async iterable, bridging the event-driven and async/await worlds:

```typescript
import { on } from "events";

// Iterate over events as they arrive
const server = createServer();

async function handleConnections(signal: AbortSignal): Promise<void> {
  for await (const [socket] of on(server, "connection", { signal })) {
    // Each iteration yields the arguments array of the emitted event
    console.log("New connection from:", socket.remoteAddress);
    handleSocket(socket);
  }
  // Loop ends when signal is aborted
}

const ac = new AbortController();
handleConnections(ac.signal);

// Later: stop listening
ac.abort();
```

### Typed Event Emitters in TypeScript

```typescript
// Typed EventEmitter is a TypeScript-only feature via `@types/node` (not a runtime change).
// `EventEmitter` remains non-generic at runtime.
import { EventEmitter } from "events";

// Define event map: event name → argument tuple
interface AppEvents {
  "user:login": [userId: string, timestamp: Date];
  "user:logout": [userId: string];
  "error": [error: Error];
}

class AppEventBus extends EventEmitter<AppEvents> {}

const bus = new AppEventBus();

// Full type safety on emit and on
bus.on("user:login", (userId, timestamp) => {
  // userId: string, timestamp: Date — fully typed
  console.log(`${userId} logged in at ${timestamp}`);
});

bus.emit("user:login", "u123", new Date()); // OK
// bus.emit("user:login", 123);             // Error: number is not assignable to string
// bus.emit("nonexistent");                 // Error: not in AppEvents
```

### AbortSignal Integration

Since Node.js 18.7+, event listeners accept an `AbortSignal` for automatic cleanup:

```typescript
const ac = new AbortController();
const emitter = new EventEmitter();

// Listener is automatically removed when signal is aborted
emitter.on("data", (chunk) => {
  process(chunk);
}, { signal: ac.signal });

// Later — this removes the listener without needing a reference to it
ac.abort();

console.log(emitter.listenerCount("data")); // 0
```

---

## 4. Worker Threads

### When to Use Workers vs Child Processes vs Cluster

```
┌──────────────┬──────────────────┬──────────────────┬──────────────────┐
│              │  Worker Threads  │ Child Processes   │ Cluster          │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Memory       │ Shared (via      │ Separate (copy    │ Separate (forked │
│              │ SharedArrayBuf)  │ of parent)        │ server process)  │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Startup cost │ Low (~5ms)       │ High (~30ms)      │ High (~30ms)     │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Communication│ MessagePort,     │ IPC (serialized)  │ IPC (serialized) │
│              │ SharedArrayBuf   │                   │                  │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Use case     │ CPU-bound work   │ Running separate  │ Multi-core HTTP  │
│              │ (crypto, image   │ programs/scripts   │ servers          │
│              │ processing)      │ (ffmpeg, shell)    │                  │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Isolation    │ Same process,    │ Full OS-level     │ Full OS-level    │
│              │ shared event     │ process isolation  │ process isolation│
│              │ loop per worker  │                    │                  │
├──────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Crash impact │ Worker crash     │ Child crash does  │ Worker crash does│
│              │ won't crash main │ not crash parent   │ not crash master │
└──────────────┴──────────────────┴──────────────────┴──────────────────┘
```

### worker_threads Module API

```typescript
// main.ts — spawning a worker
import { Worker, isMainThread, parentPort, workerData } from "worker_threads";

if (isMainThread) {
  // Main thread — create a worker
  const worker = new Worker("./heavy-computation.js", {
    workerData: { iterations: 1_000_000 },
  });

  worker.on("message", (result: number) => {
    console.log("Result from worker:", result);
  });

  worker.on("error", (err) => {
    console.error("Worker error:", err);
  });

  worker.on("exit", (code) => {
    if (code !== 0) {
      console.error(`Worker exited with code ${code}`);
    }
  });
} else {
  // Worker thread — runs in parallel
  const { iterations } = workerData as { iterations: number };

  let result = 0;
  for (let i = 0; i < iterations; i++) {
    result += Math.sqrt(i);
  }

  // Send result back to main thread
  parentPort!.postMessage(result);
}
```

### SharedArrayBuffer and Atomics

`SharedArrayBuffer` enables true shared memory between threads — no serialization overhead. `Atomics` provides thread-safe operations.

```typescript
// main.ts
import { Worker } from "worker_threads";

// Create shared memory — 1024 bytes visible to both threads
const sharedBuffer = new SharedArrayBuffer(1024);
const sharedArray = new Int32Array(sharedBuffer);

// Initialize
sharedArray[0] = 0; // Counter at index 0

const worker = new Worker("./counter-worker.js", {
  workerData: { sharedBuffer },
});

// Wait for worker to increment the counter
worker.on("exit", () => {
  console.log("Counter value:", sharedArray[0]); // 1000
});

// counter-worker.ts
import { workerData } from "worker_threads";

const sharedArray = new Int32Array(workerData.sharedBuffer);

for (let i = 0; i < 1000; i++) {
  // Atomics.add ensures thread-safe increment — no race condition
  Atomics.add(sharedArray, 0, 1);
}

// Atomics API overview:
// Atomics.add(arr, idx, val)      — atomically add and return old value
// Atomics.sub(arr, idx, val)      — atomically subtract
// Atomics.and/or/xor(arr, idx, val) — bitwise operations
// Atomics.load(arr, idx)          — read with acquire semantics
// Atomics.store(arr, idx, val)    — write with release semantics
// Atomics.compareExchange(arr, idx, expected, replacement) — CAS
// Atomics.wait(arr, idx, val)     — block until value changes (workers only)
// Atomics.notify(arr, idx, count) — wake threads blocked on wait()
```

### MessagePort and MessageChannel

```typescript
import { Worker, MessageChannel, MessagePort } from "worker_threads";

// MessageChannel creates a pair of connected ports
const { port1, port2 } = new MessageChannel();

// Transfer port2 to worker — the worker gets exclusive ownership
const worker = new Worker("./worker.js", {
  workerData: { port: port2 },
  transferList: [port2], // Transfer, don't clone
});

// Main thread communicates via port1
port1.on("message", (msg) => console.log("From worker:", msg));
port1.postMessage("Hello worker!");

// worker.js
import { workerData } from "worker_threads";
const { port } = workerData as { port: MessagePort };
port.on("message", (msg) => {
  port.postMessage(`Echo: ${msg}`);
});
```

### Transferable Objects vs Structured Clone

```typescript
// STRUCTURED CLONE (default) — deep copy of the data
// Both threads have independent copies — safe but slow for large data
worker.postMessage({ data: largeArray }); // copies largeArray

// TRANSFER — zero-copy, moves ownership to the receiver
// Original becomes unusable (detached/neutered) in sender
const buffer = new ArrayBuffer(1024 * 1024); // 1MB
worker.postMessage({ buffer }, [buffer]); // transfer list
// buffer.byteLength === 0 after transfer — it's been moved

// Transferable types:
// - ArrayBuffer
// - MessagePort
// - ReadableStream / WritableStream / TransformStream
// - ImageBitmap (browser)
// - OffscreenCanvas (browser)

// DECISION GUIDE:
// < 64KB data     → structured clone (copy overhead negligible)
// > 64KB data     → transfer if possible (zero-copy)
// Shared counters → SharedArrayBuffer + Atomics
```

### Thread Pool Patterns

```typescript
// Using piscina — production-grade worker pool
import Piscina from "piscina";

const pool = new Piscina({
  filename: "./workers/image-resize.js",
  minThreads: 2,
  maxThreads: 8,
  idleTimeout: 30_000, // kill idle threads after 30s
});

// Submit tasks — pool manages thread reuse
async function resizeImages(paths: string[]): Promise<Buffer[]> {
  return Promise.all(
    paths.map(path => pool.run({ path, width: 800, height: 600 }))
  );
}

// image-resize.js (the worker file)
export default function resize({ path, width, height }): Buffer {
  // CPU-intensive image processing here
  return processedBuffer;
}

// Manual thread pool pattern:
class WorkerPool {
  private workers: Worker[] = [];
  private queue: Array<{ data: unknown; resolve: Function; reject: Function }> = [];
  private freeWorkers: Worker[] = [];

  constructor(private workerFile: string, private size: number) {
    for (let i = 0; i < size; i++) {
      const worker = new Worker(workerFile);
      worker.on("message", (result) => {
        const task = (worker as any).__currentTask;
        task.resolve(result);
        this.freeWorkers.push(worker);
        this.processQueue();
      });
      worker.on("error", (err) => {
        const task = (worker as any).__currentTask;
        task.reject(err);
        this.freeWorkers.push(worker);
        this.processQueue();
      });
      this.workers.push(worker);
      this.freeWorkers.push(worker);
    }
  }

  run<T>(data: unknown): Promise<T> {
    return new Promise((resolve, reject) => {
      this.queue.push({ data, resolve, reject });
      this.processQueue();
    });
  }

  private processQueue(): void {
    if (this.queue.length === 0 || this.freeWorkers.length === 0) return;
    const worker = this.freeWorkers.pop()!;
    const task = this.queue.shift()!;
    (worker as any).__currentTask = task;
    worker.postMessage(task.data);
  }
}
```

---

## 5. Child Processes & Cluster

### spawn vs execFile vs fork

```typescript
import { spawn, execFile, fork } from "child_process";

// spawn — streaming I/O, no shell, best for long-running processes
const ls = spawn("ls", ["-la", "/tmp"]);
ls.stdout.on("data", (chunk: Buffer) => process.stdout.write(chunk));
ls.stderr.on("data", (chunk: Buffer) => process.stderr.write(chunk));
ls.on("close", (code) => console.log(`exited with code ${code}`));
// stdout/stderr are streams — handles large output without buffering all in memory

// execFile — buffered output, no shell, safe for running binaries
execFile("ls", ["-la", "/tmp"], (error, stdout, stderr) => {
  if (error) throw error;
  console.log(stdout); // entire output buffered as string
});
// NOTE: prefer execFile over exec — it does not spawn a shell,
// avoiding command injection risks from unsanitized user input

// fork — special spawn for Node.js scripts with built-in IPC channel
const child = fork("./worker.js", ["arg1"], {
  env: { ...process.env, WORKER_ID: "1" },
});
child.send({ type: "task", payload: data }); // IPC message
child.on("message", (msg) => console.log("From child:", msg));
```

| Method | Shell? | I/O | IPC? | Buffer limit | Best for |
|--------|--------|-----|------|-------------|----------|
| `spawn` | No | Streaming | No (unless stdio: 'ipc') | None (streaming) | Long-running, large output |
| `execFile` | No | Buffered | No | 1MB default | Safe execution of binaries |
| `fork` | No | Streaming | Yes (built-in) | None (streaming) | Node.js worker scripts |

### IPC Communication

```typescript
// parent.ts
import { fork } from "child_process";

const child = fork("./computation-worker.js");

// Send task to child
child.send({ type: "compute", data: [1, 2, 3, 4, 5] });

// Receive result from child
child.on("message", (msg: { type: string; result: number }) => {
  if (msg.type === "result") {
    console.log("Computation result:", msg.result);
    child.kill(); // or child.disconnect()
  }
});

// computation-worker.js
process.on("message", (msg: { type: string; data: number[] }) => {
  if (msg.type === "compute") {
    const result = msg.data.reduce((sum, n) => sum + n, 0);
    process.send!({ type: "result", result });
  }
});

// IPC uses serialization — only JSON-compatible data by default
// For large binary data, use shared file descriptors or pipes instead
```

### Cluster Module for Multi-Core HTTP Servers

```typescript
import cluster from "cluster";
import http from "http";
import { cpus } from "os";

if (cluster.isPrimary) {
  const numCPUs = cpus().length;
  console.log(`Primary ${process.pid} forking ${numCPUs} workers`);

  // Fork workers — one per CPU core
  for (let i = 0; i < numCPUs; i++) {
    cluster.fork();
  }

  // Handle worker crashes — restart immediately
  cluster.on("exit", (worker, code, signal) => {
    console.log(`Worker ${worker.process.pid} died (${signal || code})`);
    console.log("Starting replacement worker...");
    cluster.fork();
  });

  // The primary distributes incoming connections to workers
  // using round-robin (default on all platforms except Windows)
} else {
  // Workers share the same server port — OS handles distribution
  http.createServer((req, res) => {
    res.writeHead(200);
    res.end(`Handled by worker ${process.pid}\n`);
  }).listen(3000);

  console.log(`Worker ${process.pid} started`);
}
```

```
Cluster architecture:

┌─────────────────────────────────────────────────────┐
│                   PRIMARY PROCESS                   │
│  ┌──────────────────────────────────────────────┐   │
│  │         Listens on port 3000                 │   │
│  │     (distributes connections via IPC)        │   │
│  └──┬───────────┬───────────┬───────────┬───────┘   │
│     │           │           │           │           │
│     ▼           ▼           ▼           ▼           │
│  ┌──────┐  ┌──────┐   ┌──────┐   ┌──────┐          │
│  │Worker│  │Worker│   │Worker│   │Worker│          │
│  │  #1  │  │  #2  │   │  #3  │   │  #4  │          │
│  │ pid: │  │ pid: │   │ pid: │   │ pid: │          │
│  │ 1234 │  │ 1235 │   │ 1236 │   │ 1237 │          │
│  └──────┘  └──────┘   └──────┘   └──────┘          │
│  Each worker is a full Node.js process with its     │
│  own V8 instance, event loop, and memory space      │
└─────────────────────────────────────────────────────┘
```

### Cluster vs Reverse Proxy Load Balancing

| Aspect | Node.js Cluster | Reverse Proxy (nginx) |
|--------|-----------------|----------------------|
| Setup | Built into Node.js, zero config | Separate process, config required |
| Health checks | Basic (exit event) | Sophisticated (HTTP checks, timeouts) |
| SSL termination | Must handle in Node.js | Offloaded to proxy |
| Static files | Served by Node.js | Served directly by nginx |
| Zero-downtime deploy | Manual (graceful restart) | Built-in upstream management |
| Horizontal scaling | Single machine only | Multiple machines |
| Memory overhead | N full Node.js processes | Single nginx process + N Node.js |

**Production recommendation**: Use a reverse proxy (nginx, HAProxy) in front, with PM2 or systemd managing Node.js processes. The cluster module is useful for simpler deployments or when you cannot add infrastructure.

### Graceful Worker Restart

```typescript
// Zero-downtime restart pattern
import cluster from "cluster";

if (cluster.isPrimary) {
  const workers = new Set<cluster.Worker>();

  function forkWorker(): cluster.Worker {
    const worker = cluster.fork();
    workers.add(worker);
    worker.on("exit", () => workers.delete(worker));
    return worker;
  }

  // Initial fork
  for (let i = 0; i < 4; i++) forkWorker();

  // Graceful restart: SIGUSR2
  process.on("SIGUSR2", () => {
    const workerArray = [...workers];
    let i = 0;

    function restartNext(): void {
      if (i >= workerArray.length) return;
      const worker = workerArray[i++];
      console.log(`Restarting worker ${worker.process.pid}`);

      // Fork replacement FIRST, then kill old worker
      const replacement = forkWorker();
      replacement.on("listening", () => {
        // New worker is ready — safely kill old one
        worker.send("shutdown");
        setTimeout(() => {
          if (!worker.isDead()) worker.kill();
        }, 10_000); // force kill after 10s
        restartNext();
      });
    }

    restartNext();
  });
}

// Worker graceful shutdown
if (!cluster.isPrimary) {
  const server = http.createServer(handler).listen(3000);

  process.on("message", (msg) => {
    if (msg === "shutdown") {
      // Stop accepting new connections
      server.close(() => {
        // All existing connections have finished
        process.exit(0);
      });
    }
  });
}
```

---

## 6. Concurrency Control Patterns

> **Note on structured concurrency**: Node has no native structured concurrency primitive. Use user-land libs like `p-limit`, `p-map`, `p-queue`, or `async-pool` for bounded concurrency.

### Semaphore Pattern

A semaphore limits the number of concurrent async operations. JavaScript does not have built-in semaphores, but the pattern is straightforward with promises:

```typescript
class Semaphore {
  private queue: Array<() => void> = [];
  private running = 0;

  constructor(private maxConcurrency: number) {}

  async acquire(): Promise<void> {
    if (this.running < this.maxConcurrency) {
      this.running++;
      return;
    }
    // Wait until a slot opens
    return new Promise<void>((resolve) => {
      this.queue.push(() => {
        this.running++;
        resolve();
      });
    });
  }

  release(): void {
    this.running--;
    if (this.queue.length > 0) {
      const next = this.queue.shift()!;
      next(); // wake up next waiter
    }
  }

  async use<T>(fn: () => Promise<T>): Promise<T> {
    await this.acquire();
    try {
      return await fn();
    } finally {
      this.release();
    }
  }
}

// Usage — limit to 5 concurrent HTTP requests
const semaphore = new Semaphore(5);

async function fetchAll(urls: string[]): Promise<Response[]> {
  return Promise.all(
    urls.map(url => semaphore.use(() => fetch(url)))
  );
}
```

### Rate Limiting / Throttling Async Operations

```typescript
// Token bucket rate limiter for async operations
class RateLimiter {
  private tokens: number;
  private lastRefill: number;
  private waitQueue: Array<() => void> = [];

  constructor(
    private maxTokens: number,
    private refillRate: number,  // tokens per second
  ) {
    this.tokens = maxTokens;
    this.lastRefill = Date.now();
  }

  private refill(): void {
    const now = Date.now();
    const elapsed = (now - this.lastRefill) / 1000;
    this.tokens = Math.min(this.maxTokens, this.tokens + elapsed * this.refillRate);
    this.lastRefill = now;
  }

  async acquire(): Promise<void> {
    this.refill();
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return;
    }
    // Wait until a token is available
    const waitTime = ((1 - this.tokens) / this.refillRate) * 1000;
    return new Promise<void>((resolve) => {
      setTimeout(() => {
        this.tokens -= 1;
        resolve();
      }, waitTime);
    });
  }
}

// Usage: 10 requests per second max
const limiter = new RateLimiter(10, 10);

async function rateLimitedFetch(url: string): Promise<Response> {
  await limiter.acquire();
  return fetch(url);
}
```

### Async Queue (p-queue / BullMQ)

```typescript
// p-queue — in-process priority queue with concurrency control
import PQueue from "p-queue";

const queue = new PQueue({
  concurrency: 3,       // max 3 tasks at once
  interval: 1000,       // per-interval rate limiting
  intervalCap: 10,      // max 10 tasks per interval
  timeout: 30_000,      // per-task timeout
  throwOnTimeout: true,
});

// Add tasks — they run respecting concurrency limits
const results = await Promise.all([
  queue.add(() => fetchUser("1"), { priority: 1 }),    // lower priority
  queue.add(() => fetchUser("2"), { priority: 10 }),   // higher priority (runs first)
  queue.add(() => fetchUser("3"), { priority: 5 }),
]);

// Queue events
queue.on("active", () => console.log(`Active: ${queue.pending} pending`));
queue.on("idle", () => console.log("Queue is idle"));

// Wait for all queued tasks to complete
await queue.onIdle();

// BullMQ — distributed queue backed by Redis
import { Queue, Worker } from "bullmq";

const emailQueue = new Queue("emails", {
  connection: { host: "localhost", port: 6379 },
});

// Producer — add jobs from any process/server
await emailQueue.add("welcome", { to: "user@example.com", template: "welcome" });
await emailQueue.add("reset", { to: "user@example.com", template: "reset" }, {
  delay: 60_000,      // delay 1 minute
  attempts: 3,        // retry up to 3 times
  backoff: { type: "exponential", delay: 1000 },
});

// Consumer — process jobs (can run in a separate process)
const worker = new Worker("emails", async (job) => {
  await sendEmail(job.data.to, job.data.template);
}, {
  connection: { host: "localhost", port: 6379 },
  concurrency: 5,
});
```

### Connection Pool Management

```typescript
// Generic async connection pool
class ConnectionPool<T> {
  private available: T[] = [];
  private waitQueue: Array<(conn: T) => void> = [];
  private size = 0;

  constructor(
    private maxSize: number,
    private factory: () => Promise<T>,
    private destroyer: (conn: T) => Promise<void>,
  ) {}

  async acquire(): Promise<T> {
    // Return existing available connection
    if (this.available.length > 0) {
      return this.available.pop()!;
    }

    // Create new connection if pool not full
    if (this.size < this.maxSize) {
      this.size++;
      try {
        return await this.factory();
      } catch (err) {
        this.size--;
        throw err;
      }
    }

    // Wait for a connection to be released
    return new Promise<T>((resolve) => {
      this.waitQueue.push(resolve);
    });
  }

  release(conn: T): void {
    if (this.waitQueue.length > 0) {
      // Hand connection directly to next waiter
      const waiter = this.waitQueue.shift()!;
      waiter(conn);
    } else {
      this.available.push(conn);
    }
  }

  async withConnection<R>(fn: (conn: T) => Promise<R>): Promise<R> {
    const conn = await this.acquire();
    try {
      return await fn(conn);
    } finally {
      this.release(conn);
    }
  }
}
```

### Mutex / Lock Patterns

```typescript
// Async mutex — ensures only one async operation accesses a resource at a time
class Mutex {
  private locked = false;
  private waitQueue: Array<() => void> = [];

  async lock(): Promise<void> {
    if (!this.locked) {
      this.locked = true;
      return;
    }
    return new Promise<void>((resolve) => {
      this.waitQueue.push(resolve);
    });
  }

  unlock(): void {
    if (this.waitQueue.length > 0) {
      const next = this.waitQueue.shift()!;
      // Don't set locked=false — hand the lock directly to next waiter
      next();
    } else {
      this.locked = false;
    }
  }

  async withLock<T>(fn: () => Promise<T>): Promise<T> {
    await this.lock();
    try {
      return await fn();
    } finally {
      this.unlock();
    }
  }
}

// Usage — prevent concurrent writes to same resource
const fileLocks = new Map<string, Mutex>();

function getLock(path: string): Mutex {
  if (!fileLocks.has(path)) {
    fileLocks.set(path, new Mutex());
  }
  return fileLocks.get(path)!;
}

async function safeWriteFile(path: string, data: string): Promise<void> {
  const lock = getLock(path);
  await lock.withLock(async () => {
    // Only one concurrent call per file path
    await fs.promises.writeFile(path, data);
  });
}
```

**Why do we need locks in single-threaded JavaScript?** Although JS is single-threaded, async operations yield between `await` points. Two async functions modifying the same resource can interleave:

```typescript
// WITHOUT LOCK — race condition!
let balance = 100;

async function withdraw(amount: number): Promise<void> {
  const current = balance;        // read: 100
  await validateWithBank(amount); // yields — another call can run here
  balance = current - amount;     // write: 100 - 50 = 50 (but someone else also withdrew!)
}

// Both calls read balance=100, both write balance=50
// Expected: 100 - 50 - 50 = 0, Actual: 50
await Promise.all([withdraw(50), withdraw(50)]);
```

### AsyncLocalStorage for Request Context

> **Performance note**: Early versions had measurable overhead. Node 20+ uses `AsyncContextFrame` internally — overhead is typically <5% for most workloads.

```typescript
import { AsyncLocalStorage } from "async_hooks";

// Create storage — one per concern (request context, tracing, etc.)
interface RequestContext {
  requestId: string;
  userId: string;
  startTime: number;
}

const requestContext = new AsyncLocalStorage<RequestContext>();

// Middleware — sets context for entire request lifecycle
function contextMiddleware(req: Request, res: Response, next: NextFunction): void {
  const context: RequestContext = {
    requestId: crypto.randomUUID(),
    userId: req.headers["x-user-id"] as string,
    startTime: Date.now(),
  };

  // All async operations within this callback have access to the context
  requestContext.run(context, () => {
    next();
  });
}

// Anywhere in the request lifecycle — no need to pass context through params
function getRequestId(): string {
  const ctx = requestContext.getStore();
  if (!ctx) throw new Error("No request context available");
  return ctx.requestId;
}

// Logger automatically includes request context
function log(message: string): void {
  const ctx = requestContext.getStore();
  console.log(JSON.stringify({
    message,
    requestId: ctx?.requestId,
    userId: ctx?.userId,
    elapsed: ctx ? Date.now() - ctx.startTime : undefined,
  }));
}

// Works across async boundaries — no manual threading
async function handleOrder(orderId: string): Promise<void> {
  log("Processing order");                  // has requestId
  const order = await db.getOrder(orderId);
  log("Order fetched");                     // still has requestId
  await sendConfirmation(order);
  log("Confirmation sent");                 // still has requestId
}
```

```
AsyncLocalStorage propagation:

  requestContext.run(ctx, callback)
        │
        ▼
  ┌─── callback() ─────────────────────────────────────┐
  │                                                     │
  │  handleOrder()                                      │
  │    │                                                │
  │    ├── await db.getOrder() ─── ctx propagated ──►   │
  │    │     │                                          │
  │    │     └── log() ←── getStore() returns ctx       │
  │    │                                                │
  │    ├── await sendConfirmation() ─── ctx propagated  │
  │    │     │                                          │
  │    │     └── await emailService.send()              │
  │    │           │                                    │
  │    │           └── log() ←── still has ctx          │
  │    │                                                │
  │  Context propagates through ALL nested async calls  │
  └─────────────────────────────────────────────────────┘
```

---

## 7. Generators & Iterators

### Generator Functions

A generator function (`function*`) returns a `Generator` object that conforms to both the iterable and iterator protocols. It can **pause** and **resume** execution at each `yield` point.

```typescript
function* range(start: number, end: number, step = 1): Generator<number> {
  for (let i = start; i < end; i += step) {
    yield i; // pause here, return i to caller
  }
}

const gen = range(0, 5);
console.log(gen.next()); // { value: 0, done: false }
console.log(gen.next()); // { value: 1, done: false }
console.log(gen.next()); // { value: 2, done: false }
// ...
console.log(gen.next()); // { value: 4, done: false }
console.log(gen.next()); // { value: undefined, done: true }

// Generators are iterable — use with for...of, spread, destructuring
for (const n of range(0, 5)) {
  console.log(n); // 0, 1, 2, 3, 4
}

const arr = [...range(0, 5)]; // [0, 1, 2, 3, 4]
const [a, b, c] = range(10, 20); // a=10, b=11, c=12 (rest discarded lazily)
```

### yield and yield*

```typescript
// yield — pauses the generator and emits a single value
function* singles(): Generator<number> {
  yield 1;
  yield 2;
  yield 3;
}

// yield* — delegates to another iterable, flattening it
function* merged(): Generator<number> {
  yield* singles();       // yields 1, 2, 3
  yield* [4, 5, 6];      // yields 4, 5, 6
  yield* range(7, 10);   // yields 7, 8, 9
}

console.log([...merged()]); // [1, 2, 3, 4, 5, 6, 7, 8, 9]

// yield* can delegate to generators recursively — tree traversal
interface TreeNode<T> {
  value: T;
  children: TreeNode<T>[];
}

function* preorder<T>(node: TreeNode<T>): Generator<T> {
  yield node.value;
  for (const child of node.children) {
    yield* preorder(child); // recursive delegation
  }
}
```

### Two-Way Communication with yield

```typescript
// yield can RECEIVE values passed via .next(value)
function* accumulator(): Generator<number, void, number> {
  let total = 0;
  while (true) {
    const value = yield total; // yield current total, receive next value
    total += value;
  }
}

const acc = accumulator();
console.log(acc.next());     // { value: 0, done: false } — first .next() starts the generator
console.log(acc.next(10));   // { value: 10, done: false }
console.log(acc.next(20));   // { value: 30, done: false }
console.log(acc.next(5));    // { value: 35, done: false }
// Note: the argument to the FIRST .next() call is always discarded
```

### Lazy Evaluation

Generators produce values on demand. No intermediate arrays are created, making them memory-efficient for large or infinite sequences:

```typescript
// Infinite sequence — only generates values as consumed
function* fibonacci(): Generator<number> {
  let a = 0, b = 1;
  while (true) {
    yield a;
    [a, b] = [b, a + b];
  }
}

// Composable lazy operations
function* take<T>(n: number, iterable: Iterable<T>): Generator<T> {
  let count = 0;
  for (const item of iterable) {
    if (count >= n) return;
    yield item;
    count++;
  }
}

function* filter<T>(pred: (x: T) => boolean, iterable: Iterable<T>): Generator<T> {
  for (const item of iterable) {
    if (pred(item)) yield item;
  }
}

function* map<T, U>(fn: (x: T) => U, iterable: Iterable<T>): Generator<U> {
  for (const item of iterable) {
    yield fn(item);
  }
}

// Pipeline: first 10 even Fibonacci numbers, doubled
const result = [
  ...take(10,
    map(x => x * 2,
      filter(x => x % 2 === 0,
        fibonacci()
      )
    )
  )
];
// Only generates Fibonacci numbers until we have 10 even ones — no infinite loop
```

### Async Generators

```typescript
// async function* — yields promises, consumed with for await...of
async function* pollEndpoint(
  url: string,
  intervalMs: number,
  signal: AbortSignal,
): AsyncGenerator<Response> {
  while (!signal.aborted) {
    yield await fetch(url, { signal });
    await new Promise(r => setTimeout(r, intervalMs));
  }
}

// Consuming
const ac = new AbortController();

for await (const response of pollEndpoint("/api/status", 5000, ac.signal)) {
  const data = await response.json();
  if (data.status === "complete") {
    ac.abort(); // stop polling
    break;
  }
  console.log("Still processing...", data.progress);
}

// Streaming processing pipeline with async generators
async function* readLines(stream: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop()!; // incomplete last line
      for (const line of lines) {
        yield line;
      }
    }
    if (buffer.length > 0) yield buffer;
  } finally {
    reader.releaseLock();
  }
}

async function* parseJSON<T>(lines: AsyncIterable<string>): AsyncGenerator<T> {
  for await (const line of lines) {
    if (line.trim()) {
      yield JSON.parse(line) as T;
    }
  }
}

async function* filterValid<T>(
  items: AsyncIterable<T>,
  predicate: (item: T) => boolean,
): AsyncGenerator<T> {
  for await (const item of items) {
    if (predicate(item)) yield item;
  }
}

// Compose the pipeline — data flows through lazily
const response = await fetch("/api/events");
const events = filterValid(
  parseJSON<Event>(readLines(response.body!)),
  (event) => event.type === "order",
);

for await (const event of events) {
  await processOrder(event);
}
```

### Real-World Use Cases

```typescript
// 1. Paginated API client
async function* fetchAllUsers(baseUrl: string): AsyncGenerator<User> {
  let page = 1;
  let hasMore = true;

  while (hasMore) {
    const res = await fetch(`${baseUrl}/users?page=${page}&limit=100`);
    const data: { users: User[]; hasMore: boolean } = await res.json();
    yield* data.users; // yield each user individually
    hasMore = data.hasMore;
    page++;
  }
}

// Consumer doesn't know about pagination — just gets a stream of users
for await (const user of fetchAllUsers("https://api.example.com")) {
  if (user.lastLogin < cutoffDate) {
    await deactivateUser(user.id);
  }
}

// 2. Database cursor iteration
async function* queryCursor<T>(
  sql: string,
  params: unknown[],
  batchSize = 1000,
): AsyncGenerator<T> {
  const cursor = await db.cursor(sql, params);
  try {
    while (true) {
      const rows = await cursor.fetch(batchSize);
      if (rows.length === 0) break;
      yield* rows;
    }
  } finally {
    await cursor.close(); // always close the cursor
  }
}

// 3. Retry with exponential backoff
async function* retryWithBackoff<T>(
  fn: () => Promise<T>,
  maxRetries: number,
): AsyncGenerator<{ attempt: number; error?: Error }, T> {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      yield { attempt, error };
      if (attempt < maxRetries) {
        await new Promise(r => setTimeout(r, 2 ** attempt * 1000));
      } else {
        throw error;
      }
    }
  }
  throw new Error("Unreachable");
}
```

---

## 8. Cancellation & Timeouts

### AbortController and AbortSignal

`AbortController` / `AbortSignal` is the standard cancellation mechanism in modern JavaScript. It was originally designed for `fetch`, but is now supported across the Node.js API surface.

```typescript
// Basic usage
const controller = new AbortController();
const { signal } = controller;

// Check if already aborted
console.log(signal.aborted);   // false
console.log(signal.reason);    // undefined

// Listen for abort
signal.addEventListener("abort", () => {
  console.log("Aborted!", signal.reason);
});

// Trigger abort
controller.abort();                      // reason: AbortError
controller.abort("custom reason");       // reason: "custom reason"
controller.abort(new Error("timeout"));  // reason: Error("timeout")

// Static factory methods — version notes:
//   AbortSignal.timeout(): Node 17.3+
//   AbortSignal.any():     Node 20.3+
const timeoutSignal = AbortSignal.timeout(5000); // auto-aborts after 5s
const alreadyAborted = AbortSignal.abort("reason"); // already aborted

// Combine multiple signals — aborts when ANY source aborts (Node 20.3+)
const combined = AbortSignal.any([
  AbortSignal.timeout(5000),
  userCancelController.signal,
  requestCloseSignal,
]);
```

### Promise.race() for Timeouts

```typescript
// Generic timeout wrapper
function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  const timeout = new Promise<never>((_, reject) => {
    setTimeout(() => reject(new Error(`Timeout after ${ms}ms`)), ms);
  });
  return Promise.race([promise, timeout]);
}

// Usage
try {
  const result = await withTimeout(fetch("/api/slow"), 5000);
} catch (err) {
  console.error(err.message); // "Timeout after 5000ms"
}

// PROBLEM: The fetch is still running after timeout!
// Promise.race doesn't cancel the losing promise.

// BETTER: Use AbortSignal for true cancellation
async function fetchWithTimeout(url: string, ms: number): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), ms);
  try {
    const response = await fetch(url, { signal: controller.signal });
    return response;
  } finally {
    clearTimeout(timeoutId); // clean up timer if fetch completes first
  }
}

// BEST: Use AbortSignal.timeout() (Node 18+)
const response = await fetch("/api/slow", {
  signal: AbortSignal.timeout(5000), // auto-aborts after 5s
});
```

### Cancellable Fetch Requests

```typescript
// Real-world: Search with debounce and cancellation
class SearchService {
  private currentController: AbortController | null = null;

  async search(query: string): Promise<SearchResult[]> {
    // Cancel previous in-flight request
    this.currentController?.abort();

    // Create new controller for this request
    this.currentController = new AbortController();
    const { signal } = this.currentController;

    try {
      const response = await fetch(`/api/search?q=${encodeURIComponent(query)}`, {
        signal,
      });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.json();
    } catch (err) {
      if ((err as Error).name === "AbortError") {
        // Request was cancelled — not a real error
        return [];
      }
      throw err; // re-throw actual errors
    }
  }
}

// Cancel-aware async operation
async function processWithCancellation(
  items: string[],
  signal: AbortSignal,
): Promise<string[]> {
  const results: string[] = [];

  for (const item of items) {
    // Check signal before each expensive operation
    if (signal.aborted) {
      throw signal.reason;
    }

    const result = await expensiveOperation(item);
    results.push(result);
  }

  return results;
}
```

### Timeout Patterns for Database Queries

```typescript
// Pattern 1: Query-level timeout with AbortSignal
import { Client } from "pg";

async function queryWithTimeout(
  client: Client,
  sql: string,
  params: unknown[],
  timeoutMs: number,
): Promise<QueryResult> {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);

  try {
    // Set statement_timeout at DB level as defense in depth
    await client.query(`SET statement_timeout = ${timeoutMs}`);
    const result = await client.query({
      text: sql,
      values: params,
      signal: ac.signal,
    });
    return result;
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      throw new Error(`Query timed out after ${timeoutMs}ms: ${sql}`);
    }
    throw err;
  } finally {
    clearTimeout(timer);
    await client.query("RESET statement_timeout");
  }
}

// Pattern 2: Connection acquisition timeout
async function getConnectionWithTimeout(
  pool: Pool,
  timeoutMs: number,
): Promise<PoolClient> {
  const { promise, resolve, reject } = Promise.withResolvers<PoolClient>();

  const timer = setTimeout(() => {
    reject(new Error(`Connection pool timeout after ${timeoutMs}ms`));
  }, timeoutMs);

  pool.connect().then(
    (client) => {
      clearTimeout(timer);
      resolve(client);
    },
    (err) => {
      clearTimeout(timer);
      reject(err);
    },
  );

  return promise;
}

// Pattern 3: Transaction timeout wrapper
async function withTransactionTimeout<T>(
  pool: Pool,
  timeoutMs: number,
  fn: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const client = await getConnectionWithTimeout(pool, 5000);
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), timeoutMs);

  try {
    await client.query("BEGIN");
    const result = await fn(client);
    await client.query("COMMIT");
    return result;
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    clearTimeout(timer);
    client.release();
  }
}
```

### signal Parameter Across Node.js APIs

`AbortSignal` is widely supported across the Node.js standard library:

```typescript
import { readFile, writeFile } from "fs/promises";
import { setTimeout as sleep } from "timers/promises";
import { EventEmitter, on, once } from "events";

const signal = AbortSignal.timeout(5000);

// fs/promises — cancel file operations
await readFile("/path/to/file", { signal });
await writeFile("/path/to/file", data, { signal });

// timers/promises — cancellable sleep
await sleep(10_000, undefined, { signal }); // abort cancels the timer

// events.once — wait for a single event with timeout
const [data] = await once(emitter, "data", { signal });

// events.on — async iteration with cancellation
for await (const [event] of on(emitter, "data", { signal })) {
  process(event); // loop exits when signal aborts
}

// http — abort signal on request (Node 20.6+)
const server = createServer((req, res) => {
  // req.signal is aborted when the client disconnects
  doWork(req.signal).then(
    (result) => res.end(result),
    (err) => {
      if (req.signal.aborted) return; // client gone, no point responding
      res.statusCode = 500;
      res.end("Internal error");
    },
  );
});

// child_process — kill child on abort
import { execFile } from "child_process";
execFile("ffmpeg", ["-i", input, output], { signal });
// If signal is aborted, child process is sent SIGTERM

// stream.pipeline — cancel a pipeline
import { pipeline } from "stream/promises";
await pipeline(readable, transform, writable, { signal });
```

| API | `signal` parameter since | Behavior on abort |
|-----|-------------------------|-------------------|
| `fetch()` | Node 18 | Rejects with AbortError |
| `fs/promises.*` | Node 16 | Rejects with AbortError |
| `timers/promises.setTimeout` | Node 16 | Rejects with `AbortError` when signal aborts (does NOT resolve silently) |
| `events.once()` | Node 16 | Rejects with AbortError |
| `events.on()` | Node 16 | Async iterator returns |
| `child_process.*` | Node 16 | Sends SIGTERM to child |
| `stream.pipeline()` | Node 16 | Destroys streams with AbortError |
| `http request.signal` | Node 20.6 | Aborted when client disconnects |
| `readline` | Node 16 | Closes the interface |

### Cleanup on Cancellation

```typescript
// Proper cleanup pattern — using try/finally with signal
async function processJob(jobId: string, signal: AbortSignal): Promise<void> {
  const tempFile = `/tmp/job-${jobId}`;
  const lockKey = `lock:job:${jobId}`;

  // Acquire resources
  await acquireLock(lockKey);
  await writeFile(tempFile, "processing...");

  try {
    // Register cleanup for abort
    signal.addEventListener("abort", () => {
      console.log(`Job ${jobId} cancelled, cleaning up...`);
    }, { once: true });

    // Long-running work that checks signal periodically
    for (const chunk of dataChunks) {
      if (signal.aborted) {
        throw new Error("Job cancelled");
      }
      await processChunk(chunk, { signal });
    }
  } finally {
    // Cleanup runs whether job succeeded, failed, or was cancelled
    await unlink(tempFile).catch(() => {}); // ignore if already deleted
    await releaseLock(lockKey);
  }
}

// Composing cancellation — parent cancels all children
async function orchestrate(signal: AbortSignal): Promise<void> {
  // Create child controllers linked to parent signal
  const childController1 = new AbortController();
  const childController2 = new AbortController();

  // If parent aborts, abort all children
  signal.addEventListener("abort", () => {
    childController1.abort(signal.reason);
    childController2.abort(signal.reason);
  });

  // Or use AbortSignal.any() (Node 20+)
  const childSignal1 = AbortSignal.any([signal, childController1.signal]);
  const childSignal2 = AbortSignal.any([signal, childController2.signal]);

  await Promise.all([
    processJob("job-1", childSignal1),
    processJob("job-2", childSignal2),
  ]);
}

// Timeout with cleanup — real production pattern
async function fetchWithCleanup(url: string, timeoutMs: number): Promise<string> {
  const controller = new AbortController();
  const { signal } = controller;

  // Timeout auto-aborts
  const timer = setTimeout(
    () => controller.abort(new Error("Fetch timeout")),
    timeoutMs,
  );

  let response: Response | null = null;

  try {
    response = await fetch(url, { signal });
    const body = await response.text();
    return body;
  } catch (err) {
    // If we got a partial response, make sure to consume/close the body
    // to free the underlying TCP connection
    if (response?.body) {
      await response.body.cancel().catch(() => {});
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}
```

```
Cancellation hierarchy:

┌──────────────────────────────────────────────────────┐
│              Parent AbortController                  │
│                     │                                │
│         ┌───── abort() ──────┐                       │
│         │                    │                       │
│         ▼                    ▼                       │
│  ┌─────────────┐    ┌─────────────┐                  │
│  │ Child AC #1 │    │ Child AC #2 │                  │
│  │ (or linked  │    │ (or linked  │                  │
│  │  signal)    │    │  signal)    │                  │
│  └──────┬──────┘    └──────┬──────┘                  │
│         │                  │                         │
│         ▼                  ▼                         │
│  ┌─────────────┐    ┌─────────────┐                  │
│  │ fetch(sig1) │    │ fetch(sig2) │                  │
│  │ db.query()  │    │ file I/O    │                  │
│  │ timers      │    │ streams     │                  │
│  └─────────────┘    └─────────────┘                  │
│                                                      │
│  Parent abort → all children and their I/O cancelled │
└──────────────────────────────────────────────────────┘
```

---

## Interview Quick Reference

### Execution Order Rules

1. **Synchronous code** runs first — the entire call stack is emptied
2. **Microtasks** run next — Promise callbacks, `queueMicrotask()`. Note: `process.nextTick()` is **not a microtask** per spec. It has its own queue, drained BEFORE the microtask (promises) queue after each phase callback.
3. **Macrotasks** run one at a time — `setTimeout`, `setInterval`, `setImmediate`, I/O callbacks
4. After each macrotask, the microtask queue is fully drained again

```
┌───────────────────────────────────────────────────────┐
│                     Event Loop                        │
│                                                       │
│    ┌──────────────┐                                   │
│    │  Call Stack   │ ← synchronous code runs here     │
│    └──────┬───────┘                                   │
│           │ (empty)                                   │
│           ▼                                           │
│    ┌──────────────────┐                               │
│    │ process.nextTick  │ ← highest priority microtask │
│    │ queue             │   (Node.js only)             │
│    └──────┬───────────┘                               │
│           │ (drained)                                 │
│           ▼                                           │
│    ┌──────────────────┐                               │
│    │ Promise microtask │ ← .then(), queueMicrotask()  │
│    │ queue             │                              │
│    └──────┬───────────┘                               │
│           │ (drained)                                 │
│           ▼                                           │
│    ┌──────────────────┐                               │
│    │ Macrotask queue   │ ← timers, I/O, setImmediate  │
│    │ (one task)        │                              │
│    └──────┬───────────┘                               │
│           │                                           │
│           └──── loop back to microtask drain ────►    │
└───────────────────────────────────────────────────────┘
```

### Common Interview Questions & Key Answers

| Question | Key Points |
|----------|-----------|
| "What is the event loop?" | Single-threaded loop that processes callbacks from different queues in priority order: sync → nextTick → microtasks → macrotasks |
| "Why do Promises resolve before setTimeout(0)?" | Promise callbacks are microtasks; setTimeout callbacks are macrotasks. Microtasks are drained completely between each macrotask. |
| "Can you have race conditions in Node.js?" | Yes — between `await` points, another async function can interleave. The single thread prevents data races on individual operations but not logical race conditions across awaits. |
| "How would you handle 10,000 URLs to fetch?" | Semaphore/p-queue to limit concurrency (e.g., 50 at a time). Never `Promise.all(all10k)` — you will exhaust sockets/memory. |
| "When should you use worker threads?" | CPU-bound work only: crypto, image processing, compression. For I/O-bound work, the event loop with async I/O is already optimal. |
| "What happens if a Promise is never settled?" | It stays pending forever. The `.then()` callbacks are never called. If nothing references the promise, it can be garbage collected. No timeout, no error. |
| "How does AsyncLocalStorage work?" | Hooks into Node.js async resource tracking. Each async operation inherits the store from its parent context. Similar to Java's ThreadLocal but for async contexts. |
| "Promise.all vs Promise.allSettled?" | `.all()` fails fast on first rejection — use when all results are required. `.allSettled()` waits for all — use when you want partial results or need to know each outcome independently. |
