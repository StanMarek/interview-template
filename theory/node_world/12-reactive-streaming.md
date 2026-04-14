# Reactive Programming & Streams — Senior Engineer Interview Preparation

---

## 1. Node.js Streams Deep Dive

### Stream Types

Node.js streams are instances of `EventEmitter` that provide an abstraction for working with data that arrives or is consumed incrementally. All streams operate in one of two modes: **binary mode** (Buffer/string chunks) or **object mode** (arbitrary JavaScript values).

| Stream Type | Purpose | Key Methods | Example Use |
|-------------|---------|-------------|-------------|
| **Readable** | Source of data | `read()`, `pipe()`, `destroy()` | `fs.createReadStream`, HTTP request body |
| **Writable** | Destination for data | `write()`, `end()`, `destroy()` | `fs.createWriteStream`, HTTP response |
| **Duplex** | Both readable and writable (independent) | All of the above | TCP socket, WebSocket |
| **Transform** | Duplex where output is computed from input | `_transform()`, `_flush()` | `zlib.createGzip()`, crypto cipher |
| **PassThrough** | Transform that passes data through unchanged | Inherits Transform | Logging/metering proxy, stream teeing |

### Object Mode vs Binary Mode

```typescript
import { Transform, TransformCallback } from 'stream';

// Binary mode (default) — chunks are Buffer or string
const binaryTransform = new Transform({
  transform(chunk: Buffer, encoding: string, callback: TransformCallback) {
    // chunk is always a Buffer (or string if encoding is set)
    this.push(chunk.toString().toUpperCase());
    callback();
  },
});

// Object mode — chunks are arbitrary JS objects
const objectTransform = new Transform({
  objectMode: true,       // <-- this changes everything
  highWaterMark: 16,      // In object mode, this counts OBJECTS, not bytes
  transform(chunk: any, encoding: string, callback: TransformCallback) {
    // chunk is whatever was written — could be {id: 1, name: 'Alice'}
    this.push({ ...chunk, processed: true });
    callback();
  },
});
```

**Critical difference:** In binary mode, `highWaterMark` is measured in **bytes** (default 16 KB). In object mode, it is measured in **number of objects** (default 16). This catches people off guard when they see a stream "buffering too much" — they have 16 KB of JSON objects buffered but expected only 16 objects.

### Backpressure Mechanism

Backpressure is the mechanism by which a slow consumer signals a fast producer to slow down. Without it, a fast file read would overwhelm a slow network write, causing unbounded memory growth.

```
                        BACKPRESSURE FLOW
                        
  Producer (Readable)         Internal Buffer          Consumer (Writable)
  ┌─────────────────┐    ┌──────────────────────┐    ┌─────────────────┐
  │                  │    │  highWaterMark: 16KB │    │                 │
  │  _read() called  │───►│  ████████████░░░░░░  │───►│  _write() slow  │
  │  when buffer has │    │  12KB buffered       │    │  (network/disk) │
  │  room            │    │                      │    │                 │
  └─────────────────┘    └──────────────────────┘    └─────────────────┘
                                   │
                                   │  When buffer >= highWaterMark:
                                   │  1. write() returns false
                                   │  2. Producer MUST stop pushing
                                   │  3. Consumer drains buffer
                                   │  4. 'drain' event fires
                                   │  5. Producer resumes
                                   ▼
  
  writable.write(chunk) ─── returns true  ──► keep writing
                        └── returns false ──► STOP, wait for 'drain'
```

```typescript
import { createReadStream, createWriteStream } from 'fs';

// WRONG — ignoring backpressure, will cause OOM on large files
function copyFileBroken(src: string, dest: string): void {
  const readable = createReadStream(src);
  const writable = createWriteStream(dest);

  readable.on('data', (chunk) => {
    writable.write(chunk);  // Return value ignored! Memory will balloon
  });
}

// CORRECT — respecting backpressure manually
function copyFileCorrect(src: string, dest: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const readable = createReadStream(src);
    const writable = createWriteStream(dest);

    readable.on('data', (chunk) => {
      const canContinue = writable.write(chunk);
      if (!canContinue) {
        readable.pause();              // Stop reading until consumer catches up
      }
    });

    writable.on('drain', () => {
      readable.resume();               // Consumer caught up, resume reading
    });

    readable.on('end', () => writable.end());
    writable.on('finish', () => resolve());
    readable.on('error', reject);
    writable.on('error', reject);
  });
}
```

### pipe() vs pipeline() — The Critical Difference

`pipe()` does NOT forward errors or clean up on failure. `pipeline()` does both.

```typescript
import { pipeline } from 'stream/promises';
import { createReadStream, createWriteStream } from 'fs';
import { createGzip } from 'zlib';

// pipe() — error handling is YOUR problem
const readable = createReadStream('input.txt');
const gzip = createGzip();
const writable = createWriteStream('output.txt.gz');

readable.pipe(gzip).pipe(writable);
// If gzip errors, readable is NOT destroyed — it keeps reading into the void
// If writable errors, gzip keeps pushing data into a broken sink
// You must manually attach error handlers to EVERY stream in the chain

readable.on('error', cleanup);
gzip.on('error', cleanup);
writable.on('error', cleanup);

// pipeline() — error propagation and cleanup are automatic
async function compressFile(src: string, dest: string): Promise<void> {
  await pipeline(
    createReadStream(src),
    createGzip(),
    createWriteStream(dest),
  );
  // If ANY stream in the chain errors:
  //   1. All streams are destroyed
  //   2. The promise rejects with the error
  //   3. Resources are freed (file descriptors closed)
}
```

**Production rule:** Always use `pipeline()` (from `stream/promises`) over `pipe()`. The callback-based `stream.pipeline()` from `stream` is also acceptable when you need the callback pattern.

### stream.finished() for Cleanup

```typescript
import { finished } from 'stream/promises';
import { createReadStream } from 'fs';

async function processStream(filePath: string): Promise<void> {
  const stream = createReadStream(filePath);

  stream.on('data', (chunk) => {
    // process chunk
  });

  // finished() resolves when the stream is no longer readable/writable/has errored
  // It handles all edge cases: premature close, destroy, error, end
  await finished(stream);
  console.log('Stream fully consumed or errored — cleanup safe here');
}
```

### Creating Custom Streams

```typescript
import { Readable, Writable, Transform, TransformCallback } from 'stream';

// Custom Readable — database cursor as a stream
class DatabaseCursorStream extends Readable {
  private cursor: any;
  private batchSize: number;

  constructor(cursor: any, batchSize = 100) {
    super({ objectMode: true, highWaterMark: batchSize });
    this.cursor = cursor;
    this.batchSize = batchSize;
  }

  async _read(size: number): Promise<void> {
    try {
      const rows = await this.cursor.fetchNext(this.batchSize);
      if (rows.length === 0) {
        this.push(null);  // Signal end of stream
        return;
      }
      for (const row of rows) {
        if (!this.push(row)) {
          // Buffer is full — stop reading. Node will call _read again
          // when the consumer drains the buffer.
          return;
        }
      }
    } catch (err) {
      this.destroy(err as Error);
    }
  }

  _destroy(error: Error | null, callback: (error?: Error | null) => void): void {
    this.cursor.close().then(() => callback(error)).catch(callback);
  }
}

// Custom Transform — CSV line parser
class CsvLineParser extends Transform {
  private remainder = '';

  constructor() {
    super({ readableObjectMode: true });  // input is binary, output is objects
  }

  _transform(chunk: Buffer, encoding: string, callback: TransformCallback): void {
    const data = this.remainder + chunk.toString();
    const lines = data.split('\n');
    this.remainder = lines.pop() || '';  // Last element may be incomplete

    for (const line of lines) {
      if (line.trim()) {
        this.push(line.split(','));  // Push parsed array
      }
    }
    callback();
  }

  _flush(callback: TransformCallback): void {
    // Called when the writable side ends — process any remaining data
    if (this.remainder.trim()) {
      this.push(this.remainder.split(','));
    }
    callback();
  }
}
```

