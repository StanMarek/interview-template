# Concurrency & Multithreading — Senior Engineer Interview Preparation

---

## 1. Thread Lifecycle & Thread Pools

### Thread States

A Java thread exists in exactly one of six states at any given time, defined by `Thread.State`:

```
         start()
NEW ─────────────► RUNNABLE ◄──────────────────────────┐
                     │  ▲                               │
                     │  │ notify()/                     │
        synchronized │  │ notifyAll()/                  │
        lock acquire │  │ lock acquired                 │
        blocked      │  │                               │
                     ▼  │                               │
                   BLOCKED                              │
                                                        │
RUNNABLE ──── wait()/join()/park() ────► WAITING ───────┘
                                                        │
RUNNABLE ── sleep(t)/wait(t)/join(t) ─► TIMED_WAITING ──┘

RUNNABLE ──── run() completes ────────► TERMINATED
```

| State | Description | How to Enter | How to Exit |
|-------|-------------|--------------|-------------|
| `NEW` | Thread created but not started | `new Thread()` | `thread.start()` |
| `RUNNABLE` | Eligible to run (may be executing or waiting for CPU) | `start()`, lock acquired, `notify()` | Various blocking operations |
| `BLOCKED` | Waiting to acquire a monitor lock | Entering `synchronized` block when lock held by another thread | Lock becomes available |
| `WAITING` | Waiting indefinitely for another thread's action | `Object.wait()`, `Thread.join()`, `LockSupport.park()` | `notify()`, `notifyAll()`, `unpark()`, joined thread completes |
| `TIMED_WAITING` | Waiting with a timeout | `Thread.sleep(ms)`, `wait(ms)`, `join(ms)`, `parkNanos()` | Timeout expires or notification |
| `TERMINATED` | Thread has completed execution | `run()` returns or uncaught exception | N/A (terminal state) |

### ExecutorService Strategies

Never create raw threads in production. Use `ExecutorService` to manage thread pools:

```java
import java.util.concurrent.*;

// Fixed thread pool — bounded, predictable resource usage
ExecutorService fixed = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors()
);

// Cached thread pool — unbounded, creates threads on demand, reuses idle threads
// WARNING: can create unlimited threads under load
ExecutorService cached = Executors.newCachedThreadPool();

// Single thread — guarantees sequential execution, task queue is unbounded
ExecutorService single = Executors.newSingleThreadExecutor();

// Scheduled — for delayed/periodic tasks
ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(4);
scheduled.scheduleAtFixedRate(() -> System.out.println("tick"), 0, 1, TimeUnit.SECONDS);

// Work-stealing (Java 8+) — ForkJoinPool underneath, good for recursive/uneven tasks
ExecutorService workStealing = Executors.newWorkStealingPool();

// Virtual thread per-task (Java 21+) — one virtual thread per submitted task
ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
```

### ThreadPoolExecutor Internals

`ThreadPoolExecutor` is the engine behind most `Executors` factory methods:

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,                          // corePoolSize — threads kept alive even when idle
    8,                          // maximumPoolSize — max threads when queue is full
    60L, TimeUnit.SECONDS,      // keepAliveTime — idle time before non-core threads die
    new ArrayBlockingQueue<>(100), // workQueue — holds tasks before execution
    new ThreadPoolExecutor.CallerRunsPolicy() // rejectionHandler
);
```

**Task submission flow:**

1. If `activeThreads < corePoolSize` --> create a new thread
2. If `activeThreads >= corePoolSize` --> enqueue the task
3. If queue is full and `activeThreads < maximumPoolSize` --> create a new thread
4. If queue is full and `activeThreads >= maximumPoolSize` --> apply rejection policy

**Work Queue Types:**

| Queue Type | Behavior | Use Case |
|-----------|----------|----------|
| `LinkedBlockingQueue` (unbounded) | Never triggers thread creation beyond core | Default for `newFixedThreadPool` |
| `ArrayBlockingQueue` (bounded) | Bounded capacity, can trigger max pool size | Production systems needing backpressure |
| `SynchronousQueue` (zero capacity) | Direct handoff, always creates new thread if possible | Default for `newCachedThreadPool` |
| `PriorityBlockingQueue` | Tasks ordered by priority | When some tasks are more urgent |

**Rejection Policies:**

| Policy | Behavior |
|--------|----------|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` |
| `CallerRunsPolicy` | Executes task in the caller's thread (provides backpressure) |
| `DiscardPolicy` | Silently drops the task |
| `DiscardOldestPolicy` | Drops the oldest queued task, then retries |

### Best Practices for Pool Sizing

- **CPU-bound tasks:** `poolSize = N_cpus` (or `N_cpus + 1` to account for occasional page faults)
- **IO-bound tasks:** `poolSize = N_cpus * (1 + W/C)` where `W` = wait time, `C` = compute time
- **Mixed workloads:** Separate pools for CPU-bound and IO-bound work
- Always use bounded queues in production to prevent OOM
- Use `CallerRunsPolicy` for natural backpressure
- With virtual threads (Java 21+), pool sizing for IO-bound work is largely irrelevant — use `newVirtualThreadPerTaskExecutor()`

---

## 2. CompletableFuture

`CompletableFuture<T>` is the cornerstone of asynchronous programming in Java. It represents a future result that can be composed, combined, and chained.

### Creation

```java
// Async computation returning a value — uses ForkJoinPool.commonPool() by default
CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(() -> fetchFromApi());

// Async computation returning void
CompletableFuture<Void> cf2 = CompletableFuture.runAsync(() -> sendNotification());

// With a custom executor (recommended for IO-bound work)
ExecutorService ioPool = Executors.newFixedThreadPool(10);
CompletableFuture<String> cf3 = CompletableFuture.supplyAsync(() -> fetchFromApi(), ioPool);

// Already-completed future
CompletableFuture<String> cf4 = CompletableFuture.completedFuture("done");
```

### Chaining (Transformation & Composition)

```java
CompletableFuture<String> result = CompletableFuture
    .supplyAsync(() -> fetchUserId())           // CompletableFuture<Long>
    .thenApply(id -> fetchUserName(id))         // sync transform: T -> U
    .thenApply(name -> "Hello, " + name);       // another sync transform

// thenCompose — flat map, when the function itself returns a CompletableFuture
CompletableFuture<Order> order = CompletableFuture
    .supplyAsync(() -> fetchUserId())
    .thenCompose(id -> fetchOrderAsync(id));    // async: T -> CompletableFuture<U>

// thenCombine — combine two independent futures
CompletableFuture<String> summary = fetchUserAsync()
    .thenCombine(fetchOrderAsync(), (user, order) ->
        user.name() + " ordered " + order.item());

// thenAccept — consume result without returning (terminal)
fetchUserAsync().thenAccept(user -> log.info("Fetched: {}", user));

// thenRun — run action after completion, ignores result
fetchUserAsync().thenRun(() -> log.info("Fetch complete"));
```

