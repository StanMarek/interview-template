# Leader Election

## What It Is
Leader election is a process by which distributed nodes agree on one node to act as the coordinator (leader) for a specific task. The leader handles responsibilities like write coordination, task scheduling, or partition management.

## Why It Matters
Many distributed algorithms require a single coordinator: database write leaders, Kafka partition leaders, distributed cron jobs, shard assignment.

## Approaches

### Bully Algorithm
Node with the highest ID becomes leader. If the current leader fails, the highest-ID surviving node takes over. Simple but not partition-tolerant, and rarely used in modern systems.

### Raft-Based Election
Nodes are in one of three states: follower, candidate, leader. On leader timeout, a follower becomes a candidate and requests votes. Majority wins. Heartbeats maintain leadership.

### Zab (ZooKeeper Atomic Broadcast)
**Zab** predates Raft. Uses primary-order broadcast with epochs for leader recovery. Conceptually closer to Multi-Paxos than Raft. Used exclusively by ZooKeeper.

### ZooKeeper / etcd Lease-Based
Create an ephemeral node (ZooKeeper) or acquire a lease (etcd). The node that successfully creates it is the leader. If the leader dies, the ephemeral node disappears, and others race to create it.

### Database-Based
Use a database row with a lock/lease:
```sql
UPDATE leader_lock SET holder = 'node-3', expires_at = NOW() + INTERVAL '30s' 
WHERE expires_at < NOW();
```
The node that successfully updates the row is the leader. Must renew the lease before expiry.

## Fencing Tokens
**Problem**: An old leader (paused by GC or network partition) resumes thinking it's still the leader → split-brain.

**Solution**: Each leader election issues a monotonically increasing **fencing token** (epoch number). All operations include the token. The storage/resource rejects operations with older tokens. This is the standard safety mechanism in systems like HDFS, GFS, and Kafka (where it's called the **leader epoch**).

## Split-Brain Prevention
Quorum majority (`floor(N/2)+1` votes required for leadership) is the primary defense. Fencing tokens protect resources accessed post-fail-over from stale leaders.

## Practical Notes
- **Kafka** uses a controller elected via KRaft / Raft to assign partition leaders. Partition leaders themselves are not elected — the controller picks them from the ISR (in-sync replicas). **KRaft timeline**: KRaft went GA in **3.3 (Oct 2022)**, default in 3.5, and ZooKeeper fully removed in Kafka 4.0 (March 2025).
- **Kubernetes** uses the `coordination.k8s.io/Lease` API for leader election of controllers (kube-controller-manager, kube-scheduler) — a lease-based mechanism backed by etcd.
- **Always prefer a mature coordination service** (etcd, ZooKeeper, Consul) over rolling your own. Correct leader election is notoriously hard.

## Possible Interview Questions
1. "How would you ensure only one instance runs a scheduled job in a distributed system?"
2. "What is split-brain and how do fencing tokens prevent it?"
3. "Compare lease-based vs consensus-based leader election."
4. "The leader fails. Walk me through what happens next."