---

## 2. Web Streams API

### Overview

The Web Streams API (WHATWG Streams) is a web-standard streaming API now available in Node.js (stable since v18). Unlike Node.js streams (which inherit from `EventEmitter`), Web Streams use a promise-based pull model.

### Core Types

```typescript
// ReadableStream — a source of data
const readable = new ReadableStream<string>({
  start(controller) {
    controller.enqueue('Hello');
    controller.enqueue('World');
    controller.close();
  },
});

// WritableStream — a sink for data
const writable = new WritableStream<string>({
  write(chunk) {
    console.log('Received:', chunk);
  },
  close() {
    console.log('Stream closed');
  },
  abort(reason) {
    console.error('Stream aborted:', reason);
  },
});

// TransformStream — transforms data between readable and writable
const transform = new TransformStream<string, string>({
  transform(chunk, controller) {
    controller.enqueue(chunk.toUpperCase());
  },
});

// Piping them together
await readable
  .pipeThrough(transform)
  .pipeTo(writable);
// Output: "Received: HELLO", "Received: WORLD", "Stream closed"
```

### ReadableStream.from() — Converting Node Streams

```typescript
import { Readable } from 'stream';
import { createReadStream } from 'fs';

// Convert a Node.js Readable to a Web ReadableStream
const nodeStream = createReadStream('data.txt');
const webStream = Readable.toWeb(nodeStream);

// Convert any async iterable to a ReadableStream (Node 20+)
async function* generateNumbers() {
  for (let i = 0; i < 100; i++) {
    yield i;
  }
}
const fromIterable = ReadableStream.from(generateNumbers());
```

### Teeing Streams

Teeing creates two independent branches from a single ReadableStream. Each branch receives all the data independently.

```typescript
const response = await fetch('https://api.example.com/data');
const [branch1, branch2] = response.body!.tee();

// Process same data two different ways concurrently
const [analytics, storage] = await Promise.all([
  processForAnalytics(branch1),
  storeToDatabase(branch2),
]);
```

**Gotcha:** Teeing buffers the faster branch's data until the slower branch catches up. If one branch is much slower, memory usage will grow. In production, consider writing to a temporary file or message queue instead.

### Fetch API Streaming Responses

```typescript
// Streaming a large JSON response progressively
async function streamJSON(url: string): Promise<any[]> {
  const response = await fetch(url);
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const results: any[] = [];

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    // Parse NDJSON (newline-delimited JSON) incrementally
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (line.trim()) {
        results.push(JSON.parse(line));
      }
    }
  }

  return results;
}
```

### Node Streams vs Web Streams Comparison

| Aspect | Node.js Streams | Web Streams API |
|--------|----------------|-----------------|
| **Inheritance** | Extends `EventEmitter` | Standalone objects |
| **Backpressure model** | Push-based with `pause()`/`resume()` | Pull-based with async `read()` |
| **Error handling** | Event-based (`'error'` event) | Promise rejection |
| **Composability** | `pipe()` / `pipeline()` | `pipeThrough()` / `pipeTo()` |
| **Browser support** | Node.js only | Browsers + Node.js + Deno + Bun |
| **Object mode** | Built-in with `objectMode: true` | Always typed (generic parameter) |
| **Maturity** | Very mature, huge ecosystem | Newer, growing ecosystem |
| **Performance** | Slightly faster in Node.js | Extra overhead in Node.js (wraps internals) |
| **When to use** | Node.js servers, file I/O, piping | Cross-runtime code, Fetch API, service workers |

---

## 3. RxJS Fundamentals

### Observable, Observer, Subscription

RxJS (Reactive Extensions for JavaScript) models asynchronous data as **Observable** sequences that push values to **Observers** over time. A **Subscription** represents the execution of an Observable and is used for cancellation.

```typescript
import { Observable, Subscription } from 'rxjs';

// Creating an Observable
const numbers$ = new Observable<number>((subscriber) => {
  subscriber.next(1);
  subscriber.next(2);
  subscriber.next(3);

  // Async emission
  const timer = setTimeout(() => {
    subscriber.next(4);
    subscriber.complete();
  }, 1000);

  // Teardown logic — runs on unsubscribe or complete
  return () => {
    clearTimeout(timer);
    console.log('Teardown: resources freed');
  };
});

// Observer — object with next, error, complete handlers
const observer = {
  next: (value: number) => console.log('Value:', value),
  error: (err: Error) => console.error('Error:', err),
  complete: () => console.log('Done'),
};

// Subscription — represents the execution, used for cancellation
const subscription: Subscription = numbers$.subscribe(observer);

// Cancel after 500ms — teardown runs, no more values emitted
setTimeout(() => subscription.unsubscribe(), 500);
```

### Hot vs Cold Observables

```
  COLD OBSERVABLE                      HOT OBSERVABLE
  (unicast — per subscriber)           (multicast — shared)
  
  Sub A subscribes ──►  1, 2, 3       Sub A subscribes ──► 3, 4, 5
  Sub B subscribes ──►  1, 2, 3       Sub B subscribes ──►    4, 5
                                        (missed 1, 2, 3)
  
  Each subscriber gets                 All subscribers share
  its OWN execution                    the SAME execution
  
  Examples:                            Examples:
  - HTTP requests                      - Mouse events
  - Timer per subscriber               - WebSocket messages
  - Database queries                   - Shared timer (publish/share)
```

```typescript
import { interval, share, Subject, connectable } from 'rxjs';

// COLD — each subscriber gets independent execution
const cold$ = interval(1000);
cold$.subscribe((v) => console.log('A:', v)); // A: 0, 1, 2, 3...
cold$.subscribe((v) => console.log('B:', v)); // B: 0, 1, 2, 3... (independent)

// HOT via share() — multicast to all current subscribers
const hot$ = interval(1000).pipe(share());
hot$.subscribe((v) => console.log('A:', v));  // A: 0, 1, 2, 3...
setTimeout(() => {
  hot$.subscribe((v) => console.log('B:', v)); // B: 2, 3, 4... (missed 0,1)
}, 2500);
```

### Subject Types

A Subject is both an Observable and an Observer — it can multicast values to many subscribers.

```typescript
import { Subject, BehaviorSubject, ReplaySubject, AsyncSubject } from 'rxjs';

// --- Subject: No initial value, no replay ---
const subject = new Subject<number>();
subject.subscribe((v) => console.log('A:', v));
subject.next(1);  // A: 1
subject.subscribe((v) => console.log('B:', v));
subject.next(2);  // A: 2, B: 2  (B missed value 1)

// --- BehaviorSubject: Has current value, replays LAST value to new subs ---
const behavior = new BehaviorSubject<number>(0);  // initial value required
behavior.subscribe((v) => console.log('A:', v));   // A: 0 (immediate)
behavior.next(1);  // A: 1
behavior.subscribe((v) => console.log('B:', v));   // B: 1 (gets current)
behavior.getValue();  // 1 — synchronous access to current value

// --- ReplaySubject: Replays N most recent values to new subscribers ---
const replay = new ReplaySubject<number>(3);  // buffer size = 3
replay.next(1);
replay.next(2);
replay.next(3);
replay.next(4);
replay.subscribe((v) => console.log('Late:', v));
// Late: 2, Late: 3, Late: 4  (replayed last 3)

// --- AsyncSubject: Emits ONLY the last value, and only on complete ---
const async$ = new AsyncSubject<number>();
async$.subscribe((v) => console.log('Value:', v));
async$.next(1);   // nothing emitted yet
async$.next(2);   // nothing emitted yet
async$.next(3);   // nothing emitted yet
async$.complete(); // Value: 3  (only the last value)
```

