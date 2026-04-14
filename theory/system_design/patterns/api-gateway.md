# API Gateway

## What It Is
An API Gateway is a single entry point for all client requests in a microservices architecture. It sits between clients and backend services, handling cross-cutting concerns like authentication, rate limiting, routing, protocol translation, and response aggregation.

## Why It Matters
Without a gateway, clients must know about and communicate with every microservice directly — a coupling nightmare. The gateway abstracts the internal architecture.

## Core Responsibilities

| Responsibility | Description |
|---------------|-------------|
| **Request Routing** | Route `/users/*` to User Service, `/orders/*` to Order Service |
| **Authentication & Authorization** | Validate JWT/OAuth tokens before requests reach services |
| **Rate Limiting** | Prevent abuse per client/API key |
| **Load Balancing** | Distribute requests across service instances |
| **Protocol Translation** | REST ↔ gRPC, HTTP ↔ WebSocket |
| **Response Aggregation** | Combine multiple service responses into one client response |
| **Caching** | Cache common responses at the edge |
| **Request/Response Transformation** | Modify headers, body, query params |
| **Circuit Breaking** | Stop routing to failing services |
| **Logging & Monitoring** | Centralized access logs, tracing, metrics |
| **SSL Termination** | Handle HTTPS, forward HTTP internally |
| **API Versioning** | Route `/v1/*` and `/v2/*` to different service versions |

## API Gateway Patterns

### Backend for Frontend (BFF)
One gateway per client type (web, mobile, IoT). Each BFF aggregates and tailors responses for its client.
- **Pros**: Optimized per client, independent deployment
- **Cons**: Code duplication, more services to maintain

### Edge Gateway + Internal Gateway
Edge gateway handles public-facing concerns (auth, rate limiting). Internal gateway handles service-to-service routing.

### Gateway Offloading
Move cross-cutting concerns from services to the gateway. Services become simpler but the gateway becomes critical.

## API Gateway vs Load Balancer vs Reverse Proxy

| Feature | API Gateway | Load Balancer | Reverse Proxy |
|---------|-------------|---------------|---------------|
| Routing intelligence | URL/header-based, complex rules | Round-robin, least connections | Basic URL forwarding |
| Auth/rate limiting | Built-in | No | Sometimes |
| Protocol translation | Yes | No | Minimal |
| Response aggregation | Yes | No | No |
| Layer | L7 (application) | L4 or L7 | L7 |

In practice, these often overlap. An Nginx reverse proxy can act as a basic API gateway.

## Trade-offs
- **Single point of failure**: Must be highly available (clustered, multi-region)
- **Latency**: Extra network hop on every request
- **Complexity**: One more thing to configure, deploy, monitor
- **Bottleneck risk**: All traffic flows through it; must scale horizontally
- **Coupling**: If gateway has too much logic, it becomes a monolith itself

## Real-World Implementations
| Tool | Type | Notes |
|------|------|-------|
| Kong | Open-source | Plugin-based, Nginx/OpenResty under the hood |
| AWS API Gateway | Managed | REST, HTTP (cheaper, lower-latency), WebSocket APIs |
| Azure API Management | Managed | Policy-based, developer portal |
| GCP API Gateway / Apigee | Managed | OpenAPI/Swagger support |
| Envoy | Open-source proxy | Used as gateway in service meshes (Istio) and standalone (Gloo, Emissary) |
| Nginx / OpenResty | Reverse proxy | Can act as a simple API gateway |
| Traefik | Cloud-native | Auto-discovery of services, K8s-native |
| **Kubernetes Gateway API** | Spec (GA 2023) | Successor to Ingress; richer L4/L7 routing, implemented by Istio, Envoy Gateway, Contour, Kong, etc. |

### Gateway vs Ingress Controller vs Service Mesh
- **Ingress / Gateway API**: North-south traffic (external → cluster). Kubernetes Gateway API is the modern standard, superseding the older Ingress resource.
- **API Gateway**: Business-logic-aware routing, auth, rate limiting, API product lifecycle (versioning, quotas, billing).
- **Service Mesh**: East-west traffic (service-to-service inside the cluster). Overlap exists — Envoy can play all three roles.

## Possible Interview Questions
1. "Why not have the client call microservices directly?"
2. "How would you prevent the API gateway from becoming a bottleneck?"
3. "Explain the BFF (Backend for Frontend) pattern."
4. "How does the API gateway handle authentication?"
5. "What happens if the API gateway goes down?"
6. "How do you version your APIs with a gateway?"