**Async variants** (`thenApplyAsync`, `thenComposeAsync`, etc.) execute the callback on a different thread from the executor, rather than on the completing thread.

### Error Handling

```java
CompletableFuture<String> robust = CompletableFuture
    .supplyAsync(() -> riskyCall())
    .exceptionally(ex -> {
        log.error("Failed", ex);
        return "fallback";              // recovery value
    });

// handle — receives both result and exception (exactly one is non-null)
CompletableFuture<String> handled = CompletableFuture
    .supplyAsync(() -> riskyCall())
    .handle((result, ex) -> {
        if (ex != null) return "fallback";
        return result.toUpperCase();
    });

// whenComplete — observe result/exception without transforming
CompletableFuture<String> observed = CompletableFuture
    .supplyAsync(() -> riskyCall())
    .whenComplete((result, ex) -> {
        if (ex != null) log.error("Error", ex);
        else log.info("Result: {}", result);
    }); // propagates original result/exception
```

### Combining Multiple Futures

```java
// Wait for ALL to complete
CompletableFuture<Void> all = CompletableFuture.allOf(cf1, cf2, cf3);
all.thenRun(() -> {
    // All futures done — extract results
    String r1 = cf1.join();
    String r2 = cf2.join();
    String r3 = cf3.join();
});

// Wait for ANY to complete (first one wins)
CompletableFuture<Object> any = CompletableFuture.anyOf(cf1, cf2, cf3);
any.thenAccept(result -> log.info("First result: {}", result));
```

### Practical Example: Orchestrating Async API Calls

```java
public CompletableFuture<OrderSummary> buildOrderSummary(long orderId) {
    ExecutorService ioPool = Executors.newFixedThreadPool(10);

    CompletableFuture<Order> orderFuture =
        CompletableFuture.supplyAsync(() -> orderService.getOrder(orderId), ioPool);

    CompletableFuture<User> userFuture = orderFuture
        .thenComposeAsync(order ->
            CompletableFuture.supplyAsync(
                () -> userService.getUser(order.userId()), ioPool), ioPool);

    CompletableFuture<List<Product>> productsFuture = orderFuture
        .thenComposeAsync(order ->
            CompletableFuture.supplyAsync(
                () -> productService.getProducts(order.productIds()), ioPool), ioPool);

    CompletableFuture<ShippingInfo> shippingFuture = orderFuture
        .thenComposeAsync(order ->
            CompletableFuture.supplyAsync(
                () -> shippingService.getShipping(order.shippingId()), ioPool), ioPool);

    // Combine all results once all are available
    return orderFuture.thenCombine(userFuture, (order, user) -> new Object[]{order, user})
        .thenCombine(productsFuture, (arr, products) -> new Object[]{arr[0], arr[1], products})
        .thenCombine(shippingFuture, (arr, shipping) -> {
            Order order = (Order) arr[0];
            User user = (User) arr[1];
            @SuppressWarnings("unchecked")
            List<Product> products = (List<Product>) arr[2];
            return new OrderSummary(order, user, products, shipping);
        })
        .exceptionally(ex -> {
            log.error("Failed to build order summary for {}", orderId, ex);
            throw new CompletionException(ex);
        });
}
```

---

## 3. Virtual Threads (Project Loom)

Virtual threads (preview in Java 19, stable in Java 21) are lightweight threads managed by the JVM rather than the OS. They make the "one thread per request" model scalable.

### How They Work

```
Platform Thread (carrier)     Virtual Thread
┌─────────────────────┐      ┌──────────────────┐
│  OS Thread (1:1)    │      │  JVM-managed      │
│  ~1MB stack         │◄─────│  ~few KB stack    │
│  Expensive to create│ mount│  Cheap to create  │
│  Limited (~thousands│      │  Millions possible│
│  )                  │      │                   │
└─────────────────────┘      └──────────────────┘
```

- Virtual threads are **mounted** onto carrier (platform) threads for execution
- When a virtual thread blocks on IO, it is **unmounted** (its continuation is saved on the heap), freeing the carrier thread
- The carrier thread can then run another virtual thread
- When the IO completes, the virtual thread is **remounted** onto an available carrier
- The JVM uses **continuations** under the hood to save/restore the virtual thread's stack

```java
// Creating virtual threads
Thread vt = Thread.ofVirtual().name("my-vt").start(() -> {
    System.out.println("Running on: " + Thread.currentThread());
});

// Using the per-task executor (preferred in most cases)
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 100_000).forEach(i ->
        executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1)); // unmounts, does NOT block carrier
            return i;
        })
    );
} // executor.close() waits for all tasks
```

### When to Use Virtual Threads vs Platform Threads

| Criteria | Virtual Threads | Platform Threads |
|----------|----------------|-----------------|
| IO-bound work (HTTP calls, DB queries, file IO) | Excellent choice | Wasteful (thread blocked during IO) |
| CPU-bound computation | No benefit (still needs a carrier) | Use fixed pool sized to CPU count |
| Number of concurrent tasks | Millions | Thousands at most |
| Stack memory | Grows/shrinks dynamically (few KB) | Fixed ~1MB per thread |
| Thread pool sizing needed | No, use per-task executor | Yes, must tune pool size |
| Compatibility with `synchronized` | Beware of pinning | No issues |

### Pinning

A virtual thread becomes **pinned** to its carrier thread when it cannot be unmounted:

1. **Inside a `synchronized` block or method** -- the monitor is tied to the carrier
2. **During a native method call (JNI/FFI)**

Pinning does not cause correctness issues, but it defeats the purpose of virtual threads by tying up the carrier.

**Fix: Replace `synchronized` with `ReentrantLock`:**

```java
// BAD — pins virtual thread to carrier
synchronized (lock) {
    connection.query(sql); // blocking IO while pinned
}

// GOOD — ReentrantLock allows unmounting
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    connection.query(sql); // virtual thread can unmount during IO
} finally {
    lock.unlock();
}
```

Use `-Djdk.tracePinnedThreads=short` to detect pinning at runtime.

### Structured Concurrency (Preview in Java 21+)

Structured concurrency treats groups of concurrent tasks as a unit, ensuring clean lifecycle management:

```java
import java.util.concurrent.StructuredTaskScope;

record UserProfile(User user, List<Order> orders) {}

UserProfile fetchProfile(long userId) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Fork subtasks — each runs in its own virtual thread
        StructuredTaskScope.Subtask<User> userTask =
            scope.fork(() -> userService.getUser(userId));
        StructuredTaskScope.Subtask<List<Order>> ordersTask =
            scope.fork(() -> orderService.getOrders(userId));

        scope.join();           // wait for all subtasks
        scope.throwIfFailed();  // propagate any exception

        return new UserProfile(userTask.get(), ordersTask.get());
    }
    // If any subtask fails, the other is cancelled automatically
    // No leaked threads — scope ensures all are done before exiting
}
```

**Scope policies:**
- `ShutdownOnFailure` -- cancels siblings if any subtask throws
- `ShutdownOnSuccess` -- cancels siblings once the first subtask succeeds (useful for racing/hedging)

### ScopedValue vs ThreadLocal

`ScopedValue` (preview in Java 21+) is the virtual-thread-friendly replacement for `ThreadLocal`:

```java
// ThreadLocal — mutable, must remember to clean up, expensive with virtual threads
private static final ThreadLocal<User> CURRENT_USER = new ThreadLocal<>();

// ScopedValue — immutable within scope, automatically scoped, efficient
private static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

// Binding a scoped value
ScopedValue.runWhere(CURRENT_USER, authenticatedUser, () -> {
    handleRequest(); // CURRENT_USER.get() returns authenticatedUser
});
// Outside the scope, CURRENT_USER.get() throws NoSuchElementException
```

| Feature | `ThreadLocal` | `ScopedValue` |
|---------|--------------|---------------|
| Mutability | Mutable (`set()`/`get()`) | Immutable within scope |
| Inheritance | Via `InheritableThreadLocal` (copies per child) | Efficient sharing with child scopes |
| Cleanup | Manual (`remove()`) | Automatic when scope exits |
| Virtual thread cost | High (each VT copies the ThreadLocal) | Low (shared reference) |
| Rebinding | Anywhere | Only by entering a nested scope |

---

## 4. Fork/Join Framework

The Fork/Join framework (Java 7+) is designed for divide-and-conquer parallelism. It powers `parallelStream()` and the common pool.

### Core Components

- **`ForkJoinPool`** -- the executor that manages worker threads
- **`RecursiveTask<V>`** -- a task that returns a result
- **`RecursiveAction`** -- a task that returns void
- **Work-stealing** -- idle threads steal tasks from busy threads' deques

```
Thread 1 deque:  [Task A] [Task B] [Task C]  ←── thread 1 takes from head
Thread 2 deque:  [empty]                      ←── thread 2 steals from tail of thread 1
```

### Parallel Merge Sort Example

```java
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ForkJoinPool;

public class ParallelMergeSort extends RecursiveAction {
    private static final int THRESHOLD = 1024;
    private final int[] array;
    private final int left, right;

    public ParallelMergeSort(int[] array, int left, int right) {
        this.array = array;
        this.left = left;
        this.right = right;
    }

    @Override
    protected void compute() {
        if (right - left <= THRESHOLD) {
            // Base case: use sequential sort for small arrays
            java.util.Arrays.sort(array, left, right);
            return;
        }
        int mid = left + (right - left) / 2;

        ParallelMergeSort leftTask = new ParallelMergeSort(array, left, mid);
        ParallelMergeSort rightTask = new ParallelMergeSort(array, mid, right);

        // Fork left task to run in parallel, compute right in current thread
        leftTask.fork();
        rightTask.compute();
        leftTask.join();

        merge(array, left, mid, right);
    }

    private void merge(int[] arr, int left, int mid, int right) {
        int[] temp = new int[right - left];
        int i = left, j = mid, k = 0;
        while (i < mid && j < right) {
            temp[k++] = arr[i] <= arr[j] ? arr[i++] : arr[j++];
        }
        while (i < mid) temp[k++] = arr[i++];
        while (j < right) temp[k++] = arr[j++];
        System.arraycopy(temp, 0, arr, left, temp.length);
    }

    public static void main(String[] args) {
        int[] data = new int[1_000_000];
        // fill data...

        ForkJoinPool pool = new ForkJoinPool(); // uses availableProcessors() threads
        pool.invoke(new ParallelMergeSort(data, 0, data.length));
    }
}
```

### Parallel Sum with RecursiveTask

```java
public class ParallelSum extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] array;
    private final int start, end;

    public ParallelSum(long[] array, int start, int end) {
        this.array = array;
        this.start = start;
        this.end = end;
    }

    @Override
    protected Long compute() {
        if (end - start <= THRESHOLD) {
            long sum = 0;
            for (int i = start; i < end; i++) sum += array[i];
            return sum;
        }
        int mid = start + (end - start) / 2;
        ParallelSum left = new ParallelSum(array, start, mid);
        ParallelSum right = new ParallelSum(array, mid, end);
        left.fork();
        long rightResult = right.compute();
        long leftResult = left.join();
        return leftResult + rightResult;
    }
}
```

**Key best practices:**
- Always `fork()` one subtask and `compute()` the other (avoids extra thread context switch)
- Choose an appropriate threshold -- too small means excessive task overhead, too large means no parallelism
- Avoid blocking IO inside Fork/Join tasks -- it wastes carrier threads from the common pool
- `ForkJoinPool.commonPool()` is shared by parallel streams; creating a custom pool isolates your work

---

## 5. Synchronization Primitives

### CountDownLatch -- Wait for N Events

A one-shot barrier. The count decrements; once it reaches zero, all waiting threads proceed. Cannot be reset.

```java
import java.util.concurrent.CountDownLatch;

public class ServiceStartup {
    public static void main(String[] args) throws InterruptedException {
        int serviceCount = 3;
        CountDownLatch latch = new CountDownLatch(serviceCount);

        for (int i = 0; i < serviceCount; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    System.out.println("Service " + id + " starting...");
                    Thread.sleep((long) (Math.random() * 2000));
                    System.out.println("Service " + id + " ready");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown(); // decrement count
                }
            }).start();
        }

        latch.await(); // blocks until count reaches 0
        System.out.println("All services started — accepting requests");
    }
}
```

### CyclicBarrier -- Synchronize N Threads at a Point

All threads wait until the last one arrives. Reusable (cyclic) -- resets after each barrier trip.