| Subject Type | Initial Value | Replay | Emit on Complete Only | Use Case |
|-------------|---------------|--------|-----------------------|----------|
| **Subject** | No | None | No | Event bus, basic multicast |
| **BehaviorSubject** | Required | Last 1 | No | State management, current value |
| **ReplaySubject** | No | Last N | No | Chat history, late subscriber catch-up |
| **AsyncSubject** | No | Last 1 | Yes | One-shot async result, similar to Promise |

### Marble Diagrams

Marble diagrams are the standard notation for visualizing Observable behavior over time:

```
  Time ────────────────────────────────►

  Source:    ──1──2──3──4──5──|
                                  │ = complete
                                  X = error

  filter(x => x % 2 === 0):

  Output:    ─────2─────4─────|

  ─────────────────────────────────────

  Source A:  ──1─────3─────5──|
  Source B:  ────2─────4─────|

  merge(A, B):

  Output:    ──1──2──3──4──5──|

  ─────────────────────────────────────

  Source:    ──1──2──3──|

  map(x => x * 10):

  Output:    ──10─20─30─|

  ─────────────────────────────────────

  Source:    ──h-e-l-l-o──────w-o-r-l-d──|

  debounceTime(300ms):
                        ▼                 ▼
  Output:    ───────────o────────────────d──|
             (emits last value after 300ms silence)
```

---

## 4. RxJS Operators

### Creation Operators

```typescript
import {
  of, from, interval, timer, fromEvent, defer, EMPTY, NEVER, throwError,
  generate, range,
} from 'rxjs';
import { ajax } from 'rxjs/ajax';

of(1, 2, 3);                        // Emit values synchronously, then complete
from([1, 2, 3]);                     // From array, Promise, iterable, Observable
from(fetch('/api/users'));           // From Promise
interval(1000);                      // Emit 0, 1, 2, ... every 1000ms
timer(3000);                         // Emit 0 after 3s delay, then complete
timer(0, 1000);                      // Emit 0 immediately, then 1, 2, ... every 1s
fromEvent(document, 'click');        // From DOM event
defer(() => from(fetch('/api')));    // Lazy — factory runs at subscribe time
range(1, 10);                        // Emit 1 through 10
EMPTY;                               // Complete immediately, no values
NEVER;                               // Never emit, never complete
throwError(() => new Error('boom')); // Error immediately
```

### The switchMap vs mergeMap vs concatMap vs exhaustMap Decision

This is the most common RxJS interview question. All four operators map each source value to an inner Observable, but they differ in how they handle **concurrency**.

```
  Source:     ──A────────B────────C──|

  switchMap:  ──A────────B────────C──|
                │         │         │
               [a1,a2]  [b1,b2]  [c1,c2]
                │    ✗    │    ✗    │
  Output:     ──a1───────b1───────c1─c2─|
              (cancels previous inner on new source emission)

  mergeMap:   ──A────────B────────C──|
                │         │         │
               [a1,a2]  [b1,b2]  [c1,c2]
                │    │    │    │    │    │
  Output:     ──a1──a2───b1──b2───c1──c2─|
              (all inner observables run concurrently)

  concatMap:  ──A────────B────────C──|
                │         │         │
               [a1,a2]  [b1,b2]  [c1,c2]
                │    │              
  Output:     ──a1──a2──b1──b2──c1──c2─|
              (waits for previous inner to complete before starting next)

  exhaustMap: ──A────────B────────C──|
                │         │         │
               [a1,a2]  (ignored) [c1,c2]
                │    │              │    │
  Output:     ──a1──a2────────────c1─c2─|
              (ignores new source emissions while inner is active)
```

| Operator | Concurrency | Cancellation | Order | Best For |
|----------|-------------|-------------|-------|----------|
| `switchMap` | 1 (latest wins) | Cancels previous | Latest only | Autocomplete, route params, latest request |
| `mergeMap` | Unlimited (configurable) | None | Interleaved | Fire-and-forget, parallel requests |
| `concatMap` | 1 (serial) | None | Preserved | Sequential writes, ordered processing |
| `exhaustMap` | 1 (first wins) | Ignores new | First only | Login button, form submit (prevent double) |

```typescript
import { fromEvent, switchMap, mergeMap, concatMap, exhaustMap } from 'rxjs';
import { ajax } from 'rxjs/ajax';

// Autocomplete — cancel previous search on new input
const searchInput = document.getElementById('search')!;
fromEvent(searchInput, 'input').pipe(
  debounceTime(300),
  map((e: Event) => (e.target as HTMLInputElement).value),
  distinctUntilChanged(),
  switchMap((query) => ajax.getJSON(`/api/search?q=${query}`)),
  // If user types "rea" then "react", the "rea" request is CANCELLED
);

// Parallel file uploads — all run concurrently
uploadQueue$.pipe(
  mergeMap((file) => uploadFile(file), 3),  // max 3 concurrent uploads
);

// Sequential database writes — order must be preserved
events$.pipe(
  concatMap((event) => saveToDatabase(event)),
);

// Login button — ignore clicks while login is in progress
loginClick$.pipe(
  exhaustMap(() => authService.login(credentials)),
);
```

### Filtering Operators

```typescript
import {
  filter, distinctUntilChanged, debounceTime, throttleTime,
  take, takeUntil, skip, first, last, audit, sample,
} from 'rxjs';

source$.pipe(filter((x) => x > 5));                // Only values > 5
source$.pipe(distinctUntilChanged());               // Skip consecutive duplicates
source$.pipe(distinctUntilChanged((a, b) =>         // Custom comparison
  a.id === b.id));
source$.pipe(debounceTime(300));                    // Wait 300ms of silence
source$.pipe(throttleTime(1000));                   // At most 1 per 1000ms
source$.pipe(take(5));                              // First 5 values, then complete
source$.pipe(takeUntil(destroy$));                  // Until another observable emits
source$.pipe(skip(3));                              // Skip first 3 values
source$.pipe(first());                              // First value, then complete
source$.pipe(last());                               // Last value before complete
source$.pipe(audit(() => interval(1000)));          // Like throttle, emit latest in window
source$.pipe(sample(interval(1000)));               // Emit latest value on each tick
```

### Combination Operators

```typescript
import {
  combineLatest, merge, zip, forkJoin, withLatestFrom, concat, race,
} from 'rxjs';

// combineLatest — emit when ANY source emits (after all have emitted at least once)
combineLatest([user$, preferences$, theme$]).subscribe(
  ([user, prefs, theme]) => renderDashboard(user, prefs, theme),
);

// merge — interleave emissions from multiple sources
merge(clicks$, touches$, keyPresses$).subscribe(handleInput);

// zip — pair emissions by index (like a zipper)
zip(questions$, answers$).subscribe(
  ([question, answer]) => createQA(question, answer),
);

// forkJoin — wait for ALL to complete, emit last values (like Promise.all)
forkJoin({
  user: ajax.getJSON('/api/user/1'),
  orders: ajax.getJSON('/api/orders?userId=1'),
  preferences: ajax.getJSON('/api/preferences/1'),
}).subscribe(({ user, orders, preferences }) => {
  // All three requests completed
});

// withLatestFrom — on source emission, combine with latest from others
saveClick$.pipe(
  withLatestFrom(formData$, userId$),
).subscribe(([click, formData, userId]) => {
  save(userId, formData);
});
```

### Error Handling

