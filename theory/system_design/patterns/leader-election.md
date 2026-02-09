# Leader Election

## What It Is
Leader election is a process by which distributed nodes agree on one node to act as the coordinator (leader) for a specific task. The leader handles responsibilities like write coordination, task scheduling, or partition management.

## Why It Matters
Many distributed algorithms require a single coordinator: database write leaders, Kafka partition leaders, distributed cron jobs, shard assignment.

## Approaches

### Bully Algorithm
Node with the highest ID becomes leader. If the current leader fails, the highest-ID surviving node takes over. Simple but not partition-tolerant.

### Raft-Based Election
Nodes are in one of three states: follower, candidate, leader. On leader timeout, a follower becomes a candidate and requests votes. Majority wins. Heartbeats maintain leadership.

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

**Solution**: Each leader election issues a monotonically increasing **fencing token** (epoch number). All operations include the token. The storage/resource rejects operations with older tokens.

## Possible Interview Questions
1. "How would you ensure only one instance runs a scheduled job in a distributed system?"
2. "What is split-brain and how do fencing tokens prevent it?"
3. "Compare lease-based vs consensus-based leader election."
4. "The leader fails. Walk me through what happens next."