```java
import java.util.concurrent.CyclicBarrier;

public class ParallelPhaseComputation {
    public static void main(String[] args) {
        int workers = 4;
        CyclicBarrier barrier = new CyclicBarrier(workers, () ->
            System.out.println("--- All workers completed phase ---")
        );

        for (int i = 0; i < workers; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    for (int phase = 1; phase <= 3; phase++) {
                        System.out.println("Worker " + id + " phase " + phase);
                        Thread.sleep((long) (Math.random() * 1000));
                        barrier.await(); // wait for all workers to finish this phase
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
}
```

### Phaser -- Flexible Barrier with Phases

Supports dynamic registration/deregistration of parties and multiple phases.

```java
import java.util.concurrent.Phaser;

public class DynamicPhaserExample {
    public static void main(String[] args) {
        Phaser phaser = new Phaser(1); // register self (main thread)

        for (int i = 0; i < 3; i++) {
            phaser.register(); // dynamically register a new party
            final int id = i;
            new Thread(() -> {
                System.out.println("Worker " + id + " phase 0 done");
                phaser.arriveAndAwaitAdvance(); // wait for phase 0

                System.out.println("Worker " + id + " phase 1 done");
                phaser.arriveAndDeregister(); // done, leave the phaser
            }).start();
        }

        phaser.arriveAndAwaitAdvance(); // main waits for phase 0
        phaser.arriveAndDeregister();   // main deregisters
    }
}
```

### Semaphore -- Limit Concurrent Access

Controls access to a shared resource by maintaining a set of permits.

```java
import java.util.concurrent.Semaphore;

public class ConnectionPool {
    private final Semaphore semaphore;

    public ConnectionPool(int maxConnections) {
        this.semaphore = new Semaphore(maxConnections, true); // fair = true
    }

    public Connection acquire() throws InterruptedException {
        semaphore.acquire(); // blocks if no permits available
        return createConnection();
    }

    public void release(Connection conn) {
        closeConnection(conn);
        semaphore.release(); // return permit
    }

    // tryAcquire with timeout for non-blocking attempt
    public Connection tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (semaphore.tryAcquire(timeout, unit)) {
            return createConnection();
        }
        throw new TimeoutException("Could not acquire connection");
    }
}
```

### Exchanger -- Swap Data Between Two Threads

A synchronization point where two threads can exchange objects.

```java
import java.util.concurrent.Exchanger;

public class ExchangerExample {
    public static void main(String[] args) {
        Exchanger<String> exchanger = new Exchanger<>();

        new Thread(() -> {
            try {
                String received = exchanger.exchange("from-thread-1");
                System.out.println("Thread 1 received: " + received);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        new Thread(() -> {
            try {
                String received = exchanger.exchange("from-thread-2");
                System.out.println("Thread 2 received: " + received);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
        // Thread 1 receives "from-thread-2", Thread 2 receives "from-thread-1"
    }
}
```

**Comparison Summary:**

| Primitive | Parties | Reusable | Use Case |
|-----------|---------|----------|----------|
| `CountDownLatch` | N countdowns, M waiters | No | Wait for N events to complete |
| `CyclicBarrier` | N (all must arrive) | Yes | Synchronize N threads per phase |
| `Phaser` | Dynamic | Yes | Dynamic party count, multiple phases |
| `Semaphore` | N permits | Yes | Rate limiting, resource pools |
| `Exchanger` | Exactly 2 | Yes | Pair of threads exchanging data |

---

## 6. java.util.concurrent Collections

### ConcurrentHashMap

The most important concurrent collection. Since Java 8, it uses **CAS + bucket-level (`synchronized`)** locking instead of the old segment-based approach.

**Internals:**
- Array of `Node` entries (like `HashMap`)
- First node of each bucket is locked with `synchronized` for insertions
- Reads are lock-free using `volatile` reads
- Tree bins (like `HashMap`) when a bucket exceeds 8 entries
- Default concurrency level is no longer meaningful (was for segments); the table resizes dynamically

**Atomic compute methods (critical for interviews):**

```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

// putIfAbsent — atomic check-and-put
map.putIfAbsent("key", 1);

// computeIfAbsent — atomic check-and-compute (lazy initialization)
map.computeIfAbsent("key", k -> expensiveComputation(k));

// compute — atomic update (replaces merge logic)
map.compute("key", (k, v) -> v == null ? 1 : v + 1);

// merge — atomic merge operation
map.merge("key", 1, Integer::sum); // increment or initialize to 1

// Bulk operations (parallelism threshold)
map.forEach(1000, (k, v) -> System.out.println(k + "=" + v));
long sum = map.reduceValuesToLong(1000, Integer::longValue, 0L, Long::sum);
```

**Important:** Never use `map.get()` + `map.put()` for compound operations -- that is not atomic even with `ConcurrentHashMap`. Always use `compute`, `merge`, `computeIfAbsent`.

### CopyOnWriteArrayList

A thread-safe `List` where all mutative operations (add, set, remove) create a new copy of the underlying array.

```java
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("a"); // creates a new internal array
// Iterators see a snapshot — no ConcurrentModificationException
for (String s : list) {
    list.add("b"); // safe, but iterator won't see "b"
}
```

**When to use:** Read-heavy, write-rare scenarios (e.g., listener lists, configuration).
**When NOT to use:** Frequent writes (each write copies the entire array).

### BlockingQueue Variants

| Queue | Bounded | Ordering | Special Feature |
|-------|---------|----------|-----------------|
| `ArrayBlockingQueue` | Yes (fixed) | FIFO | Backed by array, fair/unfair locking |
| `LinkedBlockingQueue` | Optional | FIFO | Separate locks for put/take (higher throughput) |
| `PriorityBlockingQueue` | No | Priority (natural/comparator) | Not FIFO -- highest priority first |
| `SynchronousQueue` | Zero capacity | Direct handoff | Each put must wait for a take |
| `DelayQueue` | No | Delay expiration | Elements available only after delay expires |
| `LinkedTransferQueue` | No | FIFO | `transfer()` blocks until consumer takes |

```java
// Producer-consumer with ArrayBlockingQueue
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(100);

// Producer
queue.put(task);      // blocks if full
queue.offer(task, 5, TimeUnit.SECONDS); // blocks with timeout

// Consumer
Task t = queue.take();  // blocks if empty
Task t = queue.poll(5, TimeUnit.SECONDS); // blocks with timeout
```

### Other Concurrent Collections

- **`ConcurrentLinkedQueue`** -- non-blocking (lock-free CAS) unbounded queue. Use when you need a simple concurrent queue without blocking semantics.
- **`ConcurrentSkipListMap`** -- concurrent sorted map (like a concurrent `TreeMap`). O(log n) operations, lock-free reads. Use when you need sorted concurrent access.
- **`ConcurrentSkipListSet`** -- concurrent sorted set backed by `ConcurrentSkipListMap`.