```typescript
import { catchError, retry, retryWhen, timer, of, throwError, EMPTY } from 'rxjs';
import { delay, take, mergeMap } from 'rxjs/operators';

// catchError — recover from errors
source$.pipe(
  catchError((err, caught$) => {
    console.error('Error:', err);
    return of(fallbackValue);       // Replace error with fallback
    // return caught$;              // Retry the source (careful: infinite loop)
    // return EMPTY;                // Swallow error, complete
    // return throwError(() => new CustomError(err)); // Rethrow different error
  }),
);

// retry — retry N times immediately
source$.pipe(
  retry(3),  // Retry up to 3 times on error
);

// retry with exponential backoff (RxJS 7+)
source$.pipe(
  retry({
    count: 3,
    delay: (error, retryCount) => {
      const delayMs = Math.pow(2, retryCount) * 1000;  // 2s, 4s, 8s
      console.log(`Retry #${retryCount} in ${delayMs}ms`);
      return timer(delayMs);
    },
    resetOnSuccess: true,  // Reset retry count on successful emission
  }),
);
```

### Common Recipes

```typescript
// AUTOCOMPLETE with full production logic
const autocomplete$ = fromEvent<InputEvent>(searchInput, 'input').pipe(
  map((e) => (e.target as HTMLInputElement).value.trim()),
  filter((query) => query.length >= 2),
  debounceTime(300),
  distinctUntilChanged(),
  switchMap((query) =>
    ajax.getJSON<string[]>(`/api/search?q=${encodeURIComponent(query)}`).pipe(
      catchError(() => of([])),  // Graceful degradation on API error
    ),
  ),
);

// POLLING with pause/resume
const polling$ = timer(0, 5000).pipe(
  switchMap(() => ajax.getJSON('/api/status')),
  retry({ count: 3, delay: 2000 }),
  takeUntil(stopPolling$),
  share(),  // Multicast to all subscribers
);
```

---

## 5. Reactive Patterns in Node.js

### Event-Driven Architecture with RxJS

```typescript
import { Subject, merge, groupBy, mergeMap, bufferTime, filter } from 'rxjs';

// Central event bus using RxJS Subject
interface DomainEvent {
  type: string;
  payload: any;
  timestamp: number;
  correlationId: string;
}

class EventBus {
  private events$ = new Subject<DomainEvent>();

  emit(event: DomainEvent): void {
    this.events$.next(event);
  }

  on(eventType: string) {
    return this.events$.pipe(
      filter((e) => e.type === eventType),
    );
  }

  // Batch events by type for efficient processing
  onBatched(eventType: string, windowMs: number, maxBatchSize: number) {
    return this.events$.pipe(
      filter((e) => e.type === eventType),
      bufferTime(windowMs, null, maxBatchSize),
      filter((batch) => batch.length > 0),
    );
  }

  destroy(): void {
    this.events$.complete();
  }
}

// Usage
const bus = new EventBus();

// Process order events in batches of up to 50, or every 2 seconds
bus.onBatched('order.created', 2000, 50).subscribe(async (orders) => {
  await inventoryService.reserveBatch(orders.map((o) => o.payload));
});

// Group events by user for per-user rate limiting
bus.on('api.request').pipe(
  groupBy((event) => event.payload.userId),
  mergeMap((group$) =>
    group$.pipe(
      throttleTime(1000),  // Max 1 request per second per user
    ),
  ),
).subscribe((event) => processRequest(event));
```

### Reactive Database Queries

```typescript
import { Observable, from, defer, retry, timer } from 'rxjs';
import { mergeMap, toArray, bufferCount } from 'rxjs/operators';
import { Pool } from 'pg';

class ReactiveRepository {
  constructor(private pool: Pool) {}

  // Stream query results as an Observable (no loading entire result set)
  query$<T>(sql: string, params: any[] = []): Observable<T> {
    return new Observable<T>((subscriber) => {
      const client = this.pool.connect();
      let cursor: any;

      client.then(async (conn) => {
        try {
          cursor = conn.query(new Cursor(sql, params));

          const readBatch = async () => {
            const rows = await cursor.read(100);
            if (rows.length === 0) {
              subscriber.complete();
              return;
            }
            for (const row of rows) {
              subscriber.next(row as T);
            }
            await readBatch();
          };

          await readBatch();
        } catch (err) {
          subscriber.error(err);
        } finally {
          cursor?.close();
          conn.release();
        }
      });

      return () => {
        cursor?.close();
      };
    });
  }

  // Batch inserts with backpressure
  bulkInsert$<T>(items$: Observable<T>, tableName: string, batchSize = 500): Observable<number> {
    return items$.pipe(
      bufferCount(batchSize),
      mergeMap(
        (batch) => defer(() => from(this.insertBatch(tableName, batch))),
        2,  // Max 2 concurrent batch inserts (backpressure)
      ),
    );
  }

  private async insertBatch(tableName: string, rows: any[]): Promise<number> {
    // ... batch INSERT logic
    return rows.length;
  }
}
```

### Combining RxJS with NestJS

```typescript
import { Controller, Sse, MessageEvent } from '@nestjs/common';
import { Observable, interval, map, switchMap, startWith, share } from 'rxjs';

@Controller('dashboard')
export class DashboardController {
  constructor(private metricsService: MetricsService) {}

  // Server-Sent Events endpoint using Observable
  @Sse('metrics')
  streamMetrics(): Observable<MessageEvent> {
    return interval(5000).pipe(
      startWith(0),
      switchMap(() => this.metricsService.getCurrent()),
      map((metrics) => ({
        data: metrics,
        type: 'metrics',
        id: String(Date.now()),
      })),
      share(),  // Share across all connected SSE clients
    );
  }
}

// NestJS Interceptor for response timing with RxJS
import { Injectable, NestInterceptor, ExecutionContext, CallHandler } from '@nestjs/common';
import { tap, catchError } from 'rxjs/operators';

@Injectable()
export class TimingInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const start = Date.now();
    return next.handle().pipe(
      tap(() => {
        const duration = Date.now() - start;
        console.log(`${context.getHandler().name}: ${duration}ms`);
      }),
      catchError((err) => {
        const duration = Date.now() - start;
        console.error(`${context.getHandler().name}: FAILED after ${duration}ms`);
        throw err;
      }),
    );
  }
}
```

### CQRS with Reactive Event Handling

```typescript
import { Subject, Observable, groupBy, mergeMap, bufferTime, filter } from 'rxjs';

// Command side — write model
interface Command {
  type: 'CreateOrder' | 'CancelOrder' | 'UpdateInventory';
  payload: any;
  metadata: { userId: string; timestamp: number; correlationId: string };
}

// Event side — events produced by command handlers
interface DomainEvent {
  type: string;
  aggregateId: string;
  payload: any;
  version: number;
  timestamp: number;
}

class CqrsEventStore {
  private commandBus$ = new Subject<Command>();
  private eventBus$ = new Subject<DomainEvent>();

  dispatch(command: Command): void {
    this.commandBus$.next(command);
  }

  // Process commands sequentially per aggregate to maintain consistency
  commands$(type: Command['type']): Observable<Command> {
    return this.commandBus$.pipe(
      filter((cmd) => cmd.type === type),
    );
  }

  // Subscribe to domain events — projections read from here
  events$(eventType?: string): Observable<DomainEvent> {
    return this.eventBus$.pipe(
      filter((e) => !eventType || e.type === eventType),
    );
  }

  publish(event: DomainEvent): void {
    this.eventBus$.next(event);
  }
}

// Read model projection — updates a materialized view
class OrderSummaryProjection {
  constructor(private store: CqrsEventStore, private db: any) {
    // Batch event processing for efficiency
    store.events$('OrderCreated').pipe(
      bufferTime(1000, null, 100),
      filter((batch) => batch.length > 0),
      mergeMap((batch) => this.updateSummaryView(batch)),
    ).subscribe({
      error: (err) => console.error('Projection error:', err),
    });
  }

