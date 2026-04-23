# Synchronization and the Java Memory Model

> This is the file for `synchronized` vs `ReentrantLock`, wait/notify alternatives, happens-before, and publication.

## 1. `synchronized` vs `ReentrantLock`

| Need | `synchronized` | `ReentrantLock` |
|------|----------------|-----------------|
| Simple mutual exclusion | Best default | Works but noisier |
| Auto-release on scope exit | Yes | No, use `try/finally` |
| Timed / interruptible acquisition | No | Yes |
| Multiple condition queues | No | Yes |
| Fairness policy | No | Optional |
| Virtual-thread compatibility on JDK 24+ | Fine | Fine |

Senior answer in 2026: choose `synchronized` unless you specifically need lock features that only `ReentrantLock` provides.

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notEmpty = lock.newCondition();
private final Condition notFull = lock.newCondition();
```

## 2. Core Coordination Primitives

| Primitive | Best use |
|----------|----------|
| `CountDownLatch` | One-shot "wait for N events" |
| `CyclicBarrier` | Fixed group meets at repeated phases |
| `Phaser` | Dynamic parties across phases |
| `Semaphore` | Cap concurrent access to scarce resource |
| `Exchanger` | Swap one object between exactly two threads |

Interview shorthand:

- Latch counts down.
- Barrier waits for all parties.
- Phaser is a flexible barrier.
- Semaphore is a permit bucket, not a lock.

## 3. `wait` / `notify` Rules

If you must use monitor wait sets:

- always hold the monitor
- always wait in a `while`, not an `if`
- prefer `notifyAll()` when multiple predicates share one monitor

```java
synchronized (queue) {
    while (queue.isEmpty()) {
        queue.wait();
    }
    String item = queue.removeFirst();
}
```

In real production code, prefer higher-level constructs such as `BlockingQueue`, latches, semaphores, or lock conditions.

## 4. Happens-Before Rules

The Java Memory Model is the visibility contract between threads.

Key happens-before edges:

1. Program order within one thread
2. Monitor unlock -> later lock on the same monitor
3. Volatile write -> later volatile read of the same variable
4. `Thread.start()` -> actions in the started thread
5. Actions in a thread -> successful return from `join()` on that thread
6. Interrupt call -> code that observes the interrupt
7. Properly constructed `final` fields -> readers after publication
8. Transitivity across the above

If there is no happens-before edge and two threads touch the same variable with at least one write, you have a data race.

## 5. `volatile`

`volatile` gives visibility and ordering, not atomic compound updates.

```java
volatile boolean running = true;

// valid use: a stop flag
while (running) {
    doWork();
}
```

Broken use:

```java
volatile int count = 0;
count++; // still racy
```

Use atomics or locks for read-modify-write.

## 6. Safe Publication

Correct publication options:

- initialize immutable `final` fields in the constructor and do not let `this` escape
- publish via a `volatile` reference
- publish through a synchronized action
- publish through concurrency utilities with defined memory effects

```java
final class ConfigSnapshot {
    private final int timeoutMs;
    private final List<String> hosts;

    ConfigSnapshot(int timeoutMs, List<String> hosts) {
        this.timeoutMs = timeoutMs;
        this.hosts = List.copyOf(hosts);
    }
}
```

## 7. Double-Checked Locking

Correct only with `volatile`:

```java
public final class Singleton {
    private static volatile Singleton instance;

    private Singleton() {}

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

If you want the safest interview answer, use an enum singleton or initialization-on-demand holder.

## 8. Memory Ordering Vocabulary

You should recognize, not over-index on, these terms:

- acquire
- release
- full fence
- CAS
- plain / opaque / acquire / release / volatile access modes

For custom lock-free code, `VarHandle` is the modern low-level tool:

```java
private volatile int value;
private static final VarHandle VALUE;

static {
    try {
        VALUE = MethodHandles.lookup()
            .findVarHandle(MyClass.class, "value", int.class);
    } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
    }
}
```

## 9. Interview Traps

- `volatile` is not a substitute for atomic increment.
- Swallowing `InterruptedException` is almost always wrong; restore the interrupt or rethrow.
- `notify()` can wake the wrong waiter if multiple predicates share a monitor.
- Replacing every monitor with `ReentrantLock` on JDK 25 is cargo cult, not senior judgment.