---

## 7. Java Memory Model

The Java Memory Model (JMM) defines the rules for when writes by one thread become visible to reads by another thread. Without proper synchronization, the JVM and hardware are free to reorder operations and cache values.

### Happens-Before Relationships

The happens-before (HB) relationship is the fundamental ordering guarantee. If A happens-before B, then A's effects are visible to B. The complete set of rules:

1. **Program order rule:** Each action in a thread HB every subsequent action in that thread
2. **Monitor lock rule:** An unlock on a monitor HB every subsequent lock on that monitor
3. **Volatile variable rule:** A write to a `volatile` field HB every subsequent read of that field
4. **Thread start rule:** `thread.start()` HB any action in the started thread
5. **Thread join rule:** Any action in a thread HB the return from `thread.join()` on that thread
6. **Thread interruption rule:** A call to `thread.interrupt()` HB detection of the interrupt by the interrupted thread
7. **Finalizer rule:** Completion of a constructor HB the start of the finalizer for that object
8. **Transitivity:** If A HB B and B HB C, then A HB C

### volatile -- Visibility and Ordering

```java
volatile boolean running = true;

// Thread 1
data = 42;        // (1)
running = true;   // (2) volatile write

// Thread 2
if (running) {    // (3) volatile read — HB (2), so (1) is visible
    use(data);    // (4) guaranteed to see data = 42
}
```

**What `volatile` guarantees:**
- **Visibility:** All writes before a volatile write are visible to threads that subsequently read the volatile variable
- **Ordering:** Prevents reordering of instructions across the volatile access (acts as a memory fence)

**What `volatile` does NOT guarantee:**
- **Atomicity of compound operations:** `volatile int count; count++` is NOT atomic (read-modify-write)

```java
// BROKEN — volatile does not make increment atomic
volatile int count = 0;
// Thread 1: count++ — reads 0, computes 1, writes 1
// Thread 2: count++ — reads 0, computes 1, writes 1
// Result: count = 1 (should be 2)

// FIX — use AtomicInteger
AtomicInteger count = new AtomicInteger(0);
count.incrementAndGet(); // atomic CAS-based increment
```

### Memory Barriers / Fences

Memory barriers (fences) are CPU instructions that enforce ordering. The JMM uses them implicitly:

| Barrier Type | Effect |
|-------------|--------|
| LoadLoad | No load can be reordered before a preceding load |
| StoreStore | No store can be reordered before a preceding store |
| LoadStore | No store can be reordered before a preceding load |
| StoreLoad | No load can be reordered before a preceding store (most expensive) |

- `volatile` write inserts StoreStore + StoreLoad barriers
- `volatile` read inserts LoadLoad + LoadStore barriers
- `synchronized` enter inserts LoadLoad + LoadStore
- `synchronized` exit inserts StoreStore + StoreLoad

### Data Races

A **data race** occurs when two threads access the same variable, at least one access is a write, and there is no happens-before ordering between the accesses.

**How to avoid data races:**
1. Use `synchronized` blocks/methods
2. Use `volatile` variables (for single read/write visibility)
3. Use `java.util.concurrent` atomics and locks
4. Use immutable objects
5. Confine data to a single thread

### final Field Semantics

The JMM guarantees that if an object is properly constructed (no `this` escape), all threads will see the correct value of `final` fields without synchronization:

```java
public class SafePublication {
    private final int value;
    private final List<String> items;

    public SafePublication(int value, List<String> items) {
        this.value = value;
        this.items = List.copyOf(items); // defensive copy + immutable
        // Constructor inserts a freeze barrier after final field writes
    }
    // Any thread reading these fields after construction sees correct values
}
```

**Caution:** This only applies if the constructor completes normally and `this` does not escape during construction.

---

## 8. Lock-Free Programming

### CAS (Compare-And-Swap)

CAS is a CPU-level atomic instruction: "If the current value equals the expected value, set it to the new value; otherwise do nothing." It returns whether the swap succeeded.

```java
// Pseudocode for CAS
boolean compareAndSwap(memoryLocation, expectedValue, newValue) {
    if (memoryLocation.value == expectedValue) {
        memoryLocation.value = newValue;
        return true;
    }
    return false; // another thread changed it
}
```

All `java.util.concurrent.atomic` classes are built on CAS.

### Atomic Classes

```java
import java.util.concurrent.atomic.*;

// Basic atomics
AtomicInteger counter = new AtomicInteger(0);
counter.incrementAndGet();       // atomic i++
counter.compareAndSet(5, 10);    // CAS
counter.getAndUpdate(v -> v * 2); // atomic transform
counter.updateAndGet(v -> v * 2); // atomic transform, returns new value

// AtomicReference — CAS on object references
AtomicReference<Node> head = new AtomicReference<>(null);
head.compareAndSet(expectedNode, newNode);

// AtomicReferenceFieldUpdater — avoid overhead of wrapping fields
// Useful when you have many instances and don't want per-instance AtomicReference
private volatile Node next;
private static final AtomicReferenceFieldUpdater<MyClass, Node> NEXT =
    AtomicReferenceFieldUpdater.newUpdater(MyClass.class, Node.class, "next");
NEXT.compareAndSet(this, expected, newNode);
```

### The ABA Problem and AtomicStampedReference

The ABA problem: Thread 1 reads A, Thread 2 changes A -> B -> A, Thread 1's CAS succeeds even though the value changed.

```java
// AtomicStampedReference tracks a version stamp alongside the reference
AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 0);

int[] stampHolder = new int[1];
String current = ref.get(stampHolder); // gets value and stamp
int stamp = stampHolder[0];

// CAS only succeeds if both value AND stamp match
boolean success = ref.compareAndSet(current, "B", stamp, stamp + 1);
```

### LongAdder and LongAccumulator

Under high contention, `AtomicLong` causes many CAS retries (threads keep failing and spinning). `LongAdder` distributes the count across cells, reducing contention:

```java
import java.util.concurrent.atomic.*;

// AtomicLong — single CAS point, high contention
AtomicLong atomicCounter = new AtomicLong();
atomicCounter.incrementAndGet();

// LongAdder — distributed cells, much better under contention
LongAdder adder = new LongAdder();
adder.increment();        // updates a cell (low contention per cell)
long total = adder.sum(); // aggregates all cells (eventually consistent)

// LongAccumulator — generalized version with custom accumulation function
LongAccumulator max = new LongAccumulator(Long::max, Long.MIN_VALUE);
max.accumulate(42);
long result = max.get();
```

