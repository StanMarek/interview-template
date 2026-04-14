# Two-Phase Commit (2PC) & Distributed Transactions

## What It Is
Two-Phase Commit (2PC) is a distributed atomic commit protocol that ensures a transaction spanning multiple nodes/resources either commits on **all** of them or aborts on **all** of them — never a mix. A central **coordinator** drives the protocol; the participating nodes are **cohorts** (or resource managers).

## Why It Matters
Classical ACID transactions assume a single DB. When a single business operation touches multiple independent systems (two DBs, DB + message queue, two microservices), you need either a distributed commit protocol or a weaker consistency scheme (sagas, outbox). 2PC is the textbook strong-consistency option — but it has real operational downsides, which is why sagas and the outbox pattern dominate in modern microservices.

## The Two Phases

### Phase 1 — Prepare (Voting)
1. Coordinator sends `PREPARE` to every cohort.
2. Each cohort performs the work locally, writes to its WAL, and **votes**:
   - `VOTE_COMMIT` — "I can commit. I've durably reserved the resources."
   - `VOTE_ABORT` — "I cannot commit."
3. Once a cohort has voted COMMIT, it is **blocked**: it must hold locks and wait for the coordinator's decision.

### Phase 2 — Commit / Abort (Decision)
1. If **all** cohorts voted COMMIT → coordinator sends `COMMIT` to all.
2. If **any** cohort voted ABORT (or timed out) → coordinator sends `ABORT` to all.
3. Cohorts apply the decision, release locks, and acknowledge.

```
Coordinator              Cohort A              Cohort B
     │──── PREPARE ──────→│                      │
     │──── PREPARE ─────────────────────────────→│
     │←─── VOTE_COMMIT ───│                      │
     │←─── VOTE_COMMIT ─────────────────────────│
     │──── COMMIT ────────→│                      │
     │──── COMMIT ─────────────────────────────→│
     │←─── ACK ───────────│                      │
     │←─── ACK ────────────────────────────────│
```

## The Fundamental Problems

### 1. Blocking on Coordinator Failure
If the coordinator crashes **after** some cohorts voted COMMIT but **before** sending the decision, those cohorts are stuck holding locks indefinitely. They cannot safely commit (maybe another cohort voted ABORT) or abort (maybe the coordinator already told someone else to commit). This is why 2PC is called a **blocking protocol**.

Recovery requires either: (a) coordinator to recover and consult its log, or (b) manual operator intervention.

### 2. Single Point of Failure
The coordinator is critical. High availability requires replicating the coordinator's log (usually via Paxos/Raft), which effectively turns 2PC into 3PC or consensus-based protocols.

### 3. Performance
- Every transaction = 2 round trips + fsync on each cohort. Latency adds up across services and geographies.
- Locks held across the network round trip kill throughput in contended workloads.

### 4. CAP Sacrifice
2PC prioritizes Consistency over Availability. During a partition between coordinator and cohorts, the system **stops** rather than proceeding inconsistently.

## Three-Phase Commit (3PC)
Adds a **PreCommit** phase between Prepare and Commit, letting cohorts time out and abort independently if the coordinator vanishes. Solves blocking in fail-stop models but **not** in the presence of network partitions. Rarely used in practice.

## Where 2PC Still Lives
- **XA Transactions** (JTA in Java): `XADataSource`, `XAResource`. Used in monolithic Java EE apps with multiple DBs + JMS brokers.
- **PostgreSQL Prepared Transactions** (`PREPARE TRANSACTION`): Explicit 2PC support for external coordinators.
- **MSDTC** (Windows Distributed Transaction Coordinator): Common in .NET enterprise apps.
- **Kafka Transactions**: Internally use a 2PC-like protocol coordinated by the transaction coordinator on brokers.
- **Distributed databases** (Spanner, CockroachDB, TiDB, FoundationDB): Use 2PC internally for cross-shard transactions, but combined with Paxos/Raft for fault-tolerance of the coordinator role (so the coordinator itself never single-points-of-fails).

## 2PC vs Saga vs Outbox

| Property | 2PC | Saga | Outbox |
|----------|-----|------|--------|
| Consistency | Strong (atomic) | Eventual | Eventual |
| Availability during partition | Blocked | Continues | Continues |
| Complexity | Protocol infra | Compensating logic | Relay + idempotency |
| Latency | 2 RTTs + fsyncs | Per-step (low) | Per-step + relay |
| Cross-service microservices | Poor fit | Designed for it | Designed for it |
| Typical use | Single-org enterprise DBs, distributed DBs internally | E-commerce checkout, workflows | Reliable event publishing |

## Modern Alternatives to 2PC for Microservices
1. **Saga pattern** — compensating transactions, eventual consistency. See [saga-pattern.md](saga-pattern.md).
2. **Outbox pattern** — atomic local DB write + reliable async event. See [outbox-pattern.md](outbox-pattern.md).
3. **Event sourcing** — the log IS the database. See [cqrs-event-sourcing.md](cqrs-event-sourcing.md).
4. **Idempotent retries** — accept temporary inconsistency; eventually converge. See [idempotency.md](idempotency.md).

## Possible Interview Questions
1. "Explain Two-Phase Commit. Why isn't it used in modern microservices?"
2. "What happens if the 2PC coordinator crashes mid-protocol?"
3. "Compare 2PC and Saga for a checkout flow spanning payment and inventory."
4. "How does Spanner achieve cross-shard transactions — does it use 2PC?"
5. "When is 2PC still a reasonable choice?"
