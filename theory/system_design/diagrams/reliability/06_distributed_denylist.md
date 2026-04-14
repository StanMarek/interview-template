# Distributed Denylist / Blocklist Service -- Architecture Design

## Requirements

### Functional
- Check if an entity (IP, email, phone, user ID, domain, token) is on the denylist -- this is the hot path
- Add/remove entries to the denylist with immediate effect
- Support both exact matches and pattern-based rules (CIDR ranges, regex patterns, wildcard domains)
- Time-based entries (auto-expire after N hours/days)
- Bulk import/export of denylist entries
- Categorized entries (spam, fraud, abuse, compliance, sanctions)
- Audit trail for all denylist changes

### Non-Functional
- **Latency:** p99 < 1ms for denylist checks (this is inline on every request)
- **Availability:** 99.999% -- if the denylist is down, either all requests pass (unsafe) or all requests are blocked (unusable)
- **Propagation:** New denylist entries must be effective globally within 5 seconds
- **Scale:** Handle 1M+ checks per second per edge node
- **False positives:** < 0.001% (one in 100,000 checks)

## Scale Estimates
- 10M checks/second globally across all edge nodes
- 100M entries in the denylist (IPs, emails, domains, etc.)
- 1000 updates/second to the denylist
- 50 edge nodes across 10 regions
- Bloom filter size: ~150 MB for 100M entries at 0.001% FPR

## Architecture Decisions

### Decision 1: Two-Tier Check -- Bloom Filter (Fast) + Redis (Accurate)

Every edge node has an in-process Bloom filter loaded in memory. The check path is: (1) check Bloom filter locally (0.01ms), (2) if Bloom says "not in set," the entity is allowed (no network call needed), (3) if Bloom says "maybe in set," verify against Redis (0.5ms round trip).

**Why this architecture:** The denylist check is on the critical path of EVERY request. If it adds 5ms, that's 5ms added to every API call. The Bloom filter is the key insight: it's a probabilistic data structure that can definitively say "not in the set" with zero false negatives. This means 99.9%+ of legitimate requests (which are NOT on the denylist) are resolved in microseconds without any network call.

**Why not just use Redis for everything:** Redis can handle 100K ops/second per node. With 10M checks/second globally and 50 edge nodes, that's 200K checks/second per edge. Redis could handle this, but it adds network latency (~0.5ms) to every request, including the 99.9% that are not on the denylist. The Bloom filter eliminates this unnecessary latency for the common case.

**Trade-off:** Bloom filters have false positives (they can say "maybe in set" when the element is NOT in the set). At 0.001% FPR, this means 1 in 100,000 legitimate requests gets an extra Redis lookup. This is acceptable -- it's a slight performance degradation, not a correctness issue, because Redis provides the definitive answer.

### Decision 2: Push-Based Propagation with Versioned Snapshots

When the denylist changes, a new Bloom filter is built and pushed to all edge nodes. The push uses versioned snapshots -- each Bloom filter has a version number. Edge nodes compare their version with the latest available and pull the delta or full snapshot.

**Why push-based:** Pull-based polling introduces propagation delay equal to the polling interval. If an IP needs to be blocked NOW (active attack), waiting for the next poll cycle (even 5 seconds) is too long. Push-based propagation with a Kafka-backed event bus ensures sub-second delivery to all edge nodes.

