# Service Discovery

## What It Is
In a microservices architecture with dynamically scaling instances, service discovery is the mechanism by which services find and communicate with each other. Instances come and go (autoscaling, deploys, failures), so hardcoded IP addresses don't work.

## Patterns

### Client-Side Discovery
The client queries a service registry to get available instances, then load-balances among them.
- **Flow**: Client → Registry (get instances) → Client picks one → Direct call to instance
- **Pros**: Client can implement smart LB (least connections, latency-based)
- **Cons**: Coupling — every client must implement discovery logic
- **Examples**: Netflix Eureka + Ribbon, Consul + custom client. Note: Netflix Ribbon has been in maintenance since ~2018; Spring Cloud LoadBalancer replaced it (Spring Cloud 2020+).

### Server-Side Discovery
The client calls a load balancer/router, which queries the registry and routes the request.
- **Flow**: Client → Load Balancer → Registry lookup → Route to instance
- **Pros**: Clients are simple (just call one endpoint); discovery logic centralized
- **Cons**: LB is an extra hop and potential bottleneck/SPOF
- **Examples**: AWS ALB, Kubernetes Services, Nginx + Consul

### DNS-Based Discovery
Services register DNS records. Clients resolve the service name to an IP.
- **Pros**: Universal, works with any language/framework
- **Cons**: DNS TTL caching causes staleness; limited to IP:port (no metadata)
- **Examples**: Consul DNS, Kubernetes CoreDNS, AWS Cloud Map
- **SRV records**: DNS-based discovery uses **SRV records** (`_service._proto.name → priority weight port target`). Kubernetes headless Services publish SRV records for Pod endpoints; Consul publishes them for registered services.

### Service Mesh
Sidecar proxies handle discovery, routing, retries, and mTLS transparently. Application code is unaware.
- **Examples**: Istio (Envoy), Linkerd

## Service Registry
A database of available service instances with their network locations and health status.
- **Self-registration**: Service registers itself on startup, deregisters on shutdown. Heartbeats maintain registration.
- **Third-party registration**: A separate registrar watches for new instances (e.g., Kubernetes controller) and registers them.

### Registry Implementations
| Tool | Type | Notes |
|------|------|-------|
| etcd | CP (Raft consensus) | Key-value store, Kubernetes backing store |
| Consul | CP (Raft) | Service mesh + DNS + KV + health checks |
| ZooKeeper | CP (ZAB) | Mature, complex; usage declining (Kafka 4.0 removed it in 2025 in favor of KRaft) |
| Eureka | AP | Netflix; in maintenance mode — Spring Cloud dropped Netflix integrations in 2023, Eureka 2.x was abandoned. Avoid for greenfield projects |
| Nacos | AP / CP (configurable) | Alibaba; service discovery + config; popular in Spring Cloud Alibaba |
| Kubernetes | Built-in | Services + Endpoints/EndpointSlices + CoreDNS — the default in K8s-native architectures |

In modern (2025-2026) cloud-native stacks, **Kubernetes Services + CoreDNS** (often paired with a service mesh like Istio/Linkerd/Cilium) is the dominant pattern. Standalone registries like Eureka/Consul are mostly seen in legacy or hybrid VM/Kubernetes environments.

## Possible Interview Questions
1. "How do microservices find each other?"
2. "Compare client-side vs server-side service discovery."
3. "What happens if the service registry goes down?"
4. "How does Kubernetes handle service discovery?"
