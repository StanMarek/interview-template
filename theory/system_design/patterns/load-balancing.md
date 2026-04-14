# Load Balancing

## What It Is
Load balancing distributes incoming network traffic across multiple servers to ensure no single server bears too much demand. It acts as a "traffic cop" sitting in front of your servers, routing client requests in a way that maximizes speed, capacity utilization, and reliability.

## Why It Matters in System Design
Every system design question that serves more than a trivial number of users requires load balancing. It is the first building block of horizontal scaling.

## Types of Load Balancers

### By OSI Layer
- **L4 (Transport Layer)**: Operates on TCP/UDP. Decisions based on IP + port. Faster, no payload inspection. Think of it as routing at the network plumbing level.
- **L7 (Application Layer)**: Operates on HTTP/HTTPS. Can inspect headers, cookies, URLs. Can route `/api/*` to backend servers and `/static/*` to CDN. More intelligent but slightly more overhead.

### By Deployment
- **Hardware LB**: Physical appliance (F5, Citrix). Expensive, high throughput. Rare in cloud-native.
- **Software LB**: Nginx, HAProxy, Envoy. Flexible, cheap, can run on commodity hardware.
- **Cloud-native LB**: AWS ALB/NLB, GCP Cloud Load Balancer, Azure Load Balancer. Managed, auto-scaling.
- **DNS-based LB**: Distribute at the DNS resolution level. Coarse-grained, TTL-limited.

## Load Balancing Algorithms

| Algorithm | How It Works | Best For |
|-----------|-------------|----------|
| **Round Robin** | Requests rotate 1→2→3→1→2→3 | Homogeneous servers, stateless |
| **Weighted Round Robin** | More powerful servers get more traffic | Heterogeneous hardware |
| **Least Connections** | Route to server with fewest active connections | Long-lived connections (WebSockets) |
| **Least Response Time** | Route to fastest-responding server | Latency-sensitive applications |
| **IP Hash** | Hash client IP → consistent server | Session affinity without sticky sessions |
| **Consistent Hashing** | Minimize redistribution on server add/remove | Caching layers |
| **Random** | Pick a random server | Simple, surprisingly effective at scale |
| **Random Two Choices** | Pick 2 random, choose the less loaded one | Best tradeoff of simplicity and balance ("power of two choices") |
| **Maglev Hashing** | Google's consistent-hashing variant with fixed-size lookup table | High-throughput L4 LBs (Cilium, Katran, Google Maglev) |

## Key Concepts

### Health Checks
LBs periodically ping servers (active health check) or monitor response codes (passive). Unhealthy servers are removed from the pool until they recover.

### Sticky Sessions (Session Affinity)
Route a user to the same server for the duration of their session. Needed when servers store session state locally. **Anti-pattern** — prefer stateless servers with externalized session stores (Redis).

### SSL/TLS Termination
LB decrypts HTTPS traffic and forwards plain HTTP to backend servers. Offloads expensive crypto from app servers. Re-encryption (LB→server over TLS) is optional for internal networks.

### Connection Draining (Graceful Shutdown)
When removing a server, allow in-flight requests to complete before taking it out of the pool. Prevents dropped connections during deployments.

### Global Server Load Balancing (GSLB)
Distributes traffic across multiple data centers/regions, typically via DNS or Anycast. Used for geo-routing and disaster recovery.

### HTTP/3 and QUIC
Modern LBs (Envoy, Nginx ≥ 1.25, HAProxy ≥ 2.8, AWS CloudFront, Google Cloud LB) now support **HTTP/3 over QUIC** (UDP-based). Benefits: 0-RTT connection resumption, no head-of-line blocking, better performance on lossy mobile networks. Typical deployment: terminate HTTP/3 at the edge, speak HTTP/2 or HTTP/1.1 to origins. Global adoption crossed ~35% by late 2025.

### eBPF / XDP-Based Load Balancing
Kernel-level LBs using eBPF (Cilium, Katran, Facebook's Katran, Cloudflare Unimog) process packets in the Linux kernel before they reach userspace. Massively higher throughput (millions of PPS) with very low latency. **Cilium** replaces `kube-proxy` in Kubernetes and supports Maglev consistent hashing + DSR (Direct Server Return) out of the box.

## Common Architectures

```
Client → DNS → GSLB → Regional LB (L7) → App Servers
                                       → API Servers
                                       → WebSocket Servers (via L4 LB)
```

Multiple layers of LBs are common: DNS-level for geo routing, L7 for application routing, L4 for raw TCP services.

## Redundancy of the Load Balancer Itself
A single LB is a single point of failure (SPOF). Solutions:
- **Active-Passive**: Two LBs, one hot standby. Failover via virtual IP (VIP) and heartbeat.
- **Active-Active**: Both handle traffic. More capacity, more complex.
- **Anycast**: Multiple LBs advertise the same IP via BGP. Network routes to the nearest one.

## Trade-offs
- L4 is faster but dumber; L7 is smarter but adds latency
- Sticky sessions simplify state but hurt load distribution and failover
- SSL termination at LB adds a SPOF for encryption but simplifies cert management
- More LB layers = more reliability but more latency hops

## Possible Interview Questions
1. "How would you avoid a single point of failure with your load balancer?"
2. "When would you use L4 vs L7 load balancing?"
3. "A server is receiving 3x the traffic of others. What could cause this and how do you fix it?"
4. "How do you handle WebSocket connections with load balancing?"
5. "Explain the tradeoffs of sticky sessions."
6. "How does health checking work and what happens during a rolling deployment?"
7. "If you have servers in 3 regions, how do you route users to the closest one?"
