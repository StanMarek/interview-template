# Distributed Lock Service -- Architecture Design

## Requirements

### Functional
- Acquire and release named locks with configurable TTL
- Support lock renewal (lease extension) via heartbeat
- Provide fencing tokens to prevent split-brain scenarios
- Watch/subscribe mechanism to be notified when a lock becomes available
- Support both exclusive locks and read-write locks
- Session-based locks that auto-release when the client session expires
- Lock queuing (fair ordering for waiters)

### Non-Functional
- **Consistency:** Linearizability -- if lock acquisition returns success, no other client can hold the same lock simultaneously. This is the defining property of a correct lock.
- **Availability:** Available as long as a majority of nodes are reachable (Raft quorum: 3 out of 5)
- **Durability:** Lock state survives node restarts and leader failovers
- **Latency:** p99 < 10ms for lock acquisition (single datacenter)
- **Throughput:** 100,000 lock operations per second

## Scale Estimates
- 100K lock acquisitions/releases per second
- 10M concurrent active locks
- Lock state size: ~200 bytes per lock, so ~2GB total in-memory
- 5-node Raft cluster in a single region
- Multi-region: separate clusters per region with application-level partitioning

## Architecture Decisions

### Decision 1: Raft Consensus for Lock State

Lock state is replicated across a 5-node Raft cluster. The leader handles all write operations (acquire, release, renew). A lock is considered acquired only after the Raft log entry is committed by a majority (3 out of 5 nodes).

**Why Raft and not Paxos:** Raft is easier to implement and reason about. It provides the same safety guarantees as Multi-Paxos but with a clearer leader election protocol and simpler log replication. For a lock service, understandability is critical because bugs in the consensus layer mean violated mutual exclusion -- the one thing a lock service must never do.

**Why not Redis/Redlock:** Martin Kleppmann's analysis of Redlock showed fundamental safety issues. Redlock relies on clock synchronization across independent Redis nodes, and clock drift can cause two clients to simultaneously believe they hold the lock. A Raft-based approach uses logical clocks (log indices) and doesn't depend on wall-clock time for safety. This is the key insight that separates senior from mid-level answers: understanding WHY Redis-based locks are insufficient for correctness-critical use cases.

**Trade-off:** Raft requires a majority quorum, so we sacrifice availability during network partitions (CP system per CAP). A 5-node cluster tolerates 2 node failures. With 3-node, we tolerate only 1. The performance ceiling is also limited by the leader bottleneck -- all writes go through a single node.

### Decision 2: Fencing Tokens for Split-Brain Protection

Every successful lock acquisition returns a monotonically increasing fencing token (the Raft log index). The protected resource must validate that the fencing token in the request is greater than or equal to the last seen token.

**Why this is critical:** Even with Raft, there's a scenario where safety can be violated. Client A acquires lock, then experiences a long GC pause. The lock's TTL expires. Client B acquires the lock and starts working. Client A wakes up from GC, still thinks it holds the lock, and writes to the shared resource. Without fencing tokens, both clients have now written to the resource concurrently.

**The fencing token solves this:** Client A's write carries token 42. Client B's write carries token 43. The storage system rejects Client A's write because 42 < 43 (the last seen token). This is the ONLY mechanism that truly prevents split-brain writes. TTLs alone are not sufficient.

**Trade-off:** Fencing tokens require cooperation from the protected resource. If the resource doesn't validate tokens, the guarantee is lost. This is an API design challenge -- the lock service must make it easy (or mandatory) for resources to check tokens.

### Decision 3: Session-Based Locks with Ephemeral Nodes

Clients establish sessions with the lock service. Locks are associated with sessions, not individual requests. If a session expires (client fails to heartbeat), all locks held by that session are automatically released.

**Why sessions over simple TTL:** Simple TTL-based locks have a problematic edge case. If the client is alive but the network between client and lock service is partitioned, the lock expires even though the client is still working with the protected resource. Sessions provide a more nuanced model: the lock service can distinguish between "client is dead" (session expired, release the lock) and "client is alive but slow" (extend the session).

**Trade-off:** Sessions add complexity. The lock service must track session state, process heartbeats, and handle session expiry atomically. But this is exactly the approach ZooKeeper takes, and it's proven in production at massive scale.

### Decision 4: Write-Ahead Log with Periodic Snapshots

Every lock operation is first written to the Raft WAL. Periodically, the entire lock state is snapshotted to durable storage, and old WAL entries are compacted. On recovery, the node loads the latest snapshot and replays the WAL from that point.

**Why this matters:** Without snapshots, a new node joining the cluster would need to replay the entire WAL from the beginning. For a lock service processing 100K operations/second, the WAL grows at ~20MB/second. Without compaction, recovery after a few hours of operation would take unacceptably long.

## Consistency Model

**Linearizability** for all lock operations. This means:

1. If `acquire(lock_A)` returns success at time T1, no other `acquire(lock_A)` can return success until the lock is released.
2. The order of operations is consistent with real-time ordering -- if operation A completes before operation B starts, A appears before B in the total order.

This is achieved through Raft consensus, which provides linearizability by routing all operations through a single leader and committing them through majority agreement.

