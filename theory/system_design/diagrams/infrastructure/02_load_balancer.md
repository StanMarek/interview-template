# Load Balancing System -- Architecture Design

## Requirements
### Functional
- Distribute incoming traffic across healthy backend instances at both L4 (TCP/UDP) and L7 (HTTP/gRPC)
- Support multiple load balancing algorithms: round-robin, least-connections, weighted, consistent hashing
- Health checking of backends with configurable intervals, thresholds, and check types (TCP, HTTP, gRPC)
- TLS termination and optional mTLS for service-to-service traffic
- Path-based and header-based routing at L7
- Connection draining during backend removal (graceful shutdown)
- Rate limiting and circuit breaking per service/route

### Non-Functional
- Add less than 1ms of latency for L4, less than 5ms for L7
- Handle 1M+ concurrent connections per L4 LB instance
- Zero-downtime configuration reloads
- Active-passive or active-active failover for LB instances themselves
- No single point of failure -- LB failure must not take down all traffic

## Scale Estimates
- 500K requests/sec per L7 proxy instance
- 2M concurrent TCP connections per L4 LB
- 50-200 backend service types, each with 10-1000 replicas
- Config updates: 100-500/hour (due to autoscaling, deploys)
- Health check traffic: 10K checks/sec across all backends

## Architecture Decisions

### L4 + L7 Two-Tier Architecture
L4 handles raw TCP/UDP distribution using techniques like ECMP, DSR (Direct Server Return), or DNAT. It is extremely fast (kernel-level, no application parsing). L7 handles HTTP-aware routing, header inspection, rate limiting, and TLS termination. The two-tier design lets you scale them independently. L4 is typically hardware or kernel-bypass (DPDK/XDP), while L7 is software (Envoy, HAProxy, NGINX).

### Active-Passive vs Active-Active L4
Active-passive with VRRP (Virtual Router Redundancy Protocol) is simpler: one LB handles traffic, the standby takes over on failure via VIP migration. Active-active with ECMP splits traffic across multiple L4 nodes, giving better utilization. The trade-off: active-active requires consistent connection tracking across nodes (or stateless DSR), which is complex. Most cloud providers use active-active with Maglev-style consistent hashing.

### Push-Based Config (Control Plane to Data Plane)
The control plane (config store, health checker) pushes routing tables and backend lists to LB instances via xDS protocol (Envoy) or config reload (HAProxy). Push is preferred over pull because it gives immediate propagation when backends change. The data plane caches config locally and continues operating if the control plane goes down.

### Health Check Design
Centralized health checking avoids N*M health check traffic (N LBs checking M backends). Instead, one health check service probes all backends and distributes the results. This reduces backend load from health checks. The trade-off: the health checker itself becomes a dependency. Mitigation: run multiple health checker instances with consensus on backend status.

## Component Breakdown

- **Config Store (etcd/Consul)**: Source of truth for routing rules, backend lists, and LB configuration. Watches for changes and notifies LB instances. Must be highly available (Raft consensus).
- **Health Check Service**: Probes backends on configurable intervals. Reports status changes to config store. Supports multiple check types (TCP connect, HTTP 200, gRPC health, custom script).
- **Management API/CLI**: Operators configure routes, backends, weights, and rate limits. Validates config before applying. Supports dry-run and staged rollout.
- **Autoscaler / Capacity Manager**: Monitors LB resource usage (CPU, connections, bandwidth). Adds/removes LB instances. Coordinates with backend autoscaling to pre-warm connections.
- **DNS / VIP (Anycast)**: Entry point for traffic. Maps service names to LB VIPs. Anycast ensures geographic proximity. DNS TTL kept short for failover.
- **L4 LB (Active/Standby)**: Kernel-level TCP/UDP distribution. Uses consistent hashing for connection affinity. VRRP for failover. Connection table for stateful tracking.
- **L7 Proxy (Envoy/HAProxy)**: HTTP parsing, path-based routing, header manipulation, retries, timeouts. Runs as a horizontally scalable fleet. Hot config reload without dropping connections.
- **Rate Limiter / Circuit Breaker**: Per-route and per-client rate limiting. Circuit breaker opens when backend error rate exceeds threshold, preventing cascade failures.
- **TLS Termination**: Decrypts incoming TLS, optionally re-encrypts with mTLS to backends. Certificate rotation without restart.

