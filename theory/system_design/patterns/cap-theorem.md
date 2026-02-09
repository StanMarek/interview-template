# CAP Theorem & Consistency Models

## CAP Theorem
In a distributed data store, during a network partition, you can only guarantee two of three properties:

- **Consistency (C)**: Every read receives the most recent write or an error
- **Availability (A)**: Every request receives a (non-error) response, without guarantee it's the most recent write
- **Partition Tolerance (P)**: The system continues to operate despite network partitions between nodes

Since network partitions are inevitable in distributed systems, the real choice is **CP** (consistency over availability) or **AP** (availability over consistency) during a partition.

### CP Systems
During partition, reject requests to partitioned nodes to maintain consistency.
- Examples: HBase, MongoDB (default), ZooKeeper, etcd, Redis Cluster
- Use when: Banking, inventory, anything where stale reads are dangerous

### AP Systems
During partition, serve requests from any reachable node, even if data might be stale.
- Examples: Cassandra, DynamoDB, CouchDB, DNS
- Use when: Social media feeds, shopping carts, session stores — eventual consistency is acceptable

### Important Nuances
- CAP is about behavior **during a partition**. When the network is healthy, you can have all three.
- CAP is a spectrum, not binary. Systems can be tuned (e.g., Cassandra with tunable consistency per query).
- CAP doesn't capture latency, which is often more important than theoretical consistency.

## PACELC Theorem (Extension of CAP)
If there is a **P**artition, choose **A**vailability or **C**onsistency; **E**lse (no partition), choose **L**atency or **C**onsistency.

This captures the reality that even without partitions, there's a trade-off between consistency and latency.

| System | During Partition | Else (Normal) |
|--------|-----------------|---------------|
| DynamoDB | PA | EL (fast, eventually consistent) |
| Cassandra | PA | EL |
| MongoDB | PC | EC |
| HDFS | PC | EC |
| MySQL (single node) | N/A | EC |

## Consistency Models

### Strong Consistency (Linearizability)
Every read sees the most recent write. The system behaves as if there is a single copy of the data.
- Implementation: Synchronous replication, consensus algorithms (Raft, Paxos)
- Cost: Higher latency, lower throughput

### Sequential Consistency
All nodes see operations in the same order, but that order might not match real-time ordering.

### Causal Consistency
If operation A causally precedes operation B, all nodes see A before B. Concurrent operations can be seen in different orders by different nodes.
- Implementation: Vector clocks, version vectors

### Read-Your-Writes Consistency
A user always sees their own writes, but may see stale data from other users.
- Implementation: Read from leader for recent writes, or version tracking

### Eventual Consistency
If no new writes are made, all replicas will eventually converge to the same value. No guarantee on how long "eventually" takes.
- Implementation: Asynchronous replication, gossip protocols, anti-entropy

### Monotonic Read Consistency
Once a user reads a value, they will never see an older value in subsequent reads.
- Implementation: Pin user to a replica, or track read versions

## Consensus Algorithms

### Raft
- Leader-based consensus. Leader receives writes, replicates to followers, commits when majority acknowledge.
- **Used by**: etcd, CockroachDB, TiKV, Consul
- **Key terms**: Leader election, log replication, commit index, term number

### Paxos
- More theoretical, harder to implement. Multi-phase protocol for agreement.
- **Used by**: Google Chubby, Spanner

### ZAB (ZooKeeper Atomic Broadcast)
- Similar to Raft but predates it. Used exclusively by ZooKeeper.

## Possible Interview Questions
1. "Explain the CAP theorem. Can you have all three?"
2. "Is your design CP or AP? Why is that the right choice?"
3. "What's the difference between strong consistency and eventual consistency?"
4. "Your system uses eventual consistency. A user writes a review but can't see it. How do you solve this?"
5. "When would you choose eventual consistency over strong consistency?"
6. "Explain how Raft consensus works at a high level."
7. "What is PACELC and why is it more useful than CAP alone?"