**Why linearizability and not sequential consistency:** Sequential consistency allows reordering of operations from different clients as long as each client's operations appear in order. For a lock service, this is insufficient. Two clients could both acquire the same lock if their operations are reordered. Linearizability is the minimum correctness requirement.

**Impact on performance:** Linearizability requires every operation to go through the Raft leader and wait for majority acknowledgment. This limits throughput to what a single leader can handle (~100K ops/sec with batching). Read operations can optionally use "consistent reads" (read through Raft) for linearizable reads, or "stale reads" (read from any node) for lower latency when the application can tolerate it.

## Failure Modes

### Leader node failure
Raft elects a new leader within the election timeout (typically 1-5 seconds). During election, no lock operations can proceed. All committed locks are preserved because they're replicated to a majority. Uncommitted lock requests are failed and must be retried by the client.

### Client failure (crash)
The client's session eventually expires (missed heartbeats). All locks held by that session are released. Other clients watching those locks are notified. The session timeout must be long enough to tolerate temporary network hiccups but short enough to release locks promptly when a client truly dies. Typical values: 10-30 seconds.

### Network partition
If the leader is on the minority side of a partition, it steps down. The majority side elects a new leader. Clients connected to minority nodes cannot acquire or release locks until the partition heals. Locks held by clients on the majority side continue to be valid. Locks held by clients on the minority side expire when their sessions time out.

### Split-brain (two leaders)
Raft guarantees at most one leader per term. If a network partition creates a temporary state where two nodes think they're leader, only the one with majority support can commit operations. The other leader's operations will fail. Fencing tokens provide an additional safety net at the resource level.

### Clock drift
The lock service does not depend on wall-clock time for safety. Lease TTLs use the Raft log index as a logical clock. Even if clocks drift, the consensus protocol ensures correct lock semantics. This is a critical design choice -- systems that rely on wall-clock time for lock safety (like Redlock) are fundamentally broken when clocks misbehave.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Client SDK** | Provides acquire/release/renew API, manages sessions, handles fencing tokens |
| **Leader Node** | Handles all write operations, proposes entries to Raft log |
| **Follower Nodes** | Replicate log entries, participate in quorum, serve stale reads |
| **Quorum Check** | Ensures majority agreement before committing a lock operation |
| **Lock State Table** | In-memory map of lock_key -> (owner, fencing_token, TTL, session_id) |
| **Lease Manager** | Tracks TTLs, expires stale locks, extends leases on heartbeat |
| **Heartbeat Monitor** | Processes client heartbeats, detects dead sessions |
| **Fencing Token Validator** | Issues monotonically increasing tokens (Raft log index) |
| **Client Session** | Ephemeral session tracking, auto-releases locks on session death |
| **Watch Mechanism** | Notifies waiting clients when a lock becomes available |
| **Leader Election** | Raft protocol for electing a new leader on failure |
| **Deadlock Detector** | Detects cycles in lock dependency graphs (for read-write locks) |
| **WAL** | Durable log of all lock operations for crash recovery |
| **Snapshots** | Periodic full state captures for fast recovery |
| **Monitoring** | Tracks lock contention, acquisition latency, session count |
| **Audit Log** | Records who held what lock and when, for debugging |

## Key Trade-offs

### Consistency vs. Availability (CAP)
We choose **consistency** (CP). A lock service that is available but inconsistent (allows two holders) is worse than useless -- it gives a false sense of safety. During a partition, the minority side is unavailable. This is the correct behavior.

### Latency vs. Safety
Every lock operation requires a Raft round trip (leader -> followers -> quorum -> respond). This adds 2-5ms in a single datacenter. We could make reads faster by allowing stale reads, but for lock acquisition, we cannot compromise on linearizability.

### Complexity vs. Correctness
Fencing tokens, sessions, WAL, snapshots, and deadlock detection are all complex. But each solves a specific correctness problem. A simpler lock service (e.g., Redis SETNX with TTL) would be faster and easier to operate but cannot provide the correctness guarantees that serious distributed systems require.

## What Fails First

**Lock contention on hot keys.** When many clients compete for the same lock, the system degenerates into a serial queue. The p99 latency grows linearly with the number of waiters. At 1000 concurrent requests for the same lock, the 999th waiter has to wait for all 998 before it.

**Mitigation:** 
1. Lock striping -- split a hot lock into N sub-locks when the resource can be partitioned.
2. Read-write locks -- allow concurrent readers when there's no writer.
3. Advisories to clients to use exponential backoff on lock retry.

## v1 vs v2

### v1 (Ship first)
- 3-node Raft cluster
- Exclusive locks only (no read-write locks)
- Simple TTL-based expiry (no sessions)
- Fencing tokens via Raft log index
- Basic monitoring (lock count, acquisition latency)
- Single datacenter deployment
- gRPC API

### v2 (Scale and harden)
- 5-node Raft cluster with learner nodes for fast scaling
- Read-write locks with deadlock detection
- Session-based lock management with heartbeat
- Watch/subscribe notifications for lock availability
- Lock queuing with fair ordering
- Multi-datacenter deployment with lock partitioning
- Lock hierarchy (parent-child locks)
- Admin UI for lock inspection and force-release
- Integration with service mesh for automatic lock cleanup on pod termination
