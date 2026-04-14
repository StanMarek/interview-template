# URL Shortener -- Architecture Design

## Requirements

### Functional
- Given a long URL, generate a unique short alias (e.g., `sho.rt/a1B2c3`)
- When a user visits the short URL, redirect (301/302) to the original
- Optional: custom aliases, expiration, click analytics
- Optional: user accounts to manage their links

### Non-Functional
- **Read-heavy:** redirect reads dominate writes by ~100:1
- **Low latency:** redirect must complete in < 50ms (p99)
- **High availability:** 99.99% -- a broken short link erodes trust permanently
- **Consistency:** eventual consistency is acceptable for analytics; strong consistency required for alias uniqueness

## Scale Estimates
- **DAU:** 100M (mostly readers)
- **Writes:** ~1M new URLs/day (~12 writes/sec avg, ~120/sec peak)
- **Reads:** ~10B redirects/day (~115K reads/sec avg, ~500K/sec peak)
- **Storage:** Each mapping ~500 bytes. 1M/day * 365 * 5 years = ~1.8B records = ~900 GB
- **Bandwidth:** Redirect is a 301 + Location header -- negligible per-request, but at 500K/sec the connection overhead matters

## Architecture Decisions

### Base62 Encoding Over Hashing
Using base62 (a-z, A-Z, 0-9) on a unique integer ID is superior to hashing the URL because:
- **No collisions by construction** -- each integer maps to exactly one base62 string
- MD5/SHA-based approaches require collision handling (check-and-retry), which adds a DB round trip on every write
- 7 characters of base62 = 62^7 = ~3.5 trillion unique URLs -- more than enough
- **Trade-off:** Requires a centralized ID generator, but this is a solved problem (see below)

### Range-Based ID Generation (etcd / ZooKeeper)
Instead of a single auto-increment counter (single point of failure), each application server leases a range of IDs (e.g., server A gets 1-1000, server B gets 1001-2000) from a coordination service:
- Servers can generate IDs locally without coordination until their range is exhausted
- If a server crashes mid-range, we lose at most one range worth of IDs -- acceptable given the 3.5T address space
- **etcd is preferred over ZooKeeper** in modern stacks (simpler API, better k8s integration, Raft-based). ZooKeeper still works but has known operational complexity and is no longer required even for Kafka (KRaft replaced ZK in Kafka 3.3+, fully default in 4.0)
- **Alternative considered:** Snowflake IDs work too but produce longer codes (64-bit vs our compact 7-char codes)

### 301 vs 302 Redirect
- **301 (Moved Permanently):** Browser caches the redirect. Reduces server load but kills analytics -- subsequent visits never hit our servers
- **302 (Found):** Browser does NOT cache. Every click hits our servers, enabling accurate analytics
- **Decision:** Default to 302 for analytics. Offer 301 as an opt-in for high-traffic links where the owner doesn't need click tracking

### Read Path: Cache-First with Redis
The redirect path is: Redis lookup -> DB fallback -> populate cache. Why Redis specifically:
- At 500K reads/sec, hitting the DB directly is impractical
- Popular URLs follow a Zipf distribution -- the top 20% of URLs get 80% of traffic
- A Redis cluster with ~50 GB can cache the hot set and absorb >95% of reads
- TTL of 24-48 hours with LRU eviction keeps memory bounded

### NoSQL for URL Mappings
DynamoDB or Cassandra over a relational DB because:
- The access pattern is pure key-value: `shortCode -> {longURL, createdAt, userId, expiresAt}`
- No joins, no transactions, no complex queries on the mapping table
- Horizontal scaling is straightforward with consistent hashing
- **Trade-off:** Lose ACID transactions, but we don't need them -- each URL mapping is independent

## Component Breakdown

| Component | Role |
|---|---|
| **Client/Browser** | Issues POST to create short URL or GET to resolve one |
| **Load Balancer** | Distributes traffic; separate pools for read vs write if needed |
| **Shorten Service** | Accepts long URL, obtains unique ID, encodes to base62, writes mapping to DB |
| **Redirect Service** | Resolves short code to long URL via cache/DB, issues HTTP redirect |
| **Redis Cache** | Hot-path cache for redirect lookups. Write-around caching (writes go to DB, reads populate cache on miss) |
| **URL Mappings DB** | Persistent store of all short->long mappings. DynamoDB or Cassandra |
| **ID Generator (ZooKeeper)** | Distributes non-overlapping ID ranges to Shorten Service instances |
| **Analytics (Kafka)** | Redirect Service emits click events asynchronously. Downstream consumers aggregate for dashboards |

## Key Trade-offs

- **Custom aliases vs simplicity:** Supporting custom aliases requires an extra uniqueness check (DB read before write), adding latency to the write path. Worth it for user value, but adds complexity
- **Analytics accuracy vs latency:** Using 302 gives perfect analytics but means every click hits your infrastructure. At extreme scale, you may need to sample or batch analytics events
- **Cache invalidation:** When a URL is updated or deleted, cache must be invalidated. Using write-around caching means there's a window (up to TTL) where stale data is served. For most use cases this is fine; for compliance-driven deletion, you need active cache invalidation
- **Global latency:** A single-region deployment means users on the other side of the world get high redirect latency. Multi-region replication of both cache and DB is the v2 solution

## What Fails First

**The ID generator is the first bottleneck.** If the coordination service (etcd/ZK) becomes unavailable, no new URLs can be created. Mitigation:
- Lease large ranges (10K-100K IDs) so servers can operate independently for hours
- Use a 3- or 5-node etcd/ZK ensemble for HA
- As a fallback, switch to UUID-based generation (longer codes but no coordination needed)

At extreme read scale, **Redis memory** becomes the constraint. The hot set grows, and cache misses cascade to the DB. Mitigation: shard Redis, use consistent hashing, and add a second-tier local cache (Caffeine) on each redirect server.

## v1 vs v2

### v1 (Ship in 2 weeks)
- Single-region deployment
- Base62 encoding with a simple auto-increment counter (PostgreSQL sequence)
- Redis cache for reads
- No custom aliases
- Basic click counting (increment counter in Redis, flush to DB periodically)
- Single DB (PostgreSQL -- it handles the write load fine at v1 scale)

### v2 (Scale to 100M DAU)
- Multi-region with geo-routing (Route 53 latency-based routing)
- ZooKeeper-based distributed ID generation
- DynamoDB Global Tables for multi-region replication
- Custom aliases with reservation system
- Full analytics pipeline (Kafka -> Flink -> ClickHouse)
- Rate limiting on the create endpoint
- URL expiration with background TTL sweeper
- Abuse detection (spam URLs, phishing) with async scanning