**When to use `LongAdder` over `AtomicLong`:**
- High write contention from many threads
- You only need the sum periodically (not after every increment)
- Statistics counters, metrics, event counting

### VarHandle (Java 9+)

`VarHandle` provides low-level access to variables with fine-grained memory ordering control:

```java
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public class VarHandleExample {
    private int value;
    private static final VarHandle VALUE;

    static {
        try {
            VALUE = MethodHandles.lookup()
                .findVarHandle(VarHandleExample.class, "value", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public void update() {
        // Different memory ordering modes
        VALUE.set(this, 42);                     // plain write (no ordering)
        VALUE.setVolatile(this, 42);             // volatile write
        VALUE.setRelease(this, 42);              // release write (weaker than volatile)
        int v = (int) VALUE.getAcquire(this);    // acquire read (weaker than volatile)
        VALUE.setOpaque(this, 42);               // opaque write (no reordering, no visibility guarantee)

        // CAS operations
        VALUE.compareAndSet(this, 0, 1);
        VALUE.compareAndExchange(this, 0, 1);    // returns witness value
        VALUE.getAndAdd(this, 5);                // atomic add
    }
}
```

**Memory ordering modes (weakest to strongest):**
- `Opaque` -- no reordering of accesses to this variable, but no cross-variable ordering
- `Acquire/Release` -- ordered with respect to other acquire/release on the same variable
- `Volatile` -- full StoreLoad barrier, ordered with respect to all volatile operations

---

## 9. Deadlock, Livelock, Starvation

### Deadlock

A deadlock occurs when two or more threads are permanently blocked, each waiting for a lock held by the other. Four conditions must ALL be present (Coffman conditions):

1. **Mutual exclusion** -- at least one resource is non-shareable
2. **Hold and wait** -- a thread holds a resource while waiting for another
3. **No preemption** -- resources cannot be forcibly taken from threads
4. **Circular wait** -- a cycle exists in the resource dependency graph

```java
// Classic deadlock
Object lockA = new Object();
Object lockB = new Object();

// Thread 1
synchronized (lockA) {     // holds lockA
    Thread.sleep(100);
    synchronized (lockB) { // waits for lockB (held by Thread 2)
        // ...
    }
}

// Thread 2
synchronized (lockB) {     // holds lockB
    Thread.sleep(100);
    synchronized (lockA) { // waits for lockA (held by Thread 1) — DEADLOCK
        // ...
    }
}
```

### Detection with jstack and Thread Dumps

```bash
# Get thread dump (shows deadlocks)
jstack <pid>

# Or send SIGQUIT
kill -3 <pid>

# Programmatic detection
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] deadlocked = bean.findDeadlockedThreads();
if (deadlocked != null) {
    ThreadInfo[] infos = bean.getThreadInfo(deadlocked, true, true);
    for (ThreadInfo info : infos) {
        System.out.println(info);
    }
}
```

### Prevention Strategies

**1. Lock ordering -- always acquire locks in a consistent global order:**

```java
// Prevent deadlock by ordering locks by System.identityHashCode
public void transferMoney(Account from, Account to, int amount) {
    int fromHash = System.identityHashCode(from);
    int toHash = System.identityHashCode(to);

    Object first = fromHash < toHash ? from : to;
    Object second = fromHash < toHash ? to : from;

    synchronized (first) {
        synchronized (second) {
            from.debit(amount);
            to.credit(amount);
        }
    }
}
```

**2. tryLock with timeout -- avoid indefinite blocking:**

```java
ReentrantLock lock1 = new ReentrantLock();
ReentrantLock lock2 = new ReentrantLock();

boolean acquired = false;
while (!acquired) {
    if (lock1.tryLock(100, TimeUnit.MILLISECONDS)) {
        try {
            if (lock2.tryLock(100, TimeUnit.MILLISECONDS)) {
                try {
                    // critical section
                    acquired = true;
                } finally {
                    lock2.unlock();
                }
            }
        } finally {
            if (!acquired) lock1.unlock(); // release lock1 if lock2 failed
        }
    }
    if (!acquired) Thread.sleep(50); // backoff before retry
}
```

### Livelock

Threads are not blocked but make no progress because they keep responding to each other's actions:

```java
// Livelock example: two "polite" threads keep yielding to each other
AtomicBoolean spouse1Active = new AtomicBoolean(true);
AtomicBoolean spouse2Active = new AtomicBoolean(true);

// Spouse 1: "After you!"
while (spouse1Active.get()) {
    if (spouse2Active.get()) {
        spouse1Active.set(false); // step aside
        Thread.sleep(10);
        spouse1Active.set(true);  // try again
    }
}

// Spouse 2: "No, after you!"
while (spouse2Active.get()) {
    if (spouse1Active.get()) {
        spouse2Active.set(false); // step aside
        Thread.sleep(10);
        spouse2Active.set(true);  // try again
    }
}
// Both keep stepping aside forever — no progress
```

**Fix:** Introduce randomized backoff or a tie-breaking mechanism.

### Starvation

A thread is perpetually denied access to a resource because other threads always get priority.

**Causes:**
- Unfair lock acquisition (default `ReentrantLock` and `synchronized` are unfair)
- Thread priority abuse
- Long-running tasks in a shared pool

**Fixes:**
- Use fair locks: `new ReentrantLock(true)`, `new Semaphore(permits, true)`
- Avoid setting thread priorities
- Use bounded queues with fair ordering

---

## 10. Common Concurrency Patterns

### Producer-Consumer with BlockingQueue

```java
public class ProducerConsumer {
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
    private static final String POISON_PILL = "DONE";

    class Producer implements Runnable {
        @Override
        public void run() {
            try {
                for (int i = 0; i < 100; i++) {
                    queue.put("item-" + i); // blocks if queue is full
                }
                queue.put(POISON_PILL); // signal completion
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    class Consumer implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    String item = queue.take(); // blocks if queue is empty
                    if (POISON_PILL.equals(item)) break;
                    process(item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void process(String item) {
            System.out.println("Processing: " + item);
        }
    }
}
```

### Read-Write Locks

**ReentrantReadWriteLock:**

```java
import java.util.concurrent.locks.*;

public class Cache<K, V> {
    private final Map<K, V> map = new HashMap<>();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    public V get(K key) {
        readLock.lock();       // multiple readers can hold this simultaneously
        try {
            return map.get(key);
        } finally {
            readLock.unlock();
        }
    }

    public void put(K key, V value) {
        writeLock.lock();      // exclusive — no readers or writers
        try {
            map.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }
}
```

