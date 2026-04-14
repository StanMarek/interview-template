# Multi-Region Data Replication Strategy -- Architecture Design

## Requirements

### Functional
- Replicate data across geographically distributed regions (US-East, EU-West, AP-Southeast)
- Support configurable replication modes: sync, async, semi-sync
- Conflict detection and resolution for multi-writer scenarios
- Automatic failover when a region becomes unavailable
- Configurable consistency levels per operation or per table
- Cross-region read routing for low-latency reads

### Non-Functional
- **Consistency:** Configurable -- strong consistency within a region, eventual consistency across regions (default), with option for linearizable cross-region reads at higher latency
- **Availability:** 99.999% (5 nines) -- survive entire region failures
- **Durability:** Zero data loss for synchronous replication mode; RPO < 1 second for async mode
- **Latency:** Same-region reads < 5ms, cross-region reads < 100ms, cross-region writes < 200ms
- **Replication lag:** p99 < 500ms for async replication under normal conditions

## Scale Estimates
- 3 regions (US-East, EU-West, AP-Southeast)
- 500K writes/second globally, 5M reads/second globally
- 50 TB of data per region
- Cross-region bandwidth: 10 Gbps dedicated links
- Replication throughput: 100K change events/second per region pair

## Architecture Decisions

### Decision 1: Async Replication as Default, Sync as Opt-In

The default replication mode is asynchronous. Changes are committed locally first, then shipped to remote regions via a change stream (CDC). Synchronous cross-region replication is available as an opt-in for specific tables or operations.

**Why async by default:** Cross-region latency is 50-150ms (physics -- speed of light in fiber). Synchronous replication would add this latency to every write. For a system doing 500K writes/second, that means every write takes 50-150ms longer, which is unacceptable for most use cases. Async replication lets writes complete at local speed with the trade-off of temporary cross-region inconsistency.

**The critical insight:** This is a CAP theorem trade-off made explicit. With async replication, during a network partition between regions, each region can continue accepting writes independently (AP). When the partition heals, conflicts must be resolved. With sync replication, during a partition, the remote region cannot accept writes for synced data (CP). The choice between AP and CP should be made per data type, not globally.

**Trade-off:** Async replication means RPO > 0. If a region fails before its changes are replicated, those changes are lost. For most data, this is acceptable. For financial transactions, it is not -- those use sync replication.

### Decision 2: Version Vectors for Conflict Detection

Each write carries a version vector (a logical clock per region). When a change arrives at a remote region, the version vector is compared to detect concurrent writes (conflicts). Concurrent writes to the same key from different regions are flagged for resolution.

**Why version vectors over timestamps:** Wall-clock timestamps are unreliable across regions. Even with NTP, clock skew of tens of milliseconds is common. If two regions write to the same key within that skew window, timestamp-based ordering gives unpredictable results. Version vectors detect true concurrency without depending on wall clocks.

**Why not CRDTs:** CRDTs (Conflict-Free Replicated Data Types) provide automatic conflict resolution for specific data structures (counters, sets, maps). They're excellent for specific use cases but don't generalize to arbitrary relational data. We use CRDTs where they fit (counters, last-writer-wins registers) and custom conflict resolution for complex business logic.

### Decision 3: Single-Leader Per Partition, Multi-Leader Per System

Data is partitioned by key range or hash. Each partition has a single leader in one region. Different partitions can have leaders in different regions. This gives us the simplicity of single-leader replication per partition with the write distribution benefits of multi-leader.

**Why this hybrid approach:** Pure single-leader (all writes go to one region) creates a single point of failure and adds latency for remote writers. Pure multi-leader (all regions accept writes for all data) creates conflicts that are expensive to resolve. Partitioned leadership gives us: (a) no conflicts within a partition (single leader), (b) distributed write load (leaders across regions), (c) predictable failover (promote the nearest replica).

**Trade-off:** This requires careful partition assignment to co-locate leaders near their primary writers. If a user in EU frequently writes to a partition led by US-East, every write crosses the Atlantic. The routing layer must be smart about this.

### Decision 4: Anti-Entropy Protocol for Consistency Verification

A background process periodically compares Merkle tree hashes between regions to detect data divergence. If divergence is found, the specific divergent keys are identified and reconciled.

**Why anti-entropy:** Async replication can drop events (network issues, queue overflow, bugs). Without anti-entropy, regions can silently diverge and never converge. The Merkle tree comparison is efficient -- it compares O(log N) hashes to identify divergent keys in a dataset of N keys.

## Consistency Model

**Causal consistency within a session.** A user's writes in region A are guaranteed to be visible to that user when they read from region A (read-your-writes). If the user's traffic is routed to region B (failover), their recent writes may not be visible yet -- this is the fundamental limitation of async replication.

**Eventual consistency across regions.** Under normal operation, replication lag is < 500ms (p99). All regions converge to the same state. Conflicts are resolved deterministically using: (1) application-defined resolution rules, (2) last-writer-wins with version vectors, or (3) CRDT semantics for supported data types.

