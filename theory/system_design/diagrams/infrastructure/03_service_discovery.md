# Service Discovery for Microservices -- Architecture Design

## Requirements
### Functional
- Services register themselves on startup and deregister on shutdown
- Consumers discover healthy instances of a service by name
- Support multiple discovery interfaces: DNS, HTTP API, gRPC watch streams
- Health checking (both active probes and passive heartbeat/TTL-based)
- Key-value store for service metadata and configuration
- Multi-datacenter awareness with cross-DC replication

### Non-Functional
- Discovery latency under 10ms (cached local lookups under 1ms)
- Registry available during network partitions (AP or CP depending on design choice)
- Handle 10K+ service instances registering/deregistering during rolling deploys
- Stale reads acceptable for up to 10s; stale registrations must be reaped within 30s
- Zero-downtime registry upgrades

## Scale Estimates
- 500-2000 microservices, each with 10-500 instances = 50K-100K registered endpoints
- 100K discovery queries/sec (most served from local cache)
- Registration churn: 5K-10K register/deregister events per minute during peak deploy
- Health check traffic: 50K heartbeats/sec + 10K active probes/sec
- 3-5 registry nodes per datacenter, 3-5 datacenters

## Architecture Decisions

### CP vs AP Registry
Consul (CP with Raft) vs Eureka (AP with peer replication). CP guarantees that all registry nodes agree on the set of healthy instances -- no stale entries. AP allows reads during partition but may return stale data. For service discovery, AP is often preferred because a slightly stale endpoint list is better than no endpoint list at all. However, CP is preferred when the registry also serves as a distributed lock or leader election system.

### Client-Side vs Server-Side Discovery
Client-side discovery (sidecar proxy or SDK watches the registry and picks an endpoint) gives the client full control over load balancing algorithms and retry logic. Server-side discovery (DNS or a centralized load balancer) is simpler to adopt but limits routing intelligence. The sidecar proxy pattern (Envoy sidecar) is the modern best practice because it gives client-side intelligence without requiring SDK changes in every language.

### Heartbeat TTL vs Active Health Checking
Heartbeat (service sends periodic "I'm alive" signals, registry deregisters after TTL expiry) is pull-based and lightweight. Active health checking (registry probes service health endpoints) catches cases where a service is up but unhealthy (e.g., database connection lost). Best practice: use both. Heartbeat for liveness, active checks for readiness.

### DNS Interface vs Watch Streams
DNS is universally supported (every language/framework can do DNS lookups) but has TTL caching issues and limited metadata (just IP:port). Watch streams (gRPC or long-poll HTTP) give real-time updates with rich metadata (tags, weights, versions). Use DNS for simple cases and legacy services, watch streams for service mesh and sidecar proxies.

## Component Breakdown

- **Registry Cluster (Raft)**: 3-5 nodes using Raft consensus for leader election and log replication. Leader handles writes (register/deregister), all nodes handle reads. Stores service name -> list of (IP, port, metadata, health status).
- **Health Checker (Active Probes)**: Runs on each registry node or as a separate service. Probes service health endpoints (HTTP /health, TCP connect, gRPC health). Marks instances as unhealthy after configurable failure threshold.
- **KV Store (Config)**: Co-located with registry (like Consul KV). Stores service configuration, feature flags, rate limits. Supports watches for real-time config updates.
- **Sidecar Proxy**: Runs alongside each service instance. Subscribes to registry watch stream. Maintains local endpoint cache. Handles client-side load balancing, retries, and circuit breaking. Transparently intercepts outbound traffic.
- **Client Library (SDK)**: For services that cannot run a sidecar. Provides register(), deregister(), discover() API. Caches endpoints locally. Handles heartbeat renewal.
- **DNS Interface**: Translates service name lookups into registry queries. Returns SRV or A records. Low TTL (5-15s) for freshness. Falls back to cached results if registry is unavailable.
- **HTTP/gRPC API**: RESTful API for registration, querying, and watch streams. Watch streams use server-sent events or gRPC bidirectional streaming. Blocking queries (long poll) for efficient change notification.
- **LB / Ingress Integration**: Syncs registry data to external load balancers and ingress controllers. When a new service instance registers, it is automatically added to the LB backend pool.

