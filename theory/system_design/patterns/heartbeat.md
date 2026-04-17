# Heartbeat & Health Checks

## What It Is
Heartbeats are periodic signals sent by a component to indicate it's alive and functioning. Health checks are active probes by a monitoring system to verify a component's status. Together, they enable failure detection in distributed systems.

## Types

### Heartbeat (Push-Based)
Each node periodically sends "I'm alive" signals to a central monitor or peer nodes. If heartbeats stop for a configurable period (timeout), the node is considered dead.

### Health Check (Pull-Based)
A monitor actively pings each node. Three levels:
- **Liveness**: Is the process running? (TCP connection succeeds)
- **Readiness**: Can it serve traffic? (dependencies connected, warm-up complete)
- **Deep health**: Are all dependencies healthy? (DB connected, cache reachable, disk space OK)

## Failure Detection Challenges
- **Network delays vs actual failure**: Heartbeat arrives late ≠ node is dead
- **False positives**: Declaring a healthy node dead (GC pause, network hiccup) triggers unnecessary failovers
- **False negatives**: Not detecting a truly failed node
- **Timeout tuning**: Short timeout = fast detection but more false positives. Long timeout = fewer false positives but slow detection.

## Accrual Failure Detector (Phi Accrual)
Instead of binary alive/dead, compute a **suspicion level** (phi) based on the distribution of heartbeat intervals. Higher phi = more likely dead. Used by Akka and Cassandra.

**Phi Accrual Failure Detector** (Cassandra, Akka) — phi = -log₁₀(P(node alive given last heartbeat time)). Continuous suspicion score rather than binary up/down. Threshold ~8-10 typical. Adapts to network jitter.

## Deadline vs Periodic
- **Periodic** — fixed interval (e.g., every 1s).
- **Deadline-based** — next expected heartbeat at T+interval; miss = suspect. Deadline handles clock skew better.

## Gossip Protocol
In peer-to-peer systems, nodes gossip about each other's health. Each node periodically picks a random peer and exchanges membership information. Information propagates exponentially (O(log N) rounds to reach all nodes). Scales to thousands of nodes without a central coordinator.

**Used by**: Cassandra, Consul (serf), HashiCorp nomad, Redis Cluster, Riak, DynamoDB (internally).

### SWIM Protocol
Scalable Weakly-consistent Infection-style Process group Membership. Refinement of gossip that decouples failure detection from membership dissemination:
1. **Ping**: Node A pings random node B.
2. **Indirect ping**: If B doesn't respond, A asks K other nodes to ping B (rules out local network issue).
3. **Dissemination**: Membership changes piggyback on normal pings.

Used by HashiCorp Serf, Consul, and many service meshes.

## Implementation in Kubernetes
- **livenessProbe**: If fails, kubelet restarts the container
- **readinessProbe**: If fails, pod is removed from service endpoints (no traffic)
- **startupProbe**: Delays liveness checks during slow startup

## Possible Interview Questions
1. "How do you detect that a server in your distributed system has failed?"
2. "What's the difference between a liveness check and a readiness check?"
3. "How do you avoid false positives in failure detection?"
4. "Explain how gossip-based failure detection works."