**StampedLock (Java 8+) -- optimistic reads:**

```java
import java.util.concurrent.locks.StampedLock;

public class Point {
    private double x, y;
    private final StampedLock sl = new StampedLock();

    public void move(double deltaX, double deltaY) {
        long stamp = sl.writeLock();
        try {
            x += deltaX;
            y += deltaY;
        } finally {
            sl.unlockWrite(stamp);
        }
    }

    public double distanceFromOrigin() {
        // Optimistic read — no lock acquired, very cheap
        long stamp = sl.tryOptimisticRead();
        double currentX = x, currentY = y;

        // Validate that no write occurred during the optimistic read
        if (!sl.validate(stamp)) {
            // Fallback to pessimistic read lock
            stamp = sl.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                sl.unlockRead(stamp);
            }
        }
        return Math.sqrt(currentX * currentX + currentY * currentY);
    }
}
```

**Comparison:**

| Feature | `ReentrantReadWriteLock` | `StampedLock` |
|---------|------------------------|---------------|
| Optimistic reading | No | Yes |
| Reentrant | Yes | No |
| Condition support | Yes (`newCondition()`) | No |
| Read/write upgrade | No | Yes (`tryConvertToWriteLock`) |
| Fairness policy | Optional | No |
| Best for | Mixed read/write with reentrancy needs | Read-heavy with rare writes |

### Double-Checked Locking

```java
public class Singleton {
    // MUST be volatile — prevents instruction reordering during object construction
    private static volatile Singleton instance;

    private Singleton() {}

    public static Singleton getInstance() {
        if (instance == null) {                // first check (no lock)
            synchronized (Singleton.class) {
                if (instance == null) {         // second check (with lock)
                    instance = new Singleton(); // safe because of volatile
                }
            }
        }
        return instance;
    }
}
```

**Why `volatile` is required:**
Without `volatile`, the JVM can reorder the constructor's operations. Another thread might see a non-null reference to a partially constructed object:
1. Allocate memory
2. Assign reference to `instance` (non-null now!)
3. Initialize fields (constructor body)

With `volatile`, step 2 cannot happen before step 3 (StoreStore barrier).

### Thread-Safe Singleton Patterns

```java
// 1. Enum singleton (recommended by Effective Java)
public enum DatabaseConnection {
    INSTANCE;
    // guaranteed thread-safe, serialization-safe
    public void query(String sql) { /* ... */ }
}

// 2. Initialization-on-demand holder (lazy, thread-safe, no synchronization needed)
public class Singleton {
    private Singleton() {}

    private static class Holder {
        // Class initialization is guaranteed thread-safe by the JLS
        static final Singleton INSTANCE = new Singleton();
    }

    public static Singleton getInstance() {
        return Holder.INSTANCE; // class loaded only when first accessed
    }
}

// 3. Double-checked locking (shown above)

// 4. Simple eager initialization (if lazy is not needed)
public class Singleton {
    private static final Singleton INSTANCE = new Singleton();
    public static Singleton getInstance() { return INSTANCE; }
}
```

### Object Pooling

```java
public class ObjectPool<T> {
    private final BlockingQueue<T> pool;
    private final Supplier<T> factory;

    public ObjectPool(int size, Supplier<T> factory) {
        this.factory = factory;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.offer(factory.get());
        }
    }

    public T borrow() throws InterruptedException {
        return pool.take(); // blocks if pool is empty
    }

    public void returnObject(T obj) {
        pool.offer(obj); // returns to pool (silently drops if pool is full)
    }

    // With timeout
    public T borrow(long timeout, TimeUnit unit) throws InterruptedException {
        T obj = pool.poll(timeout, unit);
        if (obj == null) throw new RuntimeException("Pool exhausted");
        return obj;
    }
}
```

---

## 11. Common Senior Interview Questions

**Q1: What is the difference between `synchronized` and `ReentrantLock`? When would you choose one over the other?**

`synchronized` is a language keyword that automatically acquires and releases a monitor lock. `ReentrantLock` is an explicit lock from `java.util.concurrent.locks`.

Key differences:
- **Interruptible locking:** `ReentrantLock.lockInterruptibly()` allows a thread to be interrupted while waiting; `synchronized` blocks indefinitely.
- **Try-lock with timeout:** `tryLock(timeout, unit)` enables non-blocking lock attempts; `synchronized` has no equivalent.
- **Fairness:** `ReentrantLock` supports fair ordering (`new ReentrantLock(true)`); `synchronized` is always unfair (barging allowed).
- **Multiple conditions:** `ReentrantLock` supports multiple `Condition` objects (like multiple wait sets); `synchronized` has only one wait set per monitor.
- **Virtual thread pinning:** `synchronized` pins virtual threads to carrier threads; `ReentrantLock` does not.

Choose `synchronized` for simple, short critical sections where you do not need advanced features. Choose `ReentrantLock` when you need timeouts, interruptibility, fairness, multiple conditions, or virtual thread compatibility.

---

**Q2: Explain the Java Memory Model. What is a happens-before relationship?**

The Java Memory Model (JMM) defines the rules for how threads communicate through memory. Without synchronization, threads may see stale values because the JVM and CPU cache values in registers, reorder instructions for performance, and do not guarantee when writes become visible to other threads.

The happens-before (HB) relationship is the core concept: if action A happens-before action B, then all memory effects of A are guaranteed visible to B. HB is established by specific synchronization actions: `volatile` read/write, `synchronized` entry/exit, `Thread.start()`/`join()`, and `Lock.lock()`/`unlock()`. Crucially, HB is transitive: if A HB B and B HB C, then A HB C.

Without a HB relationship, two threads accessing the same variable (with at least one writing) constitute a **data race**, and the program has undefined behavior regarding that variable's visibility.

---

**Q3: What are virtual threads? How do they differ from platform threads? What are their limitations?**

Virtual threads (Java 21) are lightweight threads managed by the JVM rather than the OS. A platform thread is a thin wrapper over an OS thread with a fixed ~1MB stack. Virtual threads have a dynamically growing stack (starting at ~few KB) stored on the heap, and the JVM multiplexes many virtual threads onto a small number of platform (carrier) threads.

When a virtual thread blocks on IO, it is unmounted from its carrier thread (its continuation is saved to the heap), freeing the carrier to run other virtual threads. This makes the "one thread per request" model scalable to millions of concurrent tasks for IO-bound workloads.