**Why versioned snapshots:** The Bloom filter is rebuilt from scratch on each update (you can't delete from a standard Bloom filter). Versioned snapshots stored in object storage (S3) provide: (a) a reliable way for new edge nodes to bootstrap, (b) rollback capability if a bad denylist is pushed, (c) audit trail of denylist state over time.

**Trade-off:** Rebuilding the entire Bloom filter on every update is expensive for large denylists (100M entries). We batch updates and rebuild every 1-5 seconds. For urgent blocks, the edge node's Redis check is updated immediately while the Bloom filter rebuild catches up.

### Decision 3: Fail-Open vs. Fail-Closed -- Configurable Per Category

When the denylist service is unavailable, the system must decide: allow all requests (fail-open) or block all requests (fail-closed). This is configurable per category: sanctions/compliance entries fail-closed (legal obligation), rate-limiting entries fail-open (prefer availability).

**Why this matters:** This is the defining trade-off of a denylist service. Fail-open means an attacker gets through during outages. Fail-closed means legitimate users are blocked during outages. There is no universally correct answer -- it depends on the business context.

**Senior-level insight:** The in-process Bloom filter makes this trade-off less painful. Even if Redis and the control plane are completely down, the edge node's local Bloom filter (loaded at startup) continues to work. The denylist is "stale" (missing recent additions) but not "absent." This gives us effective fail-open-with-stale-data, which is much better than pure fail-open.

### Decision 4: Separate Write Path from Read Path (CQRS)

Denylist modifications (add/remove) go through the control plane (Admin API -> DB -> Kafka -> Redis + Bloom). Denylist checks go through the data plane (Edge Bloom filter + Redis). These paths share no infrastructure.

**Why CQRS:** The read path (10M checks/second) and write path (1000 updates/second) have a 10,000:1 ratio. Optimizing for reads (in-process Bloom filter, replicated Redis) would be suboptimal for writes (need strong consistency for audit). CQRS lets us optimize each path independently.

## Consistency Model

**Eventual consistency with bounded staleness.** When an entry is added to the denylist, there's a propagation window during which the entry is not yet effective at all edge nodes. This window has two phases:

1. **Redis update (< 1 second):** The Kafka consumer updates Redis. Any Bloom-filter "maybe" checks that hit Redis will see the new entry immediately.
2. **Bloom filter push (< 5 seconds):** The new Bloom filter is built and pushed to edge nodes. After this, even requests that don't hit Redis (Bloom says "not in set") will see the new entry.

**Why eventual consistency is acceptable:** The denylist is a best-effort defense mechanism, not a transaction system. A 1-5 second delay in blocking a malicious IP is acceptable because: (a) the IP was already malicious for hours/days before being added to the denylist, and (b) other defense layers (rate limiting, WAF, fraud detection) provide additional protection during the propagation window.

**Why strong consistency would be harmful:** Making every check hit a centralized, strongly-consistent store would add unacceptable latency (5-50ms) to every request and create a single point of failure. The availability and latency requirements (99.999%, < 1ms) are impossible to meet with strong consistency at this scale.

## Failure Modes

### Redis cluster failure
Edge nodes continue to use their local Bloom filter. The Bloom filter has no false negatives, so all blocked entries remain blocked. The only impact is false positives: entries that the Bloom filter says "maybe" but are actually NOT on the denylist would normally be cleared by Redis. Without Redis, these requests may be incorrectly blocked. At 0.001% FPR, this affects 1 in 100,000 requests.

### Kafka (event bus) failure
New denylist entries are not propagated. Edge nodes continue with their current Bloom filter and Redis state. The denylist_db is the source of truth, so no entries are lost. When Kafka recovers, pending events are delivered and propagation resumes.

### Edge node failure
The load balancer routes traffic to other edge nodes. The remaining nodes have the same Bloom filter and can handle the additional load. This is stateless horizontal scaling.

### Bad denylist push (false positive spike)
The propagation monitor detects an abnormal spike in block rate. Automatic rollback to the previous versioned Bloom filter snapshot. The rollback completes in seconds because it's just loading a pre-built snapshot.

### Bloom filter becomes stale (edge node can't update)
The edge node continues with its stale Bloom filter. The false negative rate slowly increases (new denylist entries are missed). Monitoring tracks the Bloom filter version age per edge node and alerts if a node is more than 60 seconds stale.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **API Gateway** | Every request passes through inline denylist check |
| **Edge Nodes** | Distributed check points with local Bloom filter and cache |
| **In-Process Bloom Filter** | Probabilistic fast-path check (no network call for 99.9% of requests) |
| **Admin API** | CRUD operations for denylist entries, rule management |
| **Denylist DB** | Source of truth for all denylist entries (PostgreSQL) |
| **Rule Engine** | Pattern matching for CIDR ranges, regex, wildcards |
| **Event Bus (Kafka)** | Distributes denylist changes to all consumers |
| **Redis Cluster** | Definitive check for Bloom filter "maybe" results |
| **Bloom Filter Builder** | Rebuilds Bloom filter on denylist changes |
| **Bloom Filter Distributor** | Pushes new Bloom filter to all edge nodes |
| **Versioned Snapshot** | Object storage for Bloom filter versions (bootstrap/rollback) |
| **Audit Log** | Immutable record of all denylist changes with reason codes |
| **Block Rate Analytics** | Tracks block rates per category, per region |
| **False Positive Monitor** | Detects abnormal false positive rates |
| **Propagation Latency Monitor** | Tracks time from denylist change to global effectiveness |

## Key Trade-offs

### Latency vs. Accuracy
The Bloom filter trades a tiny false positive rate (0.001%) for sub-millisecond checks without network calls. This is the right trade-off because the cost of blocking one legitimate request in 100,000 is negligible compared to adding 0.5ms to every request.

### Propagation Speed vs. Consistency
We propagate changes in 1-5 seconds rather than instantly. Instant propagation would require synchronous global coordination, which is impossible at < 1ms check latency. The bounded staleness (< 5s) is acceptable for security use cases.

### Storage vs. False Positive Rate
A Bloom filter for 100M entries at 0.001% FPR requires ~150 MB. At 1% FPR, it would be ~120 MB. The 30 MB difference is negligible for edge nodes with gigabytes of RAM, so we optimize for lower FPR.

## What Fails First

**The Bloom filter rebuild becomes the bottleneck.** With 1000 updates/second and 100M entries, rebuilding the entire Bloom filter on every update is too expensive (~10 seconds per rebuild). The rebuild queue falls behind, and propagation latency grows.

**Mitigation:** 
1. Batch updates and rebuild every 1-5 seconds (not on every update).
2. Use a counting Bloom filter or cuckoo filter that supports deletions.
3. For urgent blocks, update Redis immediately (< 1 second) while the Bloom filter rebuild catches up.

## v1 vs v2

### v1 (Ship first)
- Exact match only (no patterns/CIDR)
- Single Redis instance per region (no Bloom filter)
- Synchronous check against Redis on every request
- Manual add/remove via admin API
- Single category (blocked/not blocked)
- Basic audit logging

### v2 (Scale and harden)
- In-process Bloom filter at every edge node
- Push-based propagation via Kafka with versioned snapshots
- Pattern matching (CIDR ranges, regex, wildcard domains)
- Time-based entries with auto-expiry
- Categorized entries with per-category fail-open/fail-closed
- False positive monitoring and automatic rollback
- Integration with threat intelligence feeds (auto-import)
- Appeal workflow for false positive remediation
- Rate-based auto-blocking (adaptive denylist)
