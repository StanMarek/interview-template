# Distributed Locking

## What It Is
A distributed lock ensures that only one process across multiple servers can access a shared resource or execute a critical section at any given time. It's the distributed equivalent of a mutex.

## Why It Matters
Without distributed locking, concurrent writes to a shared resource from multiple servers cause race conditions, double-processing, and data corruption.

## Use Cases
- Preventing double-processing of a job (two workers pick up the same task)
- Ensuring only one instance runs a scheduled cron job
- Coordinating access to a shared file or external API with rate limits
- Leader election (simplified form)

## Implementations

### Redis-Based (Redlock)
```
SET resource_name my_random_value NX PX 30000
```
- `NX`: Only set if key doesn't exist (atomic acquire)
- `PX 30000`: Auto-expire after 30 seconds (prevents deadlock if holder crashes)
- `my_random_value`: Unique per acquisition. Only delete the lock if value matches (prevent accidental release by another process).

**Redlock** (multi-node): Acquire the lock on N/2 + 1 independent Redis instances. If majority acquired within timeout, lock is held. **Controversial** — Martin Kleppmann (2016) showed it's unsafe under clock skew, GC pauses, and network delays, because Redlock relies on wall-clock TTLs and lacks fencing tokens. antirez (Redis creator) defended it, but the consensus in 2025 is: **for correctness-critical workloads use a CP system (etcd/ZooKeeper) with fencing tokens; for efficiency-only locking (e.g., "don't run this job twice in 5 minutes") single-instance Redis with `SET NX PX` is sufficient.**

### ZooKeeper-Based
Create an ephemeral sequential node under a lock path. If your node has the lowest sequence number, you have the lock. If not, watch the next-lowest node. When it's deleted, check again.
- **Pros**: Reliable (CP system), automatic release on session death
- **Cons**: Higher latency, more complex

### Database-Based
```sql
INSERT INTO locks (resource, holder, expires_at) 
VALUES ('order-123', 'worker-5', NOW() + INTERVAL '30s')
ON CONFLICT DO NOTHING;
-- If insert succeeds, lock acquired
```
- **Pros**: No extra infrastructure
- **Cons**: DB load, clock-based expiry, polling for release

### etcd-Based
Use etcd's lease mechanism. Create a key with a lease (TTL). Lease must be renewed (keep-alive). If the holder fails, the lease expires and the key is deleted.

## Critical Issues

### Deadlock Prevention
If the lock holder crashes without releasing, the lock is stuck forever. **Solution**: Always set a TTL/expiry on locks. Trade-off: if the holder takes longer than the TTL, the lock expires while work is still in progress.

### Fencing Tokens
The most important concept. Even with TTL-based locks, this can happen:
1. Process A acquires lock with TTL 30s
2. Process A has a long GC pause (40s)
3. Lock expires. Process B acquires lock.
4. Process A resumes, thinks it still holds the lock.
5. Both A and B write to the resource → data corruption.

**Solution**: Each lock acquisition returns a monotonically increasing fencing token. The resource (database, storage) rejects writes with an older token than the last one it accepted.

### Lock Granularity
- **Coarse-grained**: One lock for the entire resource. Simple but limits concurrency.
- **Fine-grained**: Separate locks per record/row. More concurrency but more complexity and lock management.

## Possible Interview Questions
1. "How would you prevent two servers from processing the same job simultaneously?"
2. "Explain the Redlock algorithm and its controversy."
3. "What happens if a process holding a distributed lock crashes?"
4. "What are fencing tokens and why are they important?"
5. "Compare Redis vs ZooKeeper for distributed locking."