  private async updateSummaryView(events: DomainEvent[]): Promise<void> {
    const updates = events.map((e) => ({
      orderId: e.aggregateId,
      total: e.payload.total,
      status: 'created',
      createdAt: new Date(e.timestamp),
    }));
    await this.db.collection('order_summaries').insertMany(updates);
  }
}
```

---

## 6. Server-Sent Events (SSE)

### SSE Protocol and Event Format

SSE is a one-directional protocol where the server pushes events to the client over a persistent HTTP connection. The client uses the `EventSource` API. The response MUST use `Content-Type: text/event-stream`.

```
  SSE Protocol Flow:
  
  Client                                    Server
    │                                         │
    │  GET /events HTTP/1.1                   │
    │  Accept: text/event-stream              │
    │────────────────────────────────────────►│
    │                                         │
    │  HTTP/1.1 200 OK                        │
    │  Content-Type: text/event-stream        │
    │  Cache-Control: no-cache                │
    │  Connection: keep-alive                 │
    │◄────────────────────────────────────────│
    │                                         │
    │  data: {"temp": 72}\n\n                 │
    │◄────────────────────────────────────────│
    │                                         │
    │  event: alert\n                         │
    │  data: {"level": "high"}\n\n            │
    │◄────────────────────────────────────────│
    │                                         │
    │  id: 42\n                               │
    │  event: metric\n                        │
    │  data: {"cpu": 0.85}\n\n                │
    │◄────────────────────────────────────────│
    │                                         │
    │  (connection drops)                     │
    │                                         │
    │  GET /events HTTP/1.1                   │
    │  Last-Event-ID: 42                      │
    │────────────────────────────────────────►│
    │  (server replays events after ID 42)    │
```

Event format rules:
- Each field is `field: value\n`
- Events are separated by `\n\n` (blank line)
- Fields: `data`, `event`, `id`, `retry`
- Lines starting with `:` are comments (used as keepalive)
- Multi-line data: repeat `data:` on each line

### Implementing SSE with Express

```typescript
import express, { Request, Response } from 'express';

const app = express();

// Connection registry for broadcasting
const clients = new Map<string, Response>();

app.get('/events', (req: Request, res: Response) => {
  // SSE headers
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no',  // Disable nginx buffering
  });

  const clientId = crypto.randomUUID();
  clients.set(clientId, res);

  // Handle reconnection — replay missed events
  const lastEventId = req.headers['last-event-id'];
  if (lastEventId) {
    replayEventsAfter(res, lastEventId);
  }

  // Keepalive comment every 15 seconds to prevent proxy timeouts
  const keepalive = setInterval(() => {
    res.write(':keepalive\n\n');
  }, 15000);

  // Cleanup on disconnect
  req.on('close', () => {
    clearInterval(keepalive);
    clients.delete(clientId);
  });
});

// Helper to send SSE events
function sendEvent(res: Response, event: string, data: any, id?: string): void {
  if (id) res.write(`id: ${id}\n`);
  res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(data)}\n\n`);
}

// Broadcast to all connected clients
function broadcast(event: string, data: any): void {
  const id = String(Date.now());
  for (const [clientId, res] of clients) {
    try {
      sendEvent(res, event, data, id);
    } catch {
      clients.delete(clientId);
    }
  }
}
```

### Implementing SSE with NestJS

```typescript
import { Controller, Sse, Param, Req, MessageEvent } from '@nestjs/common';
import { Observable, Subject, filter, map, finalize } from 'rxjs';
import { Request } from 'express';

@Controller('notifications')
export class NotificationController {
  private events$ = new Subject<{ userId: string; event: MessageEvent }>();

  @Sse(':userId/stream')
  stream(
    @Param('userId') userId: string,
    @Req() req: Request,
  ): Observable<MessageEvent> {
    // NestJS handles SSE headers automatically for @Sse endpoints
    const lastEventId = req.headers['last-event-id'] as string;

    return this.events$.pipe(
      filter((e) => e.userId === userId),
      map((e) => e.event),
      finalize(() => {
        console.log(`Client ${userId} disconnected`);
      }),
    );
  }

  // Called by other services to push notifications
  notify(userId: string, type: string, payload: any): void {
    this.events$.next({
      userId,
      event: {
        data: payload,
        type,
        id: String(Date.now()),
      },
    });
  }
}
```

### SSE vs WebSockets

| Aspect | SSE | WebSockets |
|--------|-----|------------|
| **Direction** | Server to client only | Bidirectional |
| **Protocol** | HTTP/1.1 or HTTP/2 | ws:// or wss:// (upgrade from HTTP) |
| **Reconnection** | Automatic (built into EventSource) | Manual (must implement) |
| **Resume** | Built-in via `Last-Event-ID` | Not built-in |
| **Data format** | Text only (JSON as string) | Text and binary |
| **Browser API** | `EventSource` (simple) | `WebSocket` (more complex) |
| **HTTP/2 multiplexing** | Yes (shares connection) | No (separate TCP per socket) |
| **Proxy/firewall** | Works through HTTP proxies | May be blocked by corporate proxies |
| **Max connections** | Browser limit: 6 per domain (HTTP/1.1) | No browser limit |
| **Compression** | Standard HTTP compression | permessage-deflate (complex) |
| **Load balancing** | Standard HTTP LB | Sticky sessions needed |
| **Best for** | Notifications, feeds, dashboards | Chat, gaming, collaborative editing |

### Scaling SSE Across Servers

```
  Multi-Server SSE with Redis Pub/Sub:
  
  Client A ──► Server 1 ◄──┐
  Client B ──► Server 1    │
                            ├── Redis Pub/Sub ◄── Event Producer
  Client C ──► Server 2    │
  Client D ──► Server 2 ◄──┘
  
  Each server subscribes to Redis and fans out to its local SSE clients.
```

```typescript
import Redis from 'ioredis';

class ScalableSSEBroker {
  private redisSub: Redis;
  private redisPub: Redis;
  private localClients = new Map<string, Response>();

  constructor(redisUrl: string) {
    this.redisSub = new Redis(redisUrl);
    this.redisPub = new Redis(redisUrl);

    // Subscribe to SSE channel — receives events from all servers
    this.redisSub.subscribe('sse:events');
    this.redisSub.on('message', (channel, message) => {
      const { event, data, targetUserId } = JSON.parse(message);
      this.fanOutToLocal(event, data, targetUserId);
    });
  }

  // Publish event — reaches all servers via Redis
  publish(event: string, data: any, targetUserId?: string): void {
    this.redisPub.publish(
      'sse:events',
      JSON.stringify({ event, data, targetUserId }),
    );
  }

  private fanOutToLocal(event: string, data: any, targetUserId?: string): void {
    for (const [clientId, res] of this.localClients) {
      if (!targetUserId || clientId.startsWith(targetUserId)) {
        sendEvent(res, event, data);
      }
    }
  }

  addClient(clientId: string, res: Response): void {
    this.localClients.set(clientId, res);
  }

  removeClient(clientId: string): void {
    this.localClients.delete(clientId);
  }
}
```

---

## 7. WebSocket Patterns

### ws Library — Raw WebSocket Server

```typescript
import { WebSocketServer, WebSocket } from 'ws';
import { createServer } from 'http';

const server = createServer();
const wss = new WebSocketServer({ server });

// Connection handling
wss.on('connection', (ws: WebSocket, req) => {
  const clientIp = req.socket.remoteAddress;
  console.log(`Client connected from ${clientIp}`);

  // Message handling
  ws.on('message', (data: Buffer, isBinary: boolean) => {
    if (isBinary) {
      // Handle binary data (e.g., file upload, protobuf)
      processBinaryMessage(data);
    } else {
      const message = JSON.parse(data.toString());
      handleMessage(ws, message);
    }
  });

  ws.on('close', (code: number, reason: Buffer) => {
    console.log(`Client disconnected: ${code} ${reason.toString()}`);
  });

  ws.on('error', (err: Error) => {
    console.error('WebSocket error:', err);
  });
});

// Broadcast to all connected clients
function broadcast(data: any, sender?: WebSocket): void {
  const message = JSON.stringify(data);
  wss.clients.forEach((client) => {
    if (client !== sender && client.readyState === WebSocket.OPEN) {
      client.send(message);
    }
  });
}

server.listen(8080);
```

