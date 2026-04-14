# Consensus and Quorums

## What It Is
Consensus is the problem of getting a group of distributed nodes to agree on a single value (or ordered sequence of values) despite crashes, slow nodes, or network problems. A quorum is the minimum number of nodes whose agreement is sufficient to call a decision "committed". Quorum math is the arithmetic backbone of every consensus protocol.

## Why It Matters
Any system that needs a single source of truth under failure — leader election, cluster membership, distributed locks, replicated state machines, config stores — ultimately reduces to consensus. Getting this wrong is how you get split-brain, lost writes, and double charges.

## Quorum Math

For `N` replicas, a **majority quorum** is `floor(N/2) + 1`:

| N (replicas) | Majority quorum | Can tolerate failures |
|--------------|-----------------|-----------------------|
| 1            | 1               | 0                     |
| 3            | 2               | 1                     |
| 5            | 3               | 2                     |
| 7            | 4               | 3                     |

Why odd numbers? `N=4` also only tolerates 1 failure (quorum = 3), so you pay for an extra replica with no extra fault tolerance. Always deploy consensus clusters with an odd number of nodes (3, 5, 7).

### Read/Write Quorums (Dynamo-style)
Tunable-consistency systems (Cassandra, DynamoDB) use separate `R` and `W` quorums. If `R + W > N`, reads and writes overlap on at least one node, giving strong consistency. Common configs:
- `N=3, W=2, R=2` → strong, survives 1 node failure
- `N=3, W=3, R=1` → fast reads, writes fail if any node is down
- `N=3, W=1, R=1` → very fast, eventually consistent only

### Flexible Quorums
Not all quorums must be majorities — they just need to intersect. Paxos literature calls this "flexible quorums". Useful for asymmetric workloads (e.g., write to any 2-of-5, read from any 4-of-5).

## Raft in One Screen

Raft is the de facto modern consensus algorithm. Every node is in one of three states:

- **Follower** — passively receives heartbeats from the leader.
- **Candidate** — election timeout expired; requests votes.
- **Leader** — one per term; accepts writes and replicates to followers.

### Election
1. Each node has a randomized election timeout (typically 150-300 ms).
2. On timeout, follower becomes candidate, increments `term`, votes for itself, asks peers for votes.
3. First candidate to collect a majority wins. Ties force a new election with a new random timeout.
4. Leader sends heartbeats (`AppendEntries` with no data) to suppress elections.

### Log Replication
1. Client write goes to leader.
2. Leader appends to its log, sends `AppendEntries` RPC to followers.
3. Once a majority acknowledge, leader marks entry **committed** and applies it to the state machine.
4. Leader piggybacks the commit index on the next heartbeat; followers then apply.

### Safety Invariants
- **Election safety**: at most one leader per term.
- **Leader append-only**: leaders never overwrite their log.
- **Log matching**: if two logs agree at an index and term, they agree on everything before.
- **State machine safety**: once an entry is applied, no other state machine will apply a different entry at that index.

## Paxos (Brief)
Paxos predates Raft and is equivalent in power but notoriously harder to implement. Two roles you will hear about:
- **Proposer** — drives agreement on a value via prepare/accept rounds.
- **Acceptor** — votes. A majority of acceptors must agree for a value to be chosen.

Variants: Multi-Paxos (run Paxos repeatedly for a log), Fast Paxos (fewer round-trips in the common case), EPaxos (leaderless). In interviews, you can almost always use Raft as the reference and mention Paxos as the older, more general sibling.

## ZAB and Viewstamped Replication
- **ZAB** (ZooKeeper Atomic Broadcast) — Raft-like, predates Raft, used only by ZooKeeper.
- **Viewstamped Replication** — 1988, Raft's actual conceptual ancestor. Worth name-dropping if the interviewer likes history.

## Consensus vs Gossip — When To Use Which

| Need                                      | Use consensus (Raft/Paxos) | Use gossip / eventual consistency |
|-------------------------------------------|----------------------------|-----------------------------------|
| Single value everyone must agree on       | Yes                        | No                                |
| Linearizable reads/writes                 | Yes                        | No                                |
| Leader election, distributed lock         | Yes                        | No                                |
| Cluster membership in a 3-7 node control plane | Yes                   | Maybe                             |
| Cluster membership for 1000s of nodes     | No (too chatty)            | Yes (SWIM, HyParView)             |
| Failure detection, metadata fan-out       | No                         | Yes                               |
| AP data store (Cassandra, Dynamo)         | No                         | Yes                               |

Rule of thumb: consensus gives you **correctness at the cost of latency and scale**; gossip gives you **scale and availability at the cost of freshness**. Large systems often use both — a small Raft-backed control plane (etcd) managing a large gossip-based data plane (service mesh, DB cluster).

## Real Systems

| System    | Algorithm            | Role                                           |
|-----------|----------------------|------------------------------------------------|
| etcd      | Raft                 | Kubernetes control plane, service discovery    |
| Consul    | Raft                 | Service discovery, KV, distributed locks       |
| ZooKeeper | ZAB                  | HBase/Kafka (pre-KRaft) coordination           |
| Kafka     | KRaft (Raft variant) | Controller metadata since 4.0                  |
| Spanner   | Paxos (per shard)    | Globally consistent SQL                        |
| CockroachDB / TiKV | Raft        | Per-range replication                          |
| Chubby    | Paxos                | Google's internal lock service                 |

## Cost of Consensus
Each committed write needs at least one round-trip to a majority. In a 3-node cluster spread across AZs (~1-2 ms each), expect **~2-4 ms minimum write latency**. Across regions (~60-80 ms RTT), that jumps to **~120-160 ms**. This is why geo-distributed consensus systems often pin leaders per shard to the region with the most writes (Spanner, CockroachDB "follower reads" / "leaseholder locality").

## Common Pitfalls and Red Flags
- "We'll use Raft across 3 continents for low-latency writes." — every write pays a cross-region RTT.
- Even-numbered cluster sizes (4, 6 nodes).
- Home-rolling leader election with timestamps and no fencing token (see [leader-election.md](leader-election.md)).
- Putting 1 million nodes in a Raft group. Raft is for small control planes.
- Assuming consensus gives you exactly-once semantics end-to-end. It only gives you a consistent log — you still need idempotency at the application layer.

## Possible Interview Questions
1. Why do Raft clusters have 3 or 5 nodes instead of 2 or 4?
2. Walk me through what happens when a Raft leader is partitioned from the cluster.
3. When would you choose gossip over consensus for cluster membership?
4. Explain `R + W > N` and why it gives strong consistency.
5. What is the difference between a quorum read and a linearizable read?
6. A Raft cluster of 5 nodes has 2 nodes down. Can it still accept writes? What if 3 are down?
7. How does Raft prevent two leaders from being elected in the same term?
8. Why is consensus slow across regions and how do real systems mitigate it?

## Related
- [leader-election.md](leader-election.md) — fencing tokens and split-brain.
- [cap-theorem.md](cap-theorem.md) — consistency models and PACELC.
- [replication.md](replication.md) — sync vs async replication.
- [distributed-locking.md](distributed-locking.md) — consensus-backed locks.