## Operational Concerns
- **Deploying changes safely**: LB config changes (new routes, weight adjustments) go through the config store with validation. Envoy supports zero-downtime config reload via xDS. HAProxy uses seamless reload (new process takes over connections from old process).
- **Blast radius of a bad config**: A misconfigured route can blackhole traffic to a service. Mitigation: canary config deployment (apply to one LB instance, verify, then roll out). Automated rollback if error rate spikes.
- **Rollback**: Config store keeps version history. Rolling back is a one-command operation to revert to previous config version. Connection draining ensures in-flight requests complete.
- **Debugging connection issues**: Connection tracking tables, access logs with request IDs, and distributed tracing integration (inject trace headers at L7). Track requests from client through L4 to L7 to backend.

## Failure Modes
- **L4 LB failure**: VRRP failover promotes standby. VIP migrates within 1-3 seconds. Some in-flight connections may be reset. With ECMP, traffic rebalances across remaining L4 nodes.
- **L7 proxy failure**: L4 detects via health check, stops sending traffic to failed proxy. Remaining proxies handle increased load. If all proxies in an AZ fail, L4 routes to another AZ.
- **Control plane failure**: LB instances continue with last-known config. No new config updates propagate. Backend additions/removals are not reflected until control plane recovers. This is acceptable short-term.
- **Backend overload**: Circuit breaker opens, returning fast errors instead of overloading the backend further. Rate limiter kicks in. Retries with exponential backoff. Shed load gracefully.
- **Split brain**: If VRRP active and standby both think they are active (network partition), both advertise the VIP. Traffic goes to both. Mitigation: fencing (STONITH), or use ECMP which handles this naturally.

## Key Trade-offs
- **L4 performance vs L7 intelligence**: L4 is 100x faster but cannot inspect HTTP. L7 adds latency but enables content-based routing, retries, and observability. Most architectures need both.
- **Centralized vs Distributed health checking**: Centralized reduces probe traffic but adds a dependency. Distributed (each LB checks its own backends) is more resilient but creates N*M probes.
- **Sticky sessions vs Even distribution**: Sticky sessions (consistent hashing) improve cache locality but create hot spots. Round-robin distributes evenly but loses session affinity. Use sticky only when needed (WebSocket, stateful protocols).
- **Inline rate limiting vs Sidecar**: Inline (in the LB) is simple but couples rate limit logic to the LB. Sidecar (separate process) allows independent scaling and policy changes.

## What Fails First
Connection table exhaustion at L4. Each concurrent connection consumes memory in the connection tracking table. During a SYN flood or connection leak, the table fills up and new connections are dropped silently. Monitoring connection table utilization with alerts at 70% is critical. DSR (Direct Server Return) helps because return traffic bypasses the L4, reducing table pressure.

## v1 vs v2
### v1 (Minimum Viable LB)
- Single-tier L7 LB (HAProxy or NGINX)
- Active-passive with keepalived (VRRP)
- Round-robin and least-connections algorithms
- HTTP health checks every 10s
- Static config file, reload on change
- Basic access logging

### v2 (Production Grade)
- Two-tier L4+L7 architecture
- L4 with ECMP and consistent hashing (Maglev)
- L7 with Envoy and xDS dynamic config
- Centralized health checking with distributed fallback
- Rate limiting and circuit breaking per route
- mTLS between LB and backends
- Connection draining with configurable timeout
- Integration with service discovery (Consul, K8s endpoints)
- Real-time dashboards: connections, latency percentiles, error rates per backend
- Canary config deployment with auto-rollback