**Optional strong consistency.** For operations that require cross-region agreement (e.g., global unique constraint enforcement), the application can request a synchronous cross-region read or write. This routes the operation through a global consensus protocol (like Spanner's TrueTime or CockroachDB's hybrid-logical clocks) and adds 100-200ms latency.

**Why this model:** There is no free lunch in distributed systems. You cannot have low-latency, high-availability, AND strong consistency across regions simultaneously (CAP theorem). We make the trade-off explicit and configurable: most operations use eventual consistency for speed, critical operations use strong consistency and pay the latency cost.

## Failure Modes

### Single region failure
Global DNS detects the unhealthy region and routes traffic to surviving regions. The failover controller promotes replicas in the nearest healthy region. Writes that were in-flight at the failed region may be lost (RPO depends on replication lag). For sync-replicated data, RPO = 0.

### Network partition between regions
Each region continues operating independently (AP mode). Async replication queues changes locally. When the partition heals, changes are replayed and conflicts are resolved. The replication queue must be durable (Kafka with disk persistence) to survive long partitions.

### Replication lag spike
The lag monitor triggers alerts when replication lag exceeds thresholds. If lag exceeds SLA (e.g., > 5 seconds), the system can: (a) throttle writes at the source to let replication catch up, (b) route reads for affected data to the primary region, or (c) switch to synchronous replication temporarily. The choice depends on the business impact.

### Split-brain (both regions accept conflicting writes)
The version vector detects concurrent writes. The conflict resolver applies deterministic rules: (1) for idempotent operations, both can be applied; (2) for last-writer-wins, the higher version vector wins; (3) for business-critical conflicts, the write is rejected and escalated for manual resolution.

### Data corruption in one region
The anti-entropy protocol detects divergence via Merkle tree comparison. The corrupted region re-syncs from a healthy region. During re-sync, reads are served from healthy regions.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Regional LB** | Routes traffic to local app servers, handles TLS termination |
| **App Server** | Stateless request handler, routes writes to partition leader |
| **Primary DB (Leader)** | Accepts writes for partitions it leads, replicates locally |
| **Local Replica** | Serves reads within the region, provides intra-region HA |
| **WAL Shipper** | Captures change stream (CDC) from the primary DB |
| **WAL Receiver** | Applies incoming changes from remote regions |
| **Conflict Resolver** | Detects and resolves concurrent writes using version vectors |
| **Local Cache (Redis)** | Reduces DB load for frequently read data |
| **Global DNS** | Routes users to the nearest healthy region (latency-based routing) |
| **Replication Queue (Kafka)** | Durable cross-region change event transport |
| **Failover Controller** | Monitors region health, orchestrates failover and promotion |
| **Version Vector Service** | Maintains logical clocks for conflict detection |
| **Config Store (etcd)** | Stores partition-to-leader mapping, replication config |
| **Lag Monitor** | Tracks replication lag per region pair, triggers alerts |
| **Consistency Checker** | Anti-entropy protocol using Merkle trees |
| **Split-Brain Detector** | Monitors for conflicting writes that indicate partition |
| **Alerting & Dashboard** | Visualizes replication health, lag, conflicts |

## Key Trade-offs

### Latency vs. Consistency
Async replication gives low-latency writes (local speed) but eventual consistency. Sync replication gives strong consistency but adds 50-150ms per write. The system lets the application choose per-operation.

### Complexity vs. Correctness
Version vectors, anti-entropy, conflict resolution -- these add significant operational complexity. But without them, silent data divergence is inevitable. The cost of debugging divergent data in production far exceeds the cost of building these mechanisms.

### RPO vs. Write Throughput
Sync replication (RPO = 0) reduces write throughput because every write must cross the WAN. Async replication (RPO > 0) allows full local write speed. For most systems, the business value of zero data loss doesn't justify the throughput reduction for all data.

## What Fails First

**Cross-region replication queue becomes the bottleneck.** During a write spike in one region, the change stream overwhelms the Kafka bridge. Replication lag grows, and the secondary region falls further behind. If the primary fails during this lag, data is lost.

**Mitigation:** Dedicated high-bandwidth links between regions. Kafka partitioning aligned with data partitioning. Backpressure mechanisms to throttle source writes when lag exceeds thresholds. Pre-provisioned burst capacity in the replication pipeline.

## v1 vs v2

### v1 (Ship first)
- 2 regions (primary + disaster recovery)
- Async replication only (no sync option)
- Single leader for all data (no partition-level leadership)
- Last-writer-wins conflict resolution
- Manual failover (operator-triggered)
- Basic lag monitoring with alerts
- No anti-entropy verification

### v2 (Scale and harden)
- 3+ regions with partition-level leadership
- Configurable sync/async per table
- Version vector-based conflict detection
- CRDT support for counters and sets
- Automatic failover with health checks
- Anti-entropy protocol with Merkle trees
- Geo-aware partition placement (co-locate leaders near writers)
- Read routing to nearest replica with consistency level options
- Compliance-aware replication (GDPR: EU data stays in EU)
- Bandwidth-aware replication scheduling (off-peak cross-region sync)
