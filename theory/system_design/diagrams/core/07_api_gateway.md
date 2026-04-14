# API Gateway & Request Routing Layer -- Architecture Design

## Requirements

### Functional
- Single entry point for all client requests (web, mobile, third-party, internal)
- Path-based and header-based routing to backend microservices
- Authentication and authorization (JWT validation, API key verification)
- Rate limiting per client/tier/endpoint
- Request/response transformation (header injection, body rewriting, protocol translation)
- Circuit breaking to protect against cascading failures
- API versioning support (path-based `/v1/`, `/v2/` or header-based)
- Request aggregation (BFF pattern -- combine multiple backend calls into one client response)

### Non-Functional
- **Latency overhead:** < 10ms added per request (p99)
- **Throughput:** 500K requests/sec
- **Availability:** 99.99% -- gateway down = entire platform down
- **Zero-downtime deployments:** Route config changes without restarting the gateway

## Scale Estimates
- **Total API requests:** 500K/sec peak
- **Unique routes:** ~500 (path patterns to backend services)
- **Backend services:** 50-100 microservices
- **Concurrent connections:** 1M+
- **Request payload:** Average 2 KB, max 10 MB

## Architecture Decisions

### Reverse Proxy vs Application-Level Gateway
Two fundamentally different approaches:

**Reverse proxy (Nginx/Envoy):** Operates at L4/L7. Extremely fast (handles 100K+ req/sec per instance). Limited programmability -- config-driven, not code-driven.

**Application gateway (Kong/Spring Cloud Gateway):** Operates at L7. Fully programmable middleware chain. Slower (10-50K req/sec per instance).

**Decision: Envoy as the data plane + custom control plane.** Envoy handles the raw proxy performance. Our control plane dynamically configures Envoy's routes, rate limits, and auth policies via xDS API. This gives us Envoy's performance with custom business logic.

**Alternative considered:** Build from scratch on Netty/Vert.x. Maximum control, but you're maintaining a proxy -- not a competitive advantage. Use battle-tested proxies and customize through their extension points.

### Middleware Pipeline Architecture
Requests flow through an ordered chain of middleware:

```
Request -> TLS Termination -> CORS -> Auth -> Rate Limit -> Request Validation
  -> Route Match -> Circuit Breaker -> Load Balance -> Backend Call
  -> Response Transform -> Logging -> Response
```

**Why a pipeline?** Each concern is isolated and composable. You can add/remove/reorder middleware without touching others. Each middleware can short-circuit the pipeline (rate limiter returns 429 without calling downstream).

**Key insight:** The ORDER matters. Auth before rate limiting means unauthenticated requests still consume rate-limit capacity. Rate limiting before auth means authenticated users compete with anonymous spam. **Decision:** Basic rate limiting (IP-based) before auth, fine-grained rate limiting (per-user) after auth.

### Service Discovery Integration
The gateway needs to know where backend services live. Two approaches:

1. **Static config:** Route `/api/users/*` to `user-service.internal:8080`. Simple but requires gateway redeploy to change backends
2. **Dynamic discovery:** Gateway queries Consul/etcd for healthy instances of `user-service`. Backends register/deregister themselves

**Decision:** Dynamic discovery via Consul. Backend services register with health checks. Gateway watches for changes and updates its routing table in real-time. Adding a new service instance takes effect in seconds, not deployments.

### Circuit Breaker Pattern
When a backend service is failing, the gateway should stop sending requests to it. Otherwise, the failing service gets overwhelmed with retries, and timeouts cascade to the client.

Circuit breaker states:
- **Closed:** Normal operation. Track error rate
- **Open:** Error rate exceeds threshold (e.g., 50% failures in 10 seconds). Return 503 immediately without calling backend
- **Half-open:** After a cooldown period, send a probe request. If it succeeds, close the circuit

**Per-service, not global:** Each backend service has its own circuit breaker. User service being down shouldn't trigger the circuit breaker for product service.

**Fallback responses:** When the circuit is open, return a degraded response rather than an error when possible (e.g., return cached product data instead of a 503).

### Request Authentication Strategy
The gateway validates auth tokens but doesn't issue them (that's the auth service's job):

1. Extract JWT from `Authorization: Bearer <token>` header
2. Validate signature using the auth service's public key (cached locally, rotated periodically)
3. Check token expiration, issuer, audience
4. Extract claims (user_id, roles, permissions) and inject as headers for downstream services