## Operational Concerns
- **Deploying registry upgrades safely**: Rolling upgrade of registry nodes one at a time. Raft tolerates minority failure, so upgrading one node at a time maintains quorum. Test the new version with a canary registry in a non-production DC first.
- **Blast radius of registry outage**: If all registry nodes go down, services cannot discover new endpoints. Sidecar proxies and client libraries continue with their cached endpoint lists. New service instances cannot register. This is survivable for minutes because cached data remains valid.
- **Rollback**: Registry state is in the Raft log. Rolling back the software version does not lose state. For bad data (e.g., a misconfigured service entry), use the API to delete/update entries.
- **Debugging discovery failures**: Service cannot reach another service. Check: (1) Is the target registered? (2) Is it healthy in the registry? (3) Is the sidecar/client library cache stale? (4) Is DNS returning correct records? Registry should have audit logs for all register/deregister events.

## Failure Modes
- **Registry leader failure**: Raft elects a new leader within seconds. Writes are blocked during election. Reads continue from followers (may be slightly stale). Automatic, no intervention needed.
- **Network partition (split brain)**: With Raft, the minority partition cannot elect a leader and stops accepting writes. Services in the minority partition use cached endpoints. With AP registries (Eureka), both sides continue serving but may have divergent data.
- **Service fails to deregister (crash)**: Heartbeat TTL expires after 30-90s and the registry deregisters the instance. Active health checks detect the failure faster (within 2-3 check intervals = 10-30s).
- **Thundering herd on registry**: During a large deploy (1000 instances restarting), the registry gets flooded with register/deregister events. Mitigation: jittered startup delays, rate limiting on registration API, and batch processing in the registry.

## Key Trade-offs
- **Consistency vs Availability**: CP (Raft/Consul) gives consistent reads but blocks during partition. AP (Eureka) always returns data but may be stale. Most service discovery systems choose AP because stale-but-available beats consistent-but-unavailable for endpoint lookups.
- **Sidecar overhead vs Language agnosticism**: Sidecars add memory/CPU overhead per instance (~50MB RAM, 1-2% CPU). The alternative is per-language SDKs, which means maintaining libraries for Java, Go, Python, etc. Sidecars win in polyglot environments.
- **Local cache freshness vs Registry load**: Aggressive caching (TTL 60s) reduces registry load but means discovery of new instances is delayed. Short cache (TTL 5s) gives fast updates but increases query load. Watch streams solve this by pushing changes only when they happen.
- **Self-registration vs External registration**: Self-registration (service registers itself) is simpler but requires the service to know the registry address. External registration (a registrar agent watches for new containers and registers them) decouples services from the registry but adds another component.

## What Fails First
Health check false positives during network instability. When the network between the health checker and a service is flaky, the health checker marks healthy instances as unhealthy, removing them from the registry. Suddenly, all traffic concentrates on the remaining instances, overloading them. Mitigation: require multiple consecutive failures before marking unhealthy, use multi-path health checking (check from multiple locations), and set a minimum healthy instance threshold that prevents removing more than X% of instances at once.

## v1 vs v2
### v1 (Minimum Viable Service Discovery)
- 3-node Consul cluster with Raft
- Self-registration via HTTP API
- DNS interface for discovery (SRV records)
- TTL-based heartbeat (30s TTL, 15s renew)
- Basic HTTP health checks
- Single datacenter

### v2 (Production Grade)
- Multi-datacenter registry with WAN federation
- Sidecar proxy (Envoy) with watch stream subscription
- Active health checking with multi-path probing
- Prepared queries for advanced routing (canary, blue-green)
- Integration with Kubernetes endpoints and external LBs
- Service mesh features: mTLS, traffic splitting, circuit breaking
- Audit logging for all registration events
- Rate limiting on registration API with backpressure
- Graceful degradation: minimum healthy threshold prevents mass deregistration