Limitations: (1) **Pinning** -- `synchronized` blocks and native calls pin the virtual thread to the carrier, preventing unmounting. Replace `synchronized` with `ReentrantLock`. (2) **No benefit for CPU-bound work** -- virtual threads still need carrier threads for execution, so CPU-bound tasks should use a fixed-size platform thread pool. (3) **ThreadLocal overhead** -- each virtual thread copies inherited ThreadLocals, which is wasteful at scale. Use `ScopedValue` instead. (4) **Pooling is counterproductive** -- do not pool virtual threads; create a new one per task.

---

**Q4: How does `ConcurrentHashMap` achieve thread safety? Why is `get()` + `put()` not atomic?**

Since Java 8, `ConcurrentHashMap` uses a combination of `volatile` reads and bucket-level `synchronized` locking. Reads are fully lock-free: the table array and node values are `volatile`, so get() always sees the most recent write. Writes synchronize on the first node of the target bucket, so only threads writing to the same bucket contend with each other.

`get()` + `put()` is not atomic because they are two separate operations with no happens-before relationship between them. Between the `get()` and `put()`, another thread can modify the map. This is the classic check-then-act race condition. Instead, use the atomic compound operations: `computeIfAbsent()`, `computeIfPresent()`, `compute()`, or `merge()`. These lock the bucket and perform the read + write as a single atomic operation.

---

**Q5: What is the ABA problem in lock-free programming? How do you solve it?**

The ABA problem occurs in CAS-based algorithms. Thread 1 reads value A, then another thread changes it from A to B and back to A. When Thread 1 performs CAS(expected=A, new=C), it succeeds because the current value is A, even though the value changed in between. This can cause correctness issues in algorithms like lock-free stacks (a popped node might be reused, leading to a corrupted structure).

Solutions: (1) `AtomicStampedReference` -- pairs the reference with a version stamp; CAS checks both the value and the stamp. (2) `AtomicMarkableReference` -- simpler version with a boolean mark instead of a stamp. (3) Hazard pointers or epoch-based reclamation to delay memory reuse.

---

**Q6: Compare `CountDownLatch`, `CyclicBarrier`, and `Phaser`. When would you use each?**

- **`CountDownLatch`**: A one-shot barrier. N threads count down, M threads wait. Once the count reaches zero, all waiters proceed, and the latch cannot be reset. Use it when you need to wait for N events to complete before proceeding (e.g., waiting for all services to start before accepting requests).

- **`CyclicBarrier`**: All N threads must arrive at the barrier before any can proceed. It is reusable (cyclic) and supports a barrier action that runs when all parties arrive. Use it for iterative algorithms where threads must synchronize at the end of each phase (e.g., parallel simulation steps).

- **`Phaser`**: The most flexible. Supports dynamic registration/deregistration of parties, multiple phases, and a customizable advance condition (`onAdvance`). Use it when the number of participating threads changes during execution or when you need more control than `CyclicBarrier` offers.

---

**Q7: What is a deadlock? What are the four conditions required? How do you prevent them?**

A deadlock is a permanent blocking condition where two or more threads each hold a resource the other needs, forming a circular wait. The four Coffman conditions (all required):
1. **Mutual exclusion** -- at least one resource cannot be shared
2. **Hold and wait** -- threads hold resources while waiting for additional ones
3. **No preemption** -- resources cannot be forcibly taken
4. **Circular wait** -- a cycle exists in the wait-for graph

Prevention strategies target eliminating at least one condition:
- **Break circular wait:** Impose a global ordering on locks and always acquire them in that order.
- **Break hold and wait:** Acquire all locks atomically (or release all before acquiring new ones).
- **Break no preemption:** Use `tryLock()` with timeouts; if you cannot get all locks, release what you have and retry.
- **Detection:** Use thread dumps (`jstack`) or `ThreadMXBean.findDeadlockedThreads()` to detect deadlocks at runtime and take corrective action.

---

**Q8: Explain the Fork/Join framework. When would you use it over a regular thread pool?**

The Fork/Join framework is designed for divide-and-conquer parallelism. A `ForkJoinPool` manages worker threads that use work-stealing: idle threads steal tasks from the tail of busy threads' double-ended queues. You submit `RecursiveTask<V>` (returns a value) or `RecursiveAction` (void) that split work into subtasks.

Use Fork/Join when: (1) The problem is naturally recursive and can be decomposed. (2) Subtasks are independent and CPU-bound. (3) The workload is uneven (work-stealing balances it). Examples: parallel merge sort, parallel sum, matrix multiplication, tree processing.

Use a regular `ExecutorService` when: (1) Tasks are independent and not recursively decomposable. (2) Tasks involve IO (Fork/Join is designed for CPU-bound work). (3) You need precise control over queueing, rejection, and thread lifecycle.

The common `ForkJoinPool` also powers `parallelStream()`, so be aware that blocking IO in parallel streams starves the common pool.

---

**Q9: What is the difference between `volatile` and `AtomicInteger`? When should you use each?**

`volatile` guarantees visibility and ordering: a write to a volatile variable is immediately visible to all threads, and it prevents instruction reordering around the access. However, `volatile` does NOT provide atomicity for compound operations like `count++` (which is read, increment, write -- three operations).

`AtomicInteger` provides both visibility and atomic compound operations through CAS (compare-and-swap): `incrementAndGet()`, `compareAndSet()`, `getAndUpdate()`, etc. These are atomic at the hardware level (single CPU instruction).

Use `volatile` when: you have a simple flag or status variable that is written by one thread and read by others (e.g., `volatile boolean running`). Use `AtomicInteger` when: multiple threads need to perform read-modify-write operations on a shared counter or state (e.g., concurrent counter, sequence generator).

---

**Q10: How does `StampedLock` improve over `ReentrantReadWriteLock`? What are the trade-offs?**

`StampedLock` (Java 8) introduces **optimistic reading**: `tryOptimisticRead()` returns a stamp without acquiring any lock. The thread reads the data, then calls `validate(stamp)` to check if a write occurred during the read. If validation succeeds, no lock was needed at all. If it fails, the thread falls back to a pessimistic read lock.

This provides much higher throughput in read-heavy scenarios because optimistic reads are essentially free (no CAS, no memory barriers beyond volatile reads). `ReentrantReadWriteLock` always acquires a shared read lock, which involves CAS operations and can cause contention.

Trade-offs: (1) `StampedLock` is NOT reentrant -- attempting to re-acquire a lock you already hold causes deadlock. (2) It does not support `Condition` objects. (3) Optimistic reads require careful coding (read values into locals, then validate). (4) It does not support fairness policies. Use `StampedLock` for performance-critical read-heavy paths; use `ReentrantReadWriteLock` when you need reentrancy or conditions.
