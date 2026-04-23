# Threads, Executors, and Pools

> Start here if the interviewer is likely to ask about thread states, executor choices, rejection, or backpressure.

## 1. Thread States

`Thread.State` defines exactly six states:

| State | Meaning | Typical entry points |
|------|---------|----------------------|
| `NEW` | Thread created but not started | `new Thread(...)` |
| `RUNNABLE` | Executing in the JVM or ready to run | `start()`, lock acquired, unparked |
| `BLOCKED` | Waiting to acquire a monitor lock | Entering `synchronized` while another thread holds the monitor |
| `WAITING` | Waiting indefinitely for another action | `Object.wait()`, `Thread.join()`, `LockSupport.park()` |
| `TIMED_WAITING` | Waiting for up to a deadline | `sleep`, timed `wait`, timed `join`, timed park |
| `TERMINATED` | Execution finished | `run()` returned or failed with an uncaught exception |

Interview line: `RUNNABLE` in Java does **not** mean "currently on CPU"; it means runnable/executing within the JVM.

## 2. Executor Choices

Prefer a managed executor to ad-hoc platform threads. The main exception is one-off virtual-thread usage with `Thread.ofVirtual().start(...)`, which is idiomatic on JDK 21+.

```java
import java.util.concurrent.*;

ExecutorService fixed = Executors.newFixedThreadPool(8);
ExecutorService single = Executors.newSingleThreadExecutor();
ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(4);
ExecutorService workStealing = Executors.newWorkStealingPool();

try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> f = vt.submit(() -> fetchOrder());
    System.out.println(f.get());
}
```

### Which one when?

| Tool | Best for | Watch out for |
|------|----------|---------------|
| Fixed pool | CPU-bound work, capped concurrency | `Executors.newFixedThreadPool` uses an unbounded queue |
| Single-thread executor | Serialization of tasks | Backlog can grow forever |
| Scheduled pool | Delayed / periodic work | Long-running tasks distort schedule |
| Work-stealing pool | Recursive uneven CPU work | Not for blocking I/O |
| Virtual-thread-per-task executor | Blocking I/O at high concurrency | Do not use pool size as a rate limiter |

## 3. ThreadPoolExecutor Mechanics

For production platform-thread pools, prefer explicit `ThreadPoolExecutor` construction:

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4,
    8,
    60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(100),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

Submission flow:

1. If active workers `< corePoolSize`, create a worker.
2. Otherwise, enqueue.
3. If the queue is full and worker count `< maximumPoolSize`, create another worker.
4. Otherwise reject.

### Queue and rejection choices

| Choice | Why it matters |
|--------|----------------|
| `ArrayBlockingQueue` | Bounded queue; explicit backpressure |
| `LinkedBlockingQueue` | Unbounded by default; easy OOM trap |
| `SynchronousQueue` | Direct handoff; common in cached pools |
| `CallerRunsPolicy` | Natural throttling under overload |
| `AbortPolicy` | Fast failure; useful when dropping work is unacceptable |

Senior answer: unbounded queues hide overload until memory becomes the failure mode.

## 4. Pool Sizing

For platform threads:

- CPU-bound: start around `availableProcessors()`.
- Blocking I/O: Brian Goetz's rule of thumb is `N * (1 + W/C)`.
- Mixed workloads: split pools by workload type.

For virtual threads:

- Do **not** tune concurrency by pool size.
- Use one virtual thread per blocking task.
- Limit scarce downstream resources with semaphores, bounded queues, connection-pool limits, or rate limiting.

```java
Semaphore dbPermits = new Semaphore(50);

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        dbPermits.acquire();
        try {
            return queryDatabase();
        } finally {
            dbPermits.release();
        }
    });
}
```

## 5. Fork/Join

`ForkJoinPool` is for divide-and-conquer CPU work. It uses work stealing.

```java
class ParallelSum extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] array;
    private final int start;
    private final int end;

    ParallelSum(long[] array, int start, int end) {
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

Best practices:

- `fork()` one branch and `compute()` the other.
- Keep tasks CPU-bound.
- Avoid blocking I/O in the common pool.
- Remember `CompletableFuture.supplyAsync(...)` uses the common pool unless you pass an executor.

## 6. Must-Know Traps

- `Executors.newFixedThreadPool(n)` is not production-safe by default for bursty traffic because the queue is unbounded.
- `newCachedThreadPool()` can create an unbounded number of platform threads.
- Work-stealing is about throughput on decomposable CPU tasks, not general-purpose service I/O.
- Virtual threads are cheap enough to create per task; pooling them usually defeats the point.
