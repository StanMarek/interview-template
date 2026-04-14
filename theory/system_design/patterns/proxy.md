# Proxy (Forward & Reverse)

## Forward Proxy
Sits in front of **clients**. The client sends requests to the proxy, which forwards them to the server. The server doesn't know the client's identity.

**Use cases**: Corporate internet access control, client anonymity (VPN), content filtering, caching for outbound requests.

## Reverse Proxy
Sits in front of **servers**. The client sends requests to the proxy, which forwards them to the appropriate backend server. The client doesn't know which server handled its request.

**Use cases**: Load balancing, SSL termination, caching, compression, DDoS protection, serving static content.

## Reverse Proxy vs Load Balancer
All load balancers are reverse proxies, but not all reverse proxies are load balancers. A reverse proxy can serve a single backend server (for SSL termination, caching), while a load balancer specifically distributes traffic across multiple servers.

## Sidecar Proxy (Service Mesh)
A proxy deployed alongside each service instance (same pod in Kubernetes). Handles networking concerns: mTLS, retries, circuit breaking, observability, traffic splitting, canary deploys. The application code doesn't need to implement these.

**Examples**: Envoy (Istio default), linkerd2-proxy (Rust, Linkerd), Cilium (eBPF)

```
[Service A] ↔ [Sidecar Proxy] ←→ [Sidecar Proxy] ↔ [Service B]
```

### Sidecarless / Ambient Mode (2024-2026)
Running a sidecar per pod costs CPU and memory at scale. Modern meshes are moving to **sidecarless** architectures:
- **Istio Ambient Mesh** (stable in 1.24, 2024): L4 handled by a per-node `ztunnel`, L7 by optional per-namespace `waypoint` proxies. Dramatically reduces overhead in dense clusters.
- **Cilium Service Mesh**: eBPF-based, no userspace proxy for most paths. Leverages kernel networking for L4.
- **Linkerd**: retains a sidecar but uses a lightweight Rust micro-proxy (~20-30 MB vs Envoy's ~50 MB).

## Common Implementations
| Tool | Type | Notes |
|------|------|-------|
| Nginx | Reverse proxy | Also web server, LB, cache |
| HAProxy | Reverse proxy / LB | High-performance L4/L7 |
| Envoy | Service proxy | gRPC-native, used in Istio |
| Traefik | Reverse proxy | Cloud-native auto-discovery |
| Squid | Forward proxy | Caching proxy |
| Cloudflare | Reverse proxy (CDN) | DDoS, WAF, CDN |

## Possible Interview Questions
1. "What's the difference between a forward and reverse proxy?"
2. "Where would you place a reverse proxy in your architecture and why?"
3. "What is a sidecar proxy and when would you use a service mesh?"
4. "How does a reverse proxy help with security?"