### Authentication for WebSocket Connections

```typescript
import { WebSocketServer, WebSocket } from 'ws';
import { IncomingMessage } from 'http';
import jwt from 'jsonwebtoken';

const wss = new WebSocketServer({ noServer: true });

// Authenticate DURING the HTTP upgrade — before WebSocket handshake completes
server.on('upgrade', (request: IncomingMessage, socket, head) => {
  // Option 1: Token in query string
  const url = new URL(request.url!, `http://${request.headers.host}`);
  const token = url.searchParams.get('token');

  // Option 2: Token in Sec-WebSocket-Protocol header (browser-friendly)
  // const token = request.headers['sec-websocket-protocol'];

  if (!token) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  try {
    const user = jwt.verify(token, process.env.JWT_SECRET!) as JwtPayload;

    // Complete the upgrade with authenticated user context
    wss.handleUpgrade(request, socket, head, (ws) => {
      (ws as any).user = user;  // Attach user to connection
      wss.emit('connection', ws, request);
    });
  } catch (err) {
    socket.write('HTTP/1.1 403 Forbidden\r\n\r\n');
    socket.destroy();
  }
});
```

**Production gotcha:** You cannot send custom HTTP headers from the browser `WebSocket` API. The common patterns are: (1) token in query string (simpler, but logged in URLs), (2) token in `Sec-WebSocket-Protocol` header, or (3) authenticate after connection with a first message (requires an unauthenticated window).

### Heartbeat / Ping-Pong for Connection Health

Stale connections (where one side crashed without closing properly) consume resources indefinitely. Ping/pong detects and cleans up dead connections.

```typescript
import { WebSocketServer, WebSocket } from 'ws';

const wss = new WebSocketServer({ server });

// Track alive state per connection
wss.on('connection', (ws: WebSocket) => {
  (ws as any).isAlive = true;

  ws.on('pong', () => {
    (ws as any).isAlive = true;  // Client responded to our ping
  });

  ws.on('close', () => {
    (ws as any).isAlive = false;
  });
});

// Sweep every 30 seconds — terminate dead connections
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!(ws as any).isAlive) {
      console.log('Terminating stale connection');
      return ws.terminate();  // Hard close — no close frame sent
    }

    (ws as any).isAlive = false;
    ws.ping();  // Send ping — expect pong back before next sweep
  });
}, 30000);

wss.on('close', () => {
  clearInterval(heartbeat);
});
```

### Socket.IO — Namespaces, Rooms, Acknowledgments

```typescript
import { Server } from 'socket.io';

const io = new Server(server, {
  cors: { origin: '*' },
  pingInterval: 25000,       // Heartbeat interval
  pingTimeout: 20000,        // Disconnect if no pong within this
  maxHttpBufferSize: 1e6,    // Max message size (1MB)
  transports: ['websocket'], // Skip long-polling for lower latency
});

// --- Namespaces: separate communication channels on same connection ---
const adminNs = io.of('/admin');
const chatNs = io.of('/chat');

// Namespace-level middleware
adminNs.use((socket, next) => {
  const token = socket.handshake.auth.token;
  if (verifyAdminToken(token)) {
    next();
  } else {
    next(new Error('Unauthorized'));
  }
});

// --- Rooms: groups within a namespace ---
chatNs.on('connection', (socket) => {
  // Join a room
  socket.on('joinRoom', (roomId: string) => {
    socket.join(roomId);
    chatNs.to(roomId).emit('userJoined', { userId: socket.id });
  });

  // Send to specific room
  socket.on('message', (data: { roomId: string; text: string }) => {
    chatNs.to(data.roomId).emit('message', {
      from: socket.id,
      text: data.text,
      timestamp: Date.now(),
    });
  });

  // --- Acknowledgments: request-response over WebSocket ---
  socket.on('saveMessage', (data, callback) => {
    try {
      const saved = messageService.save(data);
      callback({ status: 'ok', messageId: saved.id });  // Response to client
    } catch (err) {
      callback({ status: 'error', message: (err as Error).message });
    }
  });

  // Leave room on disconnect
  socket.on('disconnecting', () => {
    // socket.rooms contains rooms being left
    for (const room of socket.rooms) {
      if (room !== socket.id) {
        chatNs.to(room).emit('userLeft', { userId: socket.id });
      }
    }
  });
});
```

### Reconnection Strategies

```typescript
// Client-side reconnection with exponential backoff
class ReconnectingWebSocket {
  private ws: WebSocket | null = null;
  private retryCount = 0;
  private maxRetries = 10;
  private baseDelay = 1000;
  private maxDelay = 30000;
  private url: string;

  constructor(url: string) {
    this.url = url;
    this.connect();
  }

  private connect(): void {
    this.ws = new WebSocket(this.url);

    this.ws.onopen = () => {
      console.log('Connected');
      this.retryCount = 0;  // Reset on successful connection
    };

    this.ws.onclose = (event) => {
      if (event.code === 1000) return;  // Normal close — don't reconnect

      if (this.retryCount < this.maxRetries) {
        const delay = Math.min(
          this.baseDelay * Math.pow(2, this.retryCount) + Math.random() * 1000,
          this.maxDelay,
        );
        console.log(`Reconnecting in ${delay}ms (attempt ${this.retryCount + 1})`);
        setTimeout(() => this.connect(), delay);
        this.retryCount++;
      } else {
        console.error('Max retries reached');
      }
    };
  }

  send(data: any): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    } else {
      // Queue messages while disconnected (bounded!)
      this.messageQueue.push(data);
    }
  }

  private messageQueue: any[] = [];
}
```

### Message Compression

```typescript
import { WebSocketServer } from 'ws';

const wss = new WebSocketServer({
  server,
  perMessageDeflate: {
    zlibDeflateOptions: {
      chunkSize: 1024,
      memLevel: 7,
      level: 3,            // Compression level 1-9 (3 = good speed/ratio balance)
    },
    zlibInflateOptions: {
      chunkSize: 10 * 1024,
    },
    clientNoContextTakeover: true,  // Don't keep compression context between messages
    serverNoContextTakeover: true,  // Saves memory at cost of compression ratio
    threshold: 1024,                // Only compress messages > 1KB
  },
});
```

**Production gotcha:** `perMessageDeflate` significantly increases memory per connection (up to 300 KB per socket for compression context). With 10K connections, that is 3 GB just for compression state. For high-connection-count servers, disable it or use `clientNoContextTakeover: true` / `serverNoContextTakeover: true` to reduce memory at the cost of compression ratio.

---

## 8. Streaming Architecture Patterns

### Stream Processing Pipeline Design

```
  Production ETL Pipeline:
  
  ┌────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
  │  S3    │    │  Parse   │    │ Validate │    │Transform │    │  Load    │
  │ Source │───►│  CSV     │───►│ & Filter │───►│ & Enrich │───►│ Database │
  │        │    │          │    │          │    │          │    │          │
  └────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
  Readable       Transform       Transform       Transform       Writable
                                                    
  Backpressure flows RIGHT to LEFT ◄────────────────────────────────────────
  
  If DB insert is slow, Transform pauses, Validate pauses, Parse pauses,
  S3 read pauses. Memory stays bounded.
```

### ETL with Node.js Streams

```typescript
import { pipeline } from 'stream/promises';
import { createReadStream, createWriteStream } from 'fs';
import { Transform, TransformCallback } from 'stream';
import { parse } from 'csv-parse';

// Step 1: Parse CSV with streaming parser
const csvParser = parse({
  columns: true,
  skip_empty_lines: true,
  cast: true,
  // Handles backpressure automatically
});

// Step 2: Validate and filter
class ValidationTransform extends Transform {
  private validCount = 0;
  private invalidCount = 0;

