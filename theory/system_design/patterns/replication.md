# Replication

## What It Is
Replication is the process of copying data from one database server (primary/leader) to one or more other servers (replicas/followers). It provides redundancy, improves read throughput, and enables disaster recovery.

## Why It Matters
Replication is the foundation of high availability. Without replication, a single server failure = total data loss and downtime.

## Replication Topologies

### Single-Leader (Primary-Replica)
One leader accepts all writes. Replicas copy the leader's write-ahead log (WAL) and serve reads.

```
Writes → [Leader] → replication → [Replica 1] ← Reads
                                → [Replica 2] ← Reads
                                → [Replica 3] ← Reads
```

- **Pros**: Simple, well-understood, strong consistency for writes
- **Cons**: Leader is a write bottleneck and SPOF (until failover)

### Multi-Leader (Active-Active)
Multiple nodes accept writes. Each replicates to the others.

- **Pros**: Write scalability, multi-region writes, no single write SPOF
- **Cons**: Write conflicts (two leaders update the same row simultaneously)
- **Conflict resolution**: Last-write-wins (LWW), merge function, CRDTs, manual resolution
- **Use cases**: Multi-datacenter writes, collaborative editing (Google Docs)

### Leaderless (Dynamo-style)
No single leader. Any node can accept reads and writes. Uses quorum-based consistency.

- **Read quorum (R)** + **Write quorum (W)** > **Total nodes (N)** ensures overlap
- Example: N=3, W=2, R=2 — every read sees at least one up-to-date node
- **Pros**: No failover needed, write to any node
- **Cons**: Complex conflict resolution, read repair, anti-entropy needed

## Synchronous vs Asynchronous Replication

| Type | How It Works | Pros | Cons |
|------|-------------|------|------|
| **Synchronous** | Leader waits for replica ACK before confirming write | Zero data loss, strong consistency | Higher write latency; one slow replica blocks writes |
| **Asynchronous** | Leader confirms write immediately, replicates later | Low write latency, leader never blocks | Replication lag → stale reads; data loss if leader fails |
| **Semi-synchronous** | Wait for 1 replica ACK (out of N) | Balance of durability and performance | Slightly higher latency than full async |

## Replication Lag and Its Consequences

Asynchronous replication introduces **replication lag** — the delay between a write on the leader and that write becoming visible on replicas. This leads to:

### Read-After-Write Inconsistency
User writes data, then reads from a replica that hasn't received the write yet. "I just posted a comment but I can't see it!"

**Solutions**: Read-your-writes consistency — route the user's reads to the leader for recently-written data, or use a timestamp/version to check if the replica is up to date.

### Monotonic Read Inconsistency
User makes two reads and sees data go "backward" (first read from an up-to-date replica, second from a lagging one).

**Solutions**: Pin a user to a specific replica (session affinity), or use version vectors.

### Causal Inconsistency
User sees the reply to a comment but not the original comment (reply replicated faster than the comment).

**Solutions**: Causal ordering via logical clocks or dependency tracking.

## Failover

When the leader fails, a replica must be promoted. This is one of the hardest operational problems.

### Failover Steps
1. **Detect failure**: Heartbeat timeout (typically 10-30 seconds)
2. **Elect new leader**: Most up-to-date replica, or consensus algorithm
3. **Reconfigure clients**: Route writes to the new leader (via DNS update, VIP swap, or proxy)

### Failover Risks
- **Split-brain**: Old leader comes back thinking it's still leader → two leaders accepting writes → data divergence. Prevent with fencing tokens.
- **Data loss**: If async replication, the new leader may be behind the old leader. Lost writes.
- **Cascading failures**: Failover triggers burst of traffic to the new leader, which then also fails.

## Replication in Practice

| Database | Replication Type | Notes |
|----------|-----------------|-------|
| PostgreSQL | Single-leader, streaming replication | Sync or async per replica |
| MySQL | Single-leader, binlog replication | GTID for tracking position |
| MongoDB | Replica sets (single-leader with automatic failover) | Reads from secondaries configurable |
| Cassandra | Leaderless, tunable consistency | Uses gossip protocol, hinted handoff |
| DynamoDB | Leaderless (internally), multi-region with Global Tables | Managed by AWS |
| CockroachDB | Multi-active, Raft consensus per range | Strong consistency, automatic rebalancing |
| Spanner / YugabyteDB / TiDB | Multi-Raft / Paxos per shard | Globally-distributed strong consistency via TrueTime or HLC |
| Aurora | Storage-level replication (6-way) | Leader + 5 replicas share distributed storage; instant failover |

## Possible Interview Questions
1. "How would you ensure zero data loss during a leader failure?"
2. "User writes a post but then can't see it. What's happening and how do you fix it?"
3. "When would you choose multi-leader replication over single-leader?"
4. "Explain how quorum reads and writes work in a leaderless system."
5. "What is split-brain and how do you prevent it?"
6. "Your replication lag is 30 seconds. What are the user-visible impacts and how do you reduce it?"
7. "How would you replicate data across two continents with minimal latency impact?"
