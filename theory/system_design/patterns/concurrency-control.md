# Concurrency Control

## What It Is
Concurrency control is the set of techniques a data store uses to keep concurrent transactions from corrupting each other's data. The three dominant families are **pessimistic locking**, **optimistic concurrency control (OCC)**, and **multi-version concurrency control (MVCC)**. Picking the wrong one turns a correct-looking design into a production incident.

## Why It Matters
Any time two clients race to mutate the same row — seat bookings, inventory decrements, balance updates, collaborative edits — you need a concurrency strategy. The choice drives throughput, latency, and the kinds of failures you have to design around (deadlocks vs retry storms vs write skew).

## Pessimistic Locking

### How It Works
Before reading or writing, the transaction acquires a lock. Other transactions block until the lock is released (usually at commit).

```sql
-- PostgreSQL
BEGIN;
SELECT * FROM inventory WHERE sku = 'ABC-123' FOR UPDATE;  -- row lock
UPDATE inventory SET qty = qty - 1 WHERE sku = 'ABC-123';
COMMIT;
```

### Lock Types
- **Shared (S)** — multiple readers, no writers. Held for `FOR SHARE`.
- **Exclusive (X)** — one writer, no readers. Held for `FOR UPDATE`.
- **Intent locks** — used at the table level to signal row-level intent (SQL Server, MySQL).

### Strengths
- Simple mental model: "I own this row until commit."
- Great when contention is **high** — avoids wasteful retry loops.
- Standard across all mainstream RDBMS.

### Weaknesses
- **Deadlocks** — two transactions each hold a lock the other wants. DBs auto-detect and abort one.
- **Head-of-line blocking** — a slow transaction holding a hot row stalls everyone.
- **Doesn't scale across shards or services** — a row lock in Postgres does not protect a row in another database.

### When To Use
- Hot rows with high contention (seat inventory during a concert drop, the last unit in stock).
- Short transactions.
- When you cannot afford a "sorry, try again" on the write path.

## Optimistic Concurrency Control (OCC)

### How It Works
Don't lock. Read, compute, then at commit time check that nothing you read has changed. If it has, abort and retry.

Canonical implementation: **version column** (or `updated_at`, ETag, hash):

```sql
-- Read
SELECT id, qty, version FROM inventory WHERE sku = 'ABC-123';
-- → qty=5, version=17

-- Write
UPDATE inventory
SET qty = 4, version = 18
WHERE sku = 'ABC-123' AND version = 17;
-- 0 rows updated → someone else changed it; retry the whole transaction
```

HTTP equivalent: `If-Match: "<etag>"` with `412 Precondition Failed` on conflict.

### Strengths
- No locks → no deadlocks, no blocking.
- Scales horizontally — works cleanly across services and caches.
- Perfect fit for stateless HTTP APIs.

### Weaknesses
- Work is wasted on conflict (re-read, re-compute, re-send).
- Under high contention, retries stampede and throughput collapses.
- Requires disciplined callers — a client that ignores the version check silently overwrites.

### When To Use
- Low-contention updates (user profile, settings, most CRUD).
- Multi-step wizards / form submissions.
- Distributed systems where acquiring a DB lock is not an option (microservices writing to the same logical entity through different services).

## MVCC (Multi-Version Concurrency Control)

### How It Works
The database keeps multiple versions of each row, tagged with the transaction that wrote them. Readers see a **consistent snapshot** as of a timestamp; writers create new versions without blocking readers.

Used by: PostgreSQL, Oracle, MySQL InnoDB, CockroachDB, Spanner, most modern OLTP engines.

### Snapshot Isolation
Each transaction sees the database as of its start time. Readers never block writers; writers never block readers. Two concurrent writers to the same row still conflict — one commits, one aborts or waits (depends on engine and isolation level).

### Write Skew (the gotcha)
Snapshot isolation is **not serializable**. Classic example: two doctors on call must always have at least one on duty.

```
T1 reads: Alice=on, Bob=on → T1 sets Alice=off
T2 reads: Alice=on, Bob=on → T2 sets Bob=off
Both commit. Now nobody is on call.
```