  constructor() {
    super({ objectMode: true });
  }

  _transform(record: any, encoding: string, callback: TransformCallback): void {
    if (!record.email || !record.email.includes('@')) {
      this.invalidCount++;
      callback();  // Skip invalid — call callback without pushing
      return;
    }
    if (record.age && (record.age < 0 || record.age > 150)) {
      this.invalidCount++;
      callback();
      return;
    }
    this.validCount++;
    this.push(record);
    callback();
  }

  _flush(callback: TransformCallback): void {
    console.log(`Validation: ${this.validCount} valid, ${this.invalidCount} invalid`);
    callback();
  }
}

// Step 3: Transform and enrich
class EnrichmentTransform extends Transform {
  constructor() {
    super({ objectMode: true, highWaterMark: 50 });
  }

  _transform(record: any, encoding: string, callback: TransformCallback): void {
    this.push({
      ...record,
      email_domain: record.email.split('@')[1],
      processed_at: new Date().toISOString(),
      full_name: `${record.first_name} ${record.last_name}`.trim(),
    });
    callback();
  }
}

// Step 4: Batch writer — accumulates objects and writes in batches
class BatchDatabaseWriter extends Transform {
  private batch: any[] = [];
  private batchSize: number;

  constructor(private db: any, batchSize = 500) {
    super({ objectMode: true, readableObjectMode: true });
    this.batchSize = batchSize;
  }

  async _transform(record: any, encoding: string, callback: TransformCallback): Promise<void> {
    this.batch.push(record);
    if (this.batch.length >= this.batchSize) {
      try {
        await this.writeBatch();
        callback();
      } catch (err) {
        callback(err as Error);
      }
    } else {
      callback();
    }
  }

  async _flush(callback: TransformCallback): Promise<void> {
    try {
      if (this.batch.length > 0) {
        await this.writeBatch();
      }
      callback();
    } catch (err) {
      callback(err as Error);
    }
  }

  private async writeBatch(): Promise<void> {
    const toWrite = this.batch.splice(0, this.batchSize);
    await this.db.collection('users').insertMany(toWrite);
    console.log(`Wrote batch of ${toWrite.length} records`);
  }
}

// Full pipeline — backpressure flows automatically through all stages
async function runETL(inputFile: string, db: any): Promise<void> {
  await pipeline(
    createReadStream(inputFile),
    csvParser,
    new ValidationTransform(),
    new EnrichmentTransform(),
    new BatchDatabaseWriter(db, 500),
  );
  console.log('ETL complete');
}
```

### File Processing at Scale — Streaming JSON

Processing a 10 GB JSON file without loading it into memory:

```typescript
import { pipeline } from 'stream/promises';
import { createReadStream } from 'fs';
import { parser } from 'stream-json';
import { streamArray } from 'stream-json/streamers/StreamArray';
import { pick } from 'stream-json/filters/Pick';
import { Transform, TransformCallback } from 'stream';

// stream-json parses JSON incrementally without loading entire file
async function processLargeJSON(filePath: string): Promise<void> {
  let count = 0;

  await pipeline(
    createReadStream(filePath),
    parser(),                          // Tokenize JSON incrementally
    pick({ filter: 'records' }),       // Navigate to "records" array
    streamArray(),                     // Emit each array element
    new Transform({
      objectMode: true,
      transform(chunk, encoding, callback) {
        const record = chunk.value;    // .value contains the actual object
        count++;

        // Process each record without holding entire file in memory
        if (count % 100000 === 0) {
          console.log(`Processed ${count} records`);
        }

        callback();
      },
    }),
  );

  console.log(`Total: ${count} records`);
}

// JSONStream alternative — filter with JSONPath-like patterns
import JSONStream from 'JSONStream';

async function filterLargeJSON(filePath: string): Promise<any[]> {
  const results: any[] = [];

  await pipeline(
    createReadStream(filePath),
    JSONStream.parse('users.*.orders.*'),  // Deep path extraction
    new Transform({
      objectMode: true,
      transform(order: any, encoding: string, callback: TransformCallback) {
        if (order.total > 100) {
          results.push(order);
        }
        callback();
      },
    }),
  );

  return results;
}
```

### Real-Time Analytics Pipeline

```typescript
import { Subject, Observable, bufferTime, groupBy, mergeMap, scan, map, filter } from 'rxjs';

interface AnalyticsEvent {
  type: 'pageview' | 'click' | 'purchase';
  userId: string;
  page: string;
  value?: number;
  timestamp: number;
}

class RealTimeAnalytics {
  private events$ = new Subject<AnalyticsEvent>();

  ingest(event: AnalyticsEvent): void {
    this.events$.next(event);
  }

  // Pages per second — sliding window
  pageViewsPerSecond(): Observable<number> {
    return this.events$.pipe(
      filter((e) => e.type === 'pageview'),
      bufferTime(1000),
      map((batch) => batch.length),
    );
  }

  // Revenue per minute grouped by page
  revenueByPage(): Observable<Map<string, number>> {
    return this.events$.pipe(
      filter((e) => e.type === 'purchase' && e.value !== undefined),
      bufferTime(60000),
      map((batch) => {
        const revenue = new Map<string, number>();
        for (const event of batch) {
          const current = revenue.get(event.page) || 0;
          revenue.set(event.page, current + (event.value || 0));
        }
        return revenue;
      }),
    );
  }

  // Active users in last 5 minutes (cardinality)
  activeUsers(): Observable<number> {
    return this.events$.pipe(
      bufferTime(5000),  // Check every 5 seconds
      scan((activeSet: Map<string, number>, batch: AnalyticsEvent[]) => {
        const now = Date.now();
        const fiveMinutesAgo = now - 5 * 60 * 1000;

        // Add new users
        for (const event of batch) {
          activeSet.set(event.userId, event.timestamp);
        }

        // Evict stale users
        for (const [userId, lastSeen] of activeSet) {
          if (lastSeen < fiveMinutesAgo) {
            activeSet.delete(userId);
          }
        }

        return activeSet;
      }, new Map<string, number>()),
      map((activeSet) => activeSet.size),
    );
  }
}
```

### gRPC Streaming

gRPC supports four streaming patterns. Node.js gRPC uses the `@grpc/grpc-js` library.

```
  gRPC Streaming Patterns:
  
  1. Unary (request-response):
     Client ──[Request]──► Server
     Client ◄──[Response]── Server
  
  2. Server streaming:
     Client ──[Request]──────────────► Server
     Client ◄──[Response 1]─────────── Server
     Client ◄──[Response 2]─────────── Server
     Client ◄──[Response N]─────────── Server
  
  3. Client streaming:
     Client ──[Request 1]───────────► Server
     Client ──[Request 2]───────────► Server
     Client ──[Request N]───────────► Server
     Client ◄──[Response]─────────── Server
  
  4. Bidirectional streaming:
     Client ──[Request 1]───────────► Server
     Client ◄──[Response 1]─────────── Server
     Client ──[Request 2]───────────► Server
     Client ◄──[Response 2]─────────── Server
     (independent streams, not request-response pairs)
```

```typescript
// Proto definition
// service DataService {
//   rpc GetStream (Request) returns (stream DataPoint);        // Server streaming
//   rpc IngestStream (stream DataPoint) returns (Summary);     // Client streaming
//   rpc BiDiStream (stream DataPoint) returns (stream Result); // Bidirectional
// }

import * as grpc from '@grpc/grpc-js';

// Server streaming — server pushes results as they become available
function getStream(
  call: grpc.ServerWritableStream<Request, DataPoint>,
): void {
  const query = call.request;

  // Stream database results to client
  const cursor = db.query(query.sql);

  cursor.on('data', (row: DataPoint) => {
    const canContinue = call.write(row);
    if (!canContinue) {
      cursor.pause();  // Backpressure: client is slow
    }
  });

  call.on('drain', () => {
    cursor.resume();  // Client caught up
  });

  cursor.on('end', () => {
    call.end();  // Signal stream completion
  });

  cursor.on('error', (err: Error) => {
    call.destroy(err);
  });

  // Client cancelled the stream
  call.on('cancelled', () => {
    cursor.destroy();
  });
}

