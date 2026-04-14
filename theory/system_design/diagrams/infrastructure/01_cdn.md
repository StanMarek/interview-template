# CDN (Content Delivery Network) -- Architecture Design

## Requirements
### Functional
- Serve static and dynamic content from geographically distributed edge locations
- Support cache invalidation / purge by URL, tag, or prefix
- Terminate TLS at the edge with automatic certificate provisioning
- Route users to the nearest healthy PoP via GeoDNS or Anycast
- Support custom caching rules (TTL overrides, vary headers, stale-while-revalidate)
- Origin shield (mid-tier cache) to collapse origin fetches from multiple PoPs

### Non-Functional
- Sub-50ms TTFB for cached content from any continent
- 99.99% availability at the edge (no single PoP is a SPOF)
- Cache hit ratio > 95% for static assets
- Purge propagation to all PoPs within 5 seconds
- Graceful degradation: serve stale content when origin is down

## Scale Estimates
- 200+ Points of Presence across 50+ countries
- 10M+ requests/sec aggregate across all PoPs
- 500 TB/day of bandwidth served
- Cache storage: 50-100 TB per PoP (SSD + RAM tiers)
- Origin: 50K-100K req/sec peak (after cache shielding)

## Architecture Decisions

### Control Plane vs Data Plane Separation
The control plane (config manager, DNS router, cert manager, purge API) is centralized and manages the fleet. The data plane (PoPs, shield caches) handles actual traffic. This means a control plane outage does not stop content serving -- PoPs continue with their last-known-good config. This is critical because you can tolerate stale config but never tolerate dropped requests.

### Origin Shield (Mid-Tier Cache)
Without a shield, every PoP cache miss goes directly to origin. With N PoPs, a popular new asset causes N origin fetches. The origin shield collapses these into a single fetch. Trade-off: adds one extra hop of latency for shield-miss requests. Worth it because it reduces origin load by 10-50x and makes origin scaling far more predictable.

### Push vs Pull Purge Propagation
Purge uses a push model (control plane fans out invalidation to all PoPs) rather than pull (PoPs polling). Push gives sub-second propagation latency. The trade-off is complexity in reliable delivery -- you need an acknowledgment protocol and retry logic. Some CDNs use a gossip protocol between PoPs as a fallback.

### GeoDNS vs Anycast
GeoDNS gives you explicit control over routing (you can drain a PoP by removing its DNS records) but relies on the accuracy of GeoIP databases. Anycast is simpler and handles DDoS better (traffic naturally distributes) but makes draining a PoP harder. Most production CDNs use a hybrid: Anycast for IP routing with DNS-level overrides for traffic engineering.

## Component Breakdown

- **Config Manager**: Stores caching rules, origin configs, and edge logic. Pushes config to PoPs via an eventually-consistent replication layer. Config changes go through a staged rollout (canary PoP -> region -> global).
- **GeoDNS Router**: Resolves DNS queries based on client IP geolocation. Integrates with health checks to remove unhealthy PoPs from rotation. TTL kept low (30-60s) to enable fast failover.
- **TLS / Certificate Manager**: Provisions and renews certificates via ACME (Let's Encrypt) or custom CA. Distributes certs to all PoPs. Supports SNI for multi-tenant edge termination.
- **Purge / Invalidation API**: Accepts purge requests (by URL, surrogate key, or wildcard). Fans out to all PoPs with at-least-once delivery. Returns purge status with per-PoP acknowledgment.
- **PoP (Edge Cache)**: Multi-tier local cache (RAM for hot objects, SSD for warm). Runs edge logic (redirects, header manipulation, A/B routing). Reports metrics and access logs upstream.
- **Origin Shield**: Regional mid-tier cache that sits between PoPs and origin. Deduplicates concurrent cache-miss requests (request coalescing). Reduces origin load dramatically.
- **WAF / DDoS Protection**: Inline at the edge. Rate limiting, bot detection, IP reputation filtering. Must not add meaningful latency to legitimate requests.

## Operational Concerns
- **Deploying config changes safely**: Staged rollout -- canary one PoP, monitor error rates and latency for 5 min, then roll to region, then global. Automated rollback if error rate exceeds threshold.
- **Blast radius of a bad config push**: If you push a bad caching rule globally, you serve wrong content to everyone. The staged rollout limits blast radius. Additionally, config changes should be versioned and diffable, with one-click rollback.
- **How to roll back**: Revert to previous config version. PoPs pick up the reverted config within seconds. For cache poisoning (bad content cached), you issue a global purge of affected keys.
- **Capacity planning**: Monitor cache hit ratio per PoP. When hit ratio drops below threshold or bandwidth saturates, add more cache capacity or deploy a new PoP in that region.

## Failure Modes
- **Control plane unavailable**: PoPs continue serving with last-known config. No new config changes or purges propagate. This is acceptable for minutes to hours.
- **Single PoP failure**: GeoDNS health check detects failure, removes PoP from DNS. Users route to next-closest PoP with slightly higher latency. Automatic, no human intervention.
- **Origin failure**: Shield cache and PoPs serve stale content (stale-while-revalidate). If stale TTL expires, users see errors. Mitigation: origin health checks trigger automatic failover to standby origin.
- **Cache poisoning**: Bad response gets cached and served to many users. Mitigation: validate origin responses (status codes, content-type), support instant purge, and use surrogate keys for targeted invalidation.

## Key Trade-offs
- **Consistency vs Availability**: CDNs are AP systems. You accept that purge is eventually consistent across PoPs. Users in different regions may briefly see different content versions.
- **Cache hit ratio vs Freshness**: Higher TTLs improve hit ratio but serve staler content. Use stale-while-revalidate to get the best of both worlds for most use cases.
- **Cost vs Latency**: More PoPs reduce latency but increase operational cost and config propagation complexity. Start with 10-20 PoPs in major metro areas, expand based on traffic patterns.
- **Edge compute vs Simplicity**: Running custom logic at the edge (like Cloudflare Workers) is powerful but adds debugging complexity and a new failure domain.

## What Fails First
Cache storage at popular PoPs. When a viral event drives massive traffic to assets not yet cached, the thundering-herd problem hits both the edge cache and origin simultaneously. Request coalescing at the shield is the key defense. Without it, origin collapses under load.

## v1 vs v2
### v1 (Minimum Viable CDN)
- 5-10 PoPs in major regions
- Simple GeoDNS routing
- Static file caching with configurable TTL
- Basic purge by exact URL
- TLS termination with pre-provisioned certs
- Origin health checks with DNS failover

### v2 (Production Grade)
- 100+ PoPs with Anycast + GeoDNS hybrid
- Origin shield with request coalescing
- Surrogate key-based purge and wildcard purge
- Edge compute for custom logic (redirects, A/B, auth)
- Automatic certificate provisioning via ACME
- Real-time analytics dashboard per PoP
- WAF and DDoS mitigation at the edge
- Staged config rollout with automatic rollback
- Stale-while-revalidate and stale-if-error support