Both transactions saw a valid state and wrote to different rows, but the combined outcome violates the invariant. Fix: use `SERIALIZABLE` isolation (Postgres's SSI), an explicit `FOR UPDATE` lock on the read, or a predicate lock / materialized constraint.

### Strengths
- Readers never block. Huge win for read-heavy OLTP.
- Good default for most applications.

### Weaknesses
- Background **vacuum / compaction** to reclaim dead versions (Postgres bloat, the infamous "vacuum wraparound").
- Write skew on snapshot isolation surprises people who think snapshot = serializable.
- Version storage cost.

### When To Use
- It's usually already chosen for you — pick a modern OLTP DB and you get MVCC. The interview question is typically "what isolation level?" not "MVCC or not?".

## Isolation Levels Cheat Sheet

| Level              | Dirty read | Non-repeatable read | Phantom read | Write skew | Typical engine default |
|--------------------|------------|---------------------|--------------|------------|------------------------|
| Read Uncommitted   | yes        | yes                 | yes          | yes        | —                      |
| Read Committed     | no         | yes                 | yes          | yes        | Postgres default       |
| Repeatable Read    | no         | no                  | yes (SQL std) / no (Postgres/InnoDB via MVCC) | yes | MySQL InnoDB default |
| Snapshot Isolation | no         | no                  | no           | **yes**    | Oracle, SQL Server RCSI |
| Serializable       | no         | no                  | no           | no         | CockroachDB default    |

## Concrete Design Examples

### Ticket Booking (high contention, zero tolerance for overselling)
- Reservation intent: **pessimistic `SELECT ... FOR UPDATE`** on the seat row, or a short-lived Redis lock per seat, scoped to the checkout TTL (~10 min).
- Inventory decrement: **conditional update** (`UPDATE ... WHERE qty > 0`) is also acceptable and often simpler.
- Do not use pure OCC here — the retry storm on a popular show will melt your DB.
- See [distributed-locking.md](distributed-locking.md) for the locking options.

### E-commerce Inventory (medium contention, must not oversell)
- **Conditional decrement**: `UPDATE stock SET qty = qty - 1 WHERE sku = ? AND qty >= 1`. Atomic, lock-free, trivially idempotent with an order ID.
- For aggregated warehouse inventory across regions, use a reservation log + eventual reconciliation; see [outbox-pattern.md](outbox-pattern.md) and global inventory design.

### User Profile Update (low contention)
- **OCC with ETag / version**. `If-Match` header, `412` on conflict, client merges and retries. Clean, stateless, cache-friendly.

### Collaborative Editing (Google Docs)
- Neither OCC nor pessimistic locking scales to per-keystroke edits.
- Use **OT (Operational Transformation)** or **CRDTs** — operations are designed to commute or be transformed against concurrent operations.
- The "concurrency control" here is baked into the data structure itself.

### Bank Transfer (strong correctness, low-to-medium contention)
- `SERIALIZABLE` isolation **or** explicit `SELECT ... FOR UPDATE` on both accounts in a canonical order (prevents deadlock).
- Idempotency key on the API so retries don't double-debit; see [idempotency.md](idempotency.md).

## Decision Table

| Situation                                | Pick              |
|------------------------------------------|-------------------|
| Hot row, high contention, must not lose writes | Pessimistic lock or conditional update |
| Cross-service update to shared logical entity  | OCC (version / ETag)                  |
| Read-heavy OLTP, mostly independent writes     | MVCC default isolation                |
| Invariant across multiple rows                 | Serializable or explicit lock on read  |
| Per-keystroke collaboration                    | CRDT / OT (not traditional CC)        |
| Counter / gauge                                 | Atomic conditional update, or a CRDT counter |

## Common Pitfalls and Red Flags
- "We'll use OCC everywhere" — fine until the hot-row workload shows up and retries melt the DB.
- "Snapshot isolation is the same as serializable" — write skew says hi.
- `SELECT` + application-side check + `UPDATE` without a version or `FOR UPDATE` — classic lost update.
- Long-running transactions holding row locks (especially with an external API call inside the transaction).
- Using a distributed lock as the sole guarantee without a fencing token.

## Possible Interview Questions
1. How would you prevent double-booking in a ticket reservation system?
2. Explain OCC vs pessimistic locking. When would you pick each?
3. What is write skew? Show me a scenario where snapshot isolation produces it.
4. How does MVCC let readers and writers avoid blocking each other?
5. How do you make a "decrement inventory by 1" operation safe under concurrency?
6. Your team wants to use `SELECT ... FOR UPDATE` on a popular row. What concerns do you raise?
7. Why don't collaborative editors use traditional locking?
8. What's the right isolation level for a bank transfer?

## Related
- [idempotency.md](idempotency.md) — makes retries safe after conflict.
- [distributed-locking.md](distributed-locking.md) — when row locks aren't enough.
- [outbox-pattern.md](outbox-pattern.md) — reliably publish after a local write.
- [cap-theorem.md](cap-theorem.md) — consistency models and isolation.