// Client streaming — client sends stream, server responds with summary
function ingestStream(
  call: grpc.ServerReadableStream<DataPoint, Summary>,
  callback: grpc.sendUnaryData<Summary>,
): void {
  let count = 0;
  let totalValue = 0;

  call.on('data', (point: DataPoint) => {
    count++;
    totalValue += point.value;
  });

  call.on('end', () => {
    callback(null, { count, average: totalValue / count });
  });

  call.on('error', (err: Error) => {
    callback(err, null);
  });
}

// Bidirectional streaming — independent read/write streams
function biDiStream(
  call: grpc.ServerDuplexStream<DataPoint, Result>,
): void {
  call.on('data', async (point: DataPoint) => {
    // Process each incoming point and write result back
    const result = await processDataPoint(point);
    call.write(result);
  });

  call.on('end', () => {
    call.end();
  });
}
```

### Log Aggregation with Streams

```typescript
import { pipeline } from 'stream/promises';
import { createReadStream } from 'fs';
import { createInterface } from 'readline';
import { Transform, TransformCallback, Writable } from 'stream';
import { createGunzip } from 'zlib';
import { glob } from 'glob';

interface LogEntry {
  timestamp: Date;
  level: string;
  service: string;
  message: string;
  metadata?: Record<string, any>;
}

// Parse structured log lines
class LogParser extends Transform {
  constructor() {
    super({ objectMode: true });
  }

  _transform(line: Buffer, encoding: string, callback: TransformCallback): void {
    const str = line.toString().trim();
    if (!str) {
      callback();
      return;
    }

    try {
      const parsed = JSON.parse(str);
      this.push({
        timestamp: new Date(parsed.timestamp),
        level: parsed.level,
        service: parsed.service,
        message: parsed.msg,
        metadata: parsed.meta,
      } as LogEntry);
    } catch {
      // Non-JSON log line — wrap it
      this.push({
        timestamp: new Date(),
        level: 'INFO',
        service: 'unknown',
        message: str,
      } as LogEntry);
    }
    callback();
  }
}

// Aggregate errors by service in a time window
class ErrorAggregator extends Transform {
  private window = new Map<string, { count: number; samples: string[] }>();
  private flushInterval: NodeJS.Timeout;

  constructor(private windowMs = 60000) {
    super({ objectMode: true });
    this.flushInterval = setInterval(() => this.emitWindow(), windowMs);
  }

  _transform(entry: LogEntry, encoding: string, callback: TransformCallback): void {
    if (entry.level === 'ERROR' || entry.level === 'FATAL') {
      const key = entry.service;
      const existing = this.window.get(key) || { count: 0, samples: [] };
      existing.count++;
      if (existing.samples.length < 5) {
        existing.samples.push(entry.message);
      }
      this.window.set(key, existing);
    }
    callback();
  }

  private emitWindow(): void {
    if (this.window.size > 0) {
      this.push({
        windowEnd: new Date(),
        errors: Object.fromEntries(this.window),
      });
      this.window.clear();
    }
  }

  _flush(callback: TransformCallback): void {
    clearInterval(this.flushInterval);
    this.emitWindow();
    callback();
  }

  _destroy(error: Error | null, callback: (error?: Error | null) => void): void {
    clearInterval(this.flushInterval);
    callback(error);
  }
}

// Process multiple compressed log files in parallel with bounded concurrency
async function aggregateLogs(logDir: string): Promise<void> {
  const files = await glob(`${logDir}/**/*.log.gz`);

  // Process 4 files concurrently (bounded parallelism)
  const concurrency = 4;
  const results: any[] = [];

  for (let i = 0; i < files.length; i += concurrency) {
    const batch = files.slice(i, i + concurrency);
    await Promise.all(
      batch.map(async (file) => {
        await pipeline(
          createReadStream(file),
          createGunzip(),
          new LogParser(),
          new ErrorAggregator(60000),
          new Writable({
            objectMode: true,
            write(chunk, encoding, callback) {
              results.push(chunk);
              callback();
            },
          }),
        );
      }),
    );
  }

  console.log('Aggregated error windows:', results.length);
}
```

### Stream Processing Performance Considerations

```
  Memory Profile Comparison (processing 5 GB file):
  
  Approach              Peak Memory    Time      Notes
  ─────────────────────────────────────────────────────────────────
  fs.readFile()         ~5.2 GB        12s       Entire file in memory
  readFileSync()        ~5.2 GB        14s       Blocks event loop
  Stream (16KB HWM)     ~32 MB         15s       Bounded, non-blocking
  Stream (1MB HWM)      ~4 MB          13s       Slightly less overhead
  Stream (objectMode)   ~varies        varies    Depends on object size
  
  Key tuning parameters:
  
  ┌──────────────────────┬───────────────┬────────────────────────────────┐
  │ Parameter            │ Default       │ Impact                         │
  ├──────────────────────┼───────────────┼────────────────────────────────┤
  │ highWaterMark        │ 16384 (16KB)  │ Higher = fewer reads, more     │
  │ (binary)             │               │ memory. Lower = more reads,    │
  │                      │               │ less memory.                   │
  ├──────────────────────┼───────────────┼────────────────────────────────┤
  │ highWaterMark        │ 16 objects    │ Same tradeoff but counted in   │
  │ (object mode)        │               │ objects, not bytes. Objects     │
  │                      │               │ can be arbitrarily large!      │
  ├──────────────────────┼───────────────┼────────────────────────────────┤
  │ Batch size           │ N/A           │ Batching reduces per-item      │
  │ (custom)             │               │ overhead (DB inserts, network) │
  ├──────────────────────┼───────────────┼────────────────────────────────┤
  │ Concurrency          │ Unlimited     │ mergeMap concurrency param or  │
  │                      │ (mergeMap)    │ manual semaphore to bound      │
  │                      │               │ parallel async work            │
  └──────────────────────┴───────────────┴────────────────────────────────┘
```

### Production Gotchas Summary

| Problem | Symptom | Solution |
|---------|---------|----------|
| Ignoring `write()` return value | OOM on large data | Check return, pause on `false`, resume on `drain` |
| Using `pipe()` instead of `pipeline()` | Leaked file descriptors on error | Always use `pipeline()` from `stream/promises` |
| Object mode with large objects | Memory bloat (16 objects could be 16 GB) | Set `highWaterMark` lower, or buffer by byte size |
| No error handler on streams | Uncaught exception crashes process | `pipeline()` or explicit error handlers on every stream |
| Forgetting `_flush()` in Transform | Last chunk lost | Always implement `_flush()` for buffering transforms |
| Sync work in `_transform()` | Blocks event loop | Offload CPU work to worker threads |
| `tee()` with asymmetric consumers | Memory grows unbounded | Use file/queue as intermediate buffer |
| SSE without keepalive | Proxy kills idle connection | Send comment (`:keepalive\n\n`) every 15-30s |
| WebSocket without heartbeat | Ghost connections consume memory | Implement ping/pong sweep |
| `perMessageDeflate` at scale | 300 KB per connection overhead | Disable or use `noContextTakeover` |
| Unbounded RxJS subscriptions | Memory leak in long-lived apps | Use `takeUntil(destroy$)` pattern |
| Hot observable without `share()` | Duplicate side effects | Use `share()` or `shareReplay()` for multicast |
| `BehaviorSubject` without unsubscribe | Subscriber leak | Always unsubscribe or use `takeUntil` |
| gRPC stream without cancel handling | Resource leak when client disconnects | Listen for `cancelled` event |

---