**Why at the gateway?** Downstream services don't need to implement auth logic. They trust the gateway's headers. This centralizes auth policy changes.

**Trade-off:** The gateway becomes a single point of auth failure. If the token validation logic has a bug, all services are affected. Mitigate with thorough testing and canary deployments of gateway changes.

### Canary Routing and Traffic Splitting
The gateway is the natural place to implement canary deployments:
- Route 5% of traffic to the new version of a service, 95% to the stable version
- Monitor error rates for the canary. Auto-rollback if error rate exceeds threshold
- Gradually increase traffic (5% -> 25% -> 50% -> 100%)

Implemented via weighted routing rules in the route config. No code changes needed -- just update the route weight.

## Component Breakdown

| Component | Role |
|---|---|
| **Client Applications** | Web, mobile, third-party, internal services -- all enter through the gateway |
| **Auth / JWT Validation** | Validates bearer tokens, extracts claims, rejects unauthenticated requests |
| **Rate Limiter** | IP-based (pre-auth) and user-based (post-auth) rate limiting via Redis counters |
| **Request Validation** | Schema validation for request bodies, required header checks |
| **CORS / Security Headers** | Handles preflight requests, injects security headers (CSP, HSTS, X-Frame-Options) |
| **Routing Engine** | Matches request path + method + headers to a backend service route definition |
| **Circuit Breaker + Load Balancing** | Tracks per-service health. Distributes requests across healthy instances (round-robin or least-connections) |
| **Response Transform** | Strips internal headers, adds CORS headers, rewrites response bodies for API versioning |
| **Backend Microservices** | The actual business logic services (user, order, product, payment, etc.) |
| **Service Registry (Consul)** | Dynamic directory of healthy service instances. Gateway watches for changes |
| **Route Config** | Database or ConfigMap storing route definitions, updated without gateway restart |
| **Access Logs + Tracing** | Every request logged with timing, status code, service. Distributed tracing IDs propagated |

## Key Trade-offs

- **Centralization vs resilience:** A single gateway is a single point of failure. Multiple gateway instances behind a load balancer mitigate this, but now the LB is the SPOF. At some point, you accept that DNS/BGP is the ultimate SPOF and move on
- **Thick vs thin gateway:** A thick gateway (auth, rate limit, transform, aggregation, caching) reduces backend complexity but creates a monolithic bottleneck. A thin gateway (just routing + auth) pushes complexity to services but scales better. **Start thin, add middleware only when the alternative (implementing it in every service) is worse**
- **Latency overhead:** Every middleware adds latency. A 6-middleware pipeline where each takes 1ms adds 6ms per request. Profile ruthlessly and parallelize where possible (auth + rate limit can run concurrently since neither depends on the other)
- **Gateway vs service mesh:** An API gateway handles north-south traffic (client to service). A service mesh (Istio/Linkerd) handles east-west traffic (service to service). They complement each other; the gateway is NOT a replacement for a service mesh

## What Fails First

**The gateway itself becomes a CPU bottleneck** under extreme load. TLS termination is CPU-intensive. JWT validation requires cryptographic operations. At 500K req/sec, you need careful profiling:
- Use hardware TLS acceleration or terminate TLS at the load balancer
- Cache JWT validation results (same token = same user for its TTL)
- Minimize response body parsing (don't transform what you don't need to)

**Mitigation:** Horizontal scaling. Gateway instances are stateless (all state is in Redis/Consul). Add instances behind an L4 load balancer.

**Secondary risk:** Route configuration errors. A bad route config can black-hole traffic or route it to the wrong service. Mitigation: config validation pipeline, canary testing of route changes, instant rollback capability.

## v1 vs v2

### v1 (Ship in 1 week)
- Nginx as reverse proxy with static config
- JWT validation via Lua middleware (OpenResty)
- Simple path-based routing to 5-10 services
- No circuit breaking (rely on timeouts)
- No dynamic service discovery (hardcoded upstreams)
- Access logging to files, parsed by ELK

### v2 (Production-grade)
- Envoy with xDS-based dynamic configuration
- Full middleware pipeline: auth, rate limit, validation, CORS, circuit breaker
- Dynamic service discovery via Consul with health checks
- Canary routing with automatic rollback
- Request aggregation / BFF endpoints for mobile
- Distributed tracing (OpenTelemetry) propagation
- Per-service circuit breakers with fallback responses
- Admin dashboard for route management and traffic monitoring
- WebSocket and gRPC proxying support
- Geographic routing for multi-region deployment
