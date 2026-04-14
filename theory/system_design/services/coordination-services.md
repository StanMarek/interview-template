# Coordination Services (ZooKeeper, etcd, Consul)

## What They Are
Distributed coordination services that provide primitives for building reliable distributed systems: configuration management, service discovery, leader election, distributed locking, and group membership.

## Core Primitives
- **Distributed configuration**: Centralized, consistent config accessible by all nodes
- **Leader election**: Agree on one coordinator node
- **Distributed locks**: Mutual exclusion across processes/machines
- **Service registry**: Track which services are alive and where
- **Barrier/Queue**: Synchronization primitives for distributed workflows
- **Watch/Notification**: Get notified when data changes

## ZooKeeper
- **Data model**: Hierarchical namespace (like a filesystem) of **znodes**. Each znode can store data (up to 1MB) and have children.
- **Ephemeral znodes**: Exist only while the session is active. Auto-deleted when the client disconnects. Used for leader election and service discovery.
- **Sequential znodes**: Automatically appended with a monotonically increasing number. Used for distributed locking (lowest sequence number holds the lock).
- **Watches**: Client registers a one-time trigger on a znode. Notified when data changes.
- **Consistency**: Linearizable writes (all go through the leader). Sequential reads (reads may be stale unless using `sync`).
- **Consensus**: ZAB (ZooKeeper Atomic Broadcast) — similar to Raft.
- **Quorum**: 2F+1 nodes tolerate F failures (3 nodes tolerate 1, 5 tolerate 2).
- **Used by**: HBase, Hadoop, Solr, ClickHouse. (Kafka removed ZooKeeper in 4.0 — replaced by KRaft.)

## etcd
- **Data model**: Flat key-value store (keys are byte strings, often used with `/`-separated paths)
- **Consistency**: Strong (Raft consensus for every write)
- **Watch**: Efficient streaming watch API (long-lived gRPC stream)
- **Lease**: TTL-based keys. If lease expires, key is deleted. Used for service registration and leader election.
- **Transactions**: Multi-key atomic compare-and-swap
- **Current version**: etcd v3.5 (LTS) and v3.6 (2024+, downgrade support, better memory management, feature gates). v2 API was removed in v3.6.
- **Used by**: Kubernetes (backing store for all cluster state), CoreDNS

## Consul
- **Multi-purpose**: Service discovery + health checking + KV store + service mesh
- **Data model**: Key-value store + service catalog
- **Consistency**: Raft consensus (CP for KV), gossip (Serf) for membership
- **Health checks**: Built-in HTTP, TCP, script, gRPC health checks
- **DNS interface**: Services registered in Consul are resolvable via DNS
- **Service mesh**: Built-in sidecar proxy (Consul Connect) with mTLS
- **Multi-datacenter**: Native support for multi-DC federation
- **Used by**: HashiCorp ecosystem, service mesh deployments

## Comparison

| Feature | ZooKeeper | etcd | Consul |
|---------|-----------|------|--------|
| Consensus | ZAB | Raft | Raft |
| Data model | Hierarchical (znodes) | Flat KV | Flat KV + service catalog |
| Watch | One-time triggers | Streaming watches | Blocking queries |
| Service discovery | DIY with ephemeral znodes | DIY with leases | Built-in |
| Health checks | Session-based | Lease-based | Built-in (HTTP, TCP, script) |
| Language | Java | Go | Go |
| Typical use | Hadoop ecosystem, HBase | Kubernetes | Service mesh, multi-DC |

## When to Use
- **ZooKeeper**: You're in the Hadoop/HBase/Solr ecosystem and need battle-tested coordination (Kafka no longer uses it as of 4.0)
- **etcd**: You're using Kubernetes, or need a simple, reliable KV with strong consistency
- **Consul**: You need service discovery + health checks + KV + service mesh in one tool. Note: HashiCorp relicensed Consul to BSL in 2023 — OpenTofu-style forks have not taken off, but check licensing for commercial use.

## Possible Interview Questions
1. "How would you implement leader election using ZooKeeper?"
2. "What role does etcd play in Kubernetes?"
3. "How do you handle the coordination service itself failing?"
4. "Compare ZooKeeper, etcd, and Consul for service discovery."
5. "What is a distributed lock and how would you build one with etcd?"
