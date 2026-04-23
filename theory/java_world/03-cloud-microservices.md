# Cloud & Microservices — Senior Engineer Interview Preparation

---

## 1. Microservices Architecture

### Monolith vs Microservices

| Aspect | Monolith | Microservices |
|--------|----------|---------------|
| Deployment | Single unit | Independent services |
| Scaling | Scale entire app | Scale individual services |
| Data | Shared database | Database per service |
| Consistency | ACID transactions | Eventual consistency |
| Complexity | In the code | In the infrastructure |
| Team | Coordinated releases | Autonomous teams |
| Debugging | Stack trace | Distributed tracing |

**When NOT to use microservices**: Small team (<10 engineers), unclear domain boundaries, startup MVP, when you can't afford the operational overhead.

### Domain-Driven Design (DDD) & Bounded Contexts

Each microservice should align with a **Bounded Context** — a boundary within which a particular domain model is defined and applicable.

**Key DDD concepts for microservices**:
- **Aggregate Root**: The entry point entity that ensures consistency within a boundary. Transactions should not span aggregates.
- **Domain Events**: How bounded contexts communicate changes asynchronously.
- **Anti-Corruption Layer (ACL)**: Translates between your model and an external/legacy model to prevent leaking.
- **Context Map**: Documents relationships between bounded contexts (Shared Kernel, Customer-Supplier, Conformist, etc.)

### Communication Patterns

**Synchronous**:
- **REST/HTTP**: Simple, well-understood. Risk: temporal coupling, cascading failures.
- **gRPC**: Binary protocol (Protobuf), strongly typed, bidirectional streaming, 2-10x faster than REST depending on payload size, serialization, and scenario. Risk: harder to debug, requires code generation.
- **GraphQL**: Client-specified queries, reduces over/under-fetching. Risk: N+1 queries on server, complexity in caching.

**Asynchronous**:
- **Message Queues** (RabbitMQ, SQS): RabbitMQ supports point-to-point (direct queue) AND pub/sub (fanout/topic/headers exchanges). SQS standard queues are at-least-once; SQS FIFO provides ordered delivery plus queue-level deduplication for retries within the 5-minute deduplication window, so consumers should still be idempotent.
- **Event Streaming** (Kafka, Kinesis): Pub/sub, event log, replay capability.
- **Event-Driven Architecture**: Services react to events instead of being called directly. Enables loose coupling.

```
┌──────────┐     Event Bus (Kafka)     ┌──────────┐
│  Order   │ ──OrderCreated──────────→ │ Payment  │
│ Service  │                           │ Service  │
└──────────┘                           └──────────┘
                                              │
                                     PaymentCompleted
                                              │
                                              ▼
                                       ┌──────────┐
                                       │Shipping  │
                                       │ Service  │
                                       └──────────┘
```

### API Gateway Pattern

Single entry point for all clients. Responsibilities:
- Request routing to appropriate microservices
- Authentication/authorization
- Rate limiting and throttling
- Request/response transformation
- Load balancing
- Circuit breaking
- API versioning

**Implementations (2026 landscape)**:

| Gateway | Strengths | Weaknesses | Best Fit |
|---------|-----------|------------|----------|
| **Spring Cloud Gateway** | Native Spring integration, reactive (Netty), Java filters | Higher memory than native gateways, JVM startup | Java-centric teams, monolith strangler |
| **Kong** | Mature plugin ecosystem (Lua/OpenResty), API monetization, enterprise features | Lua skill barrier for custom plugins | Multi-stack orgs, external APIs with billing |
| **Envoy / Envoy Gateway** | Cloud-native, gRPC/xDS, unified north-south + east-west, Gateway API | C++ for deep customization, heavier than NGINX | Kubernetes + service mesh alignment |
| **Kubernetes Gateway API** | Vendor-neutral standard (GA, replacing Ingress), role-split (GatewayClass/Gateway/HTTPRoute) | Still maturing for some advanced L7 cases | Portable K8s ingress/egress |
| **AWS API Gateway** | Managed, native AWS IAM/WAF integration, Lambda auth | AWS lock-in, cold starts for rare routes | Serverless + AWS-native workloads |

The **Zuul → Spring Cloud Gateway** migration is complete — Zuul 1 is deprecated and Spring Cloud Gateway (MVC or reactive) is the supported successor. The Kubernetes **Gateway API** (GA since late 2023) is increasingly the standard interface layered on top of Envoy/Istio/Cilium/Kong implementations.

```java
// Spring Cloud Gateway configuration (Spring Cloud 2025.x / Northfields)
@Bean
public RouteLocator routes(RouteLocatorBuilder builder) {
    return builder.routes()
        .route("order-service", r -> r
            .path("/api/orders/**")
            .filters(f -> f
                .circuitBreaker(c -> c.setName("orderCB").setFallbackUri("forward:/fallback"))
                .retry(retryConfig -> retryConfig.setRetries(3))
                .requestRateLimiter(rl -> rl.setRateLimiter(redisRateLimiter()))
            )
            .uri("lb://order-service"))
        .build();
}
```

---

## 2. Resilience Patterns

### Circuit Breaker

Prevents cascading failures by stopping calls to a failing downstream service.

**States**: CLOSED (normal) → OPEN (failing, reject calls) → HALF_OPEN (test recovery)

```java
// Resilience4j Circuit Breaker
@CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
public PaymentResult processPayment(PaymentRequest request) {
    return paymentClient.charge(request);
}

private PaymentResult paymentFallback(PaymentRequest request, Exception ex) {
    // Queue for retry, return pending status, etc.
    return PaymentResult.pending(request.getOrderId());
}

// Configuration
resilience4j.circuitbreaker:
  instances:
    paymentService:
      slidingWindowSize: 10
      failureRateThreshold: 50          # Open after 50% failure
      waitDurationInOpenState: 30s      # Wait before half-open
      permittedNumberOfCallsInHalfOpenState: 3
      slowCallDurationThreshold: 2s
      slowCallRateThreshold: 80
```

### Retry with Exponential Backoff

```java
@Retry(name = "externalApi", fallbackMethod = "fallback")
public Response callExternalApi() {
    return restTemplate.getForObject(url, Response.class);
}

// Configuration
resilience4j.retry:
  instances:
    externalApi:
      maxAttempts: 3
      waitDuration: 500ms
      enableExponentialBackoff: true
      exponentialBackoffMultiplier: 2    # 500ms, 1s, 2s
      retryExceptions:
        - java.io.IOException
        - java.util.concurrent.TimeoutException
      ignoreExceptions:
        - com.app.BusinessException       # Don't retry business errors
```

### Bulkhead

Isolates resources so that one slow service doesn't consume all threads.

- **Thread Pool Bulkhead**: Dedicated thread pool per downstream. Risk: extra thread context switching.
- **Semaphore Bulkhead**: Limits concurrent calls. Runs on caller's thread. Lighter weight.

```java
@Bulkhead(name = "inventoryService", type = Bulkhead.Type.THREADPOOL)
public CompletableFuture<Inventory> checkInventory(String sku) {
    return CompletableFuture.supplyAsync(() -> inventoryClient.check(sku));
}
```

### Rate Limiter

```java
@RateLimiter(name = "thirdPartyApi")
public Response callThirdParty() { ... }

// Configuration
resilience4j.ratelimiter:
  instances:
    thirdPartyApi:
      limitForPeriod: 100
      limitRefreshPeriod: 1s
      timeoutDuration: 500ms
```

### Composing Resilience4j Patterns

Resilience4j replaced the deprecated Netflix **Hystrix** (end-of-life since 2018, removed from Spring Cloud). It favors functional composition over AOP, so you can stack only what you need. Order matters — the decorator closest to the supplier runs first.

```java
// Recommended decoration order (inside-out):
// Supplier -> Bulkhead -> TimeLimiter -> CircuitBreaker -> Retry -> Fallback
CompletionStage<String> result = Decorators.ofSupplier(backendService::call)
    .withThreadPoolBulkhead(threadPoolBulkhead)   // isolate resources
    .withTimeLimiter(timeLimiter, scheduler)       // bound latency
    .withCircuitBreaker(circuitBreaker)            // stop cascading failures
    .withRetry(retry, scheduler)                   // async retry
    .withFallback(List.of(TimeoutException.class,
                          CallNotPermittedException.class,
                          BulkheadFullException.class),
                  throwable -> "fallback")
    .get();
```

**Reactive integration**: Resilience4j ships `CircuitBreakerOperator`, `RetryOperator`, `BulkheadOperator` for Project Reactor (`Mono`/`Flux`) — apply via `transformDeferred(...)`. Spring Cloud Circuit Breaker is the abstraction layer that sits on top (defaults to Resilience4j).

### Timeout

Always set timeouts. Never trust default infinite timeouts.

```java
// RestTemplate
restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory() {{
    setConnectTimeout(2000);  // 2s connect
    setReadTimeout(5000);     // 5s read
}});

// WebClient
WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .responseTimeout(Duration.ofSeconds(5))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
    ))
    .build();
```

---

## 3. Distributed Data Patterns

### Saga Pattern

Manages distributed transactions through a sequence of local transactions, each publishing events that trigger the next step.

**Choreography** (event-driven):
```
Order Service          Payment Service       Inventory Service
     │                       │                       │
     │──OrderCreated──→      │                       │
     │                 PaymentCharged──→               │
     │                       │               InventoryReserved
     │←──────────────────────│←──────────────────────│
     │  OrderConfirmed       │                       │
```

**Orchestration** (central coordinator):
```
         ┌──────────────┐
         │    Saga       │
         │ Orchestrator  │
         └──────┬───────┘
                │
    ┌───────────┼───────────┐
    ▼           ▼           ▼
 Order      Payment     Inventory
Service     Service     Service
```

**Compensating transactions**: Each step must have a "undo" action. E.g., if inventory reservation fails after payment, issue a refund.

**Risks**:
- Choreography: Hard to track overall saga state, can become tangled
- Orchestration: Single point of failure (orchestrator), tight coupling
- Both: Eventual consistency, complex error handling

### Outbox Pattern

Ensures reliable event publishing by writing to a DB table (outbox) in the same transaction as the business data, then asynchronously publishing events from the outbox.

```
┌─────────────────────────────────────────┐
│  Database Transaction                    │
│  1. INSERT INTO orders (...)            │
│  2. INSERT INTO outbox (event_data)     │
│  COMMIT                                 │
└─────────────────────────────────────────┘
                    │
        Debezium CDC / Polling Reader
                    │
                    ▼
            ┌──────────────┐
            │    Kafka     │
            │   Topic      │
            └──────────────┘
```

**Change Data Capture (CDC)** with Debezium reads the database transaction log (WAL in PostgreSQL) and publishes changes to Kafka. No polling overhead, no dual-write problem.

### Inbox Pattern & Idempotency Keys

True "exactly-once delivery" is impossible in distributed systems (Two Generals Problem). The realistic target is **at-least-once delivery + idempotent processing = effectively-exactly-once**.

- **Outbox** (producer side): transactional event publishing, eliminates dual-write.
- **Inbox** (consumer side): record the processed `message_id` (or business idempotency key) in the same DB transaction as the state change. Skip replays whose key already exists.

```java
// Idempotent HTTP handler using client-supplied Idempotency-Key header
@PostMapping("/payments")
public ResponseEntity<Payment> charge(@RequestHeader("Idempotency-Key") String key,
                                      @RequestBody ChargeRequest req) {
    return idempotencyStore.findResponse(key)
        .map(cached -> ResponseEntity.ok(cached))      // replay -> cached response
        .orElseGet(() -> {
            Payment p = paymentService.charge(req);     // one-time effect
            idempotencyStore.save(key, p, Duration.ofHours(24));
            return ResponseEntity.ok(p);
        });
}
```

**Design rules**:
- Idempotency keys are client-generated (UUIDv7 recommended — sortable by time).
- Store the key + request fingerprint (hash) to detect "same key, different body" misuse -> return 422.
- TTL the store (24h typical) to bound storage.
- For natural idempotency: prefer `PUT`/`UPSERT` and conditional updates (`WHERE version = ?`) over `POST`/`INSERT`.

### CQRS (Command Query Responsibility Segregation)

Separate read and write models. Writes go to a normalized store, reads come from a denormalized/optimized store.

```
Commands ──→ Write Model (PostgreSQL) ──events──→ Read Model (Elasticsearch)
                                                          ↑
Queries ──────────────────────────────────────────────────┘
```

**When to use**: High read/write ratio with different optimization needs, complex domain with simple read patterns, event sourcing systems.

**Risk**: Eventual consistency between read and write models. UI must handle stale reads (optimistic UI, polling, WebSocket updates).

### Event Sourcing

Store every state change as an immutable event instead of storing current state.

```java
// Event store
public interface AccountEvent {
    record AccountOpened(String id, BigDecimal balance) implements AccountEvent {}
    record MoneyDeposited(String id, BigDecimal amount) implements AccountEvent {}
    record MoneyWithdrawn(String id, BigDecimal amount) implements AccountEvent {}
}

// Rebuild state by replaying events
Account account = events.stream()
    .reduce(Account.empty(), Account::apply, (a, b) -> b);
```

**Benefits**: Complete audit trail, temporal queries, easy debugging (replay events).
**Risks**: Event schema evolution, eventual consistency, snapshot management for performance, complexity.

---

## 4. Service Discovery & Load Balancing

### Client-Side Discovery (Eureka, Consul)

```
┌──────────┐     Register     ┌──────────────┐
│ Service A │ ──────────────→ │   Registry   │
│ Instance 1│                 │ (Eureka)     │
└──────────┘                  └──────┬───────┘
                                      │ Query
┌──────────┐                          │
│ Service B │ ←───────────────────────┘
│ (Client)  │──→ Service A Instance 1 (direct call)
└──────────┘
```

### Server-Side Discovery (K8s Services, AWS ALB)

```
┌──────────┐          ┌──────────────┐         ┌──────────┐
│ Service B │ ──────→ │ Load Balancer│ ──────→ │ Service A│
│ (Client)  │         │ / K8s Service│         │ Instance │
└──────────┘          └──────────────┘         └──────────┘
```

In Kubernetes, services get a stable DNS name and the kube-proxy handles load balancing. No need for Eureka.

**2026 trend**: Kubernetes-native discovery has largely displaced Eureka/Consul for greenfield microservices. `Spring Cloud LoadBalancer` replaced Netflix **Ribbon** (deprecated) as the client-side LB when running outside of K8s. Eureka is the only Netflix OSS component still actively maintained inside Spring Cloud; Ribbon/Hystrix/Zuul/Archaius are in maintenance mode only.

---

## 5. Observability (Three Pillars)

### OpenTelemetry vs Micrometer Tracing (2026 state)

OpenTelemetry (OTel) is the **CNCF-backed vendor-neutral standard**. The Tracing spec is stable (1.x), Metrics spec is stable, Logs spec is stable. OTLP is the canonical wire protocol to collectors (Grafana, Datadog, New Relic, Jaeger, Honeycomb, etc.).

In the Spring Boot 3.x ecosystem, two paths coexist:

| Approach | Description | When to use |
|----------|-------------|-------------|
| **Micrometer Observation + `micrometer-tracing-bridge-otel`** | Single Observation API covers metrics + traces + logs; bridge emits OTLP spans. Spring auto-config | Default for Spring Boot; unified facade; good for mixed Spring/non-Spring teams |
| **OpenTelemetry Java Agent** (auto-instrumentation) | Byte-code agent attached at JVM start; zero code changes; covers 100+ libraries | Polyglot orgs, legacy apps, when you can't modify code |
| **OpenTelemetry SDK (manual)** | Direct use of OTel API (`Tracer`, `Meter`) | Non-Spring apps, fine-grained control |

```java
// Spring Boot 3+ with Micrometer Tracing (recommended for Spring apps)
// application.yml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% in dev; use tail-based sampling at collector in prod
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
    metrics:
      export:
        url: http://otel-collector:4318/v1/metrics

// Trace propagation across services via W3C Trace Context headers
// traceparent: 00-<trace-id>-<span-id>-<flags>
// baggage:     userId=u1,tenantId=t42    (key/value attached to trace)
```

**Trace** = end-to-end request journey across services.
**Span** = single operation within a trace (e.g., DB query, HTTP call).
**Context propagation** = W3C Trace Context is the default standard (`traceparent`, `tracestate`). B3 headers (Zipkin) are legacy but still supported.

```java
// Custom span with Micrometer Observation API (portable across tracers)
Observation.createNotStarted("order.process", observationRegistry)
    .contextualName("process-order")
    .lowCardinalityKeyValue("order.type", "standard")
    .highCardinalityKeyValue("order.id", orderId)   // high-cardinality -> span only, not metric
    .observe(() -> processOrder(orderId));
```

### Metrics (Prometheus + Grafana)

```java
// Custom metrics with Micrometer
@Component
public class OrderMetrics {
    private final Counter ordersCreated;
    private final Timer orderProcessingTime;

    public OrderMetrics(MeterRegistry registry) {
        ordersCreated = Counter.builder("orders.created")
            .tag("type", "standard")
            .register(registry);
        orderProcessingTime = Timer.builder("orders.processing.time")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    public void recordOrder(Runnable action) {
        orderProcessingTime.record(action);
        ordersCreated.increment();
    }
}
```

**Four Golden Signals** (from Google SRE):
1. **Latency**: Time to process request (distinguish success vs error latency)
2. **Traffic**: Requests per second
3. **Errors**: Error rate (5xx, timeouts, business errors)
4. **Saturation**: How full your service is (CPU, memory, queue depth)

### Structured Logging

```java
// Use MDC for correlation
MDC.put("traceId", traceId);
MDC.put("userId", userId);
log.info("Order created", kv("orderId", orderId), kv("total", total));
// Output: {"timestamp":"...","level":"INFO","message":"Order created",
//          "orderId":"123","total":99.99,"traceId":"abc","userId":"u1"}
```

In Spring Boot 3.x the trace/span IDs are auto-injected into MDC (`%X{traceId}`, `%X{spanId}`) when Micrometer Tracing is on the classpath — no manual `MDC.put` needed.

### eBPF-Based Observability (zero-instrumentation)

eBPF (extended Berkeley Packet Filter) lets you run sandboxed programs in the Linux kernel, giving observability tools deep visibility without app changes. eBPF-based observability adoption has grown rapidly; CNCF annual surveys track the trend.

| Tool | Purpose |
|------|---------|
| **Cilium + Hubble** | CNI + L3-L7 network observability; Default CNI: GKE Dataplane V2 (enabled by default on new clusters); available as option on AKS (Azure CNI powered by Cilium) and EKS (via add-on; AWS VPC CNI remains the EKS default) |
| **Pixie** | Auto-instrumented APM, service maps, no SDKs |
| **Grafana Beyla** | Emits OpenTelemetry spans/metrics from any HTTP/gRPC binary, no SDK |
| **Parca / Pyroscope** | Continuous CPU profiling (Java, Go, Python, native) via eBPF stack walks |
| **Tetragon** | Runtime security observability and enforcement |

**Java-specific note**: eBPF profilers can walk JVM stacks via JFR or `perf-map-agent`/`async-profiler` integration. Beyla auto-detects Spring Boot HTTP endpoints and emits OTel spans without bytecode agents.

### SLO / SLI / Error Budgets (SRE)

| Term | Definition | Example |
|------|------------|---------|
| **SLI** | A measured quantity (a *ratio*) describing service behavior | `successful_requests / total_requests` |
| **SLO** | Target threshold for the SLI over a time window | "99.9% of `/checkout` requests succeed in < 300 ms over 28 days" |
| **SLA** | Contractual SLO with consequences (refunds, credits) | "99.5% uptime or 10% credit" |
| **Error budget** | `1 - SLO`. The amount of "unreliability" allowed in the window | 99.9% SLO -> 0.1% = ~43 min/month |

**Error budget policy** drives engineering decisions:
- Burn rate > 4x expected -> page on-call, freeze risky deploys
- Burn rate 2x-4x -> alert owners, pause non-essential changes
- Budget healthy -> ship faster, take more risk

**Microservices nuance**: Define SLOs per **user journey** (checkout, search) not per service — a user doesn't care which of 12 backend services failed. Compose journey SLOs from per-hop SLIs using distributed tracing.

```yaml
# Example SLO definition (Sloth/OpenSLO format)
sloth:
  service: checkout
  slos:
    - name: availability
      objective: 99.9              # 0.1% error budget
      sli:
        events:
          error_query: sum(rate(http_requests_total{status=~"5..",route="/checkout"}[5m]))
          total_query: sum(rate(http_requests_total{route="/checkout"}[5m]))
      alerting:
        page_alert:    { burn_rate_factor: 14.4, time_window: 1h }   # 2% budget in 1h
        ticket_alert:  { burn_rate_factor: 1,    time_window: 24h }
```

---

## 6. Cloud-Native Patterns

### 12-Factor App Principles

1. **Codebase**: One codebase, many deploys
2. **Dependencies**: Explicitly declare and isolate
3. **Config**: Store in environment (not in code)
4. **Backing Services**: Treat as attached resources
5. **Build, Release, Run**: Strictly separate stages
6. **Processes**: Stateless, share-nothing
7. **Port Binding**: Self-contained, export via port
8. **Concurrency**: Scale via process model
9. **Disposability**: Fast startup, graceful shutdown
10. **Dev/Prod Parity**: Keep environments similar
11. **Logs**: Treat as event streams
12. **Admin Processes**: Run as one-off processes

### Health Checks & Graceful Shutdown

```java
// Spring Boot Actuator health groups
management:
  endpoint:
    health:
      show-details: always
      group:
        readiness:
          include: db, redis, kafka
        liveness:
          include: ping

// Kubernetes probes
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5

// Graceful shutdown
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

### Configuration Management

**Spring Cloud Config Server**: Centralized config backed by Git. Supports encryption, profiles, label (branch).

**Kubernetes ConfigMaps/Secrets**:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-service-config
data:
  application.yml: |
    app:
      feature-x-enabled: true
      max-retries: 3
```

**Vault / AWS Secrets Manager**: For sensitive configuration (DB passwords, API keys, certificates). Spring Cloud Vault integrates natively.

---

## 7. Containerization & Kubernetes

### Docker Best Practices for Java

```dockerfile
# Multi-stage build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Don't run as root
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=builder /app/build/libs/*.jar app.jar

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseZGC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**Risks**:
- **Container memory**: JVM must respect container limits. Use `-XX:+UseContainerSupport` (default since Java 10) and `-XX:MaxRAMPercentage`.
- **Image size**: Use JRE (not JDK) in production. Alpine images are smaller. Consider jlink for custom runtime.
- **Layer caching**: Order Dockerfile instructions from least to most frequently changing.

### Kubernetes Essentials

**Pod Design Patterns**:
- **Sidecar**: Helper container alongside main (e.g., log shipper, proxy)
- **Ambassador**: Proxy outbound connections (e.g., database proxy)
- **Init Container**: Run before main container (e.g., DB migration, config fetch)

**Resource Management**:
```yaml
resources:
  requests:        # Scheduler uses this for placement
    memory: "512Mi"
    cpu: "250m"
  limits:          # Hard ceiling
    memory: "1Gi"  # OOMKilled if exceeded
    cpu: "1000m"   # Throttled if exceeded
```

**Horizontal Pod Autoscaler (HPA)**:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: order-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Pods
      pods:
        metric:
          name: http_requests_per_second
        target:
          type: AverageValue
          averageValue: "1000"
```

**KEDA** (Kubernetes Event-Driven Autoscaling) extends HPA with event-source scalers (Kafka lag, SQS depth, Prometheus query, cron) — essential for queue-consumer workloads where CPU is a poor scaling signal.

---

## 8. Service Mesh

A service mesh externalizes service-to-service concerns — mTLS, retries, timeouts, traffic shifting, observability — into infrastructure instead of application code. In 2026 the architectural debate has shifted from "sidecar vs no mesh" to **sidecar vs sidecarless (ambient / eBPF)**.

### Sidecar vs Ambient Mesh

| Aspect | Sidecar (classic Istio, Linkerd) | Ambient / eBPF (Istio Ambient, Cilium Mesh) |
|--------|----------------------------------|---------------------------------------------|
| Deployment | Envoy/proxy injected in each pod | Per-node `ztunnel` + optional per-namespace `waypoint` proxy (Istio); kernel eBPF (Cilium) |
| Memory per pod | 40-100 MB (Envoy) or 10-20 MB (Linkerd2-proxy) | ~0 MB per app pod; shared per node |
| Upgrade model | Restart pod to upgrade proxy | Upgrade infra; apps unaffected |
| L7 policy | Always available | Only when waypoint proxy is attached |
| Security blast radius | Isolated per pod | Shared per node -> stricter node hardening required |
| Maturity (2026) | Very mature | Production-ready; Istio Ambient GA, Cilium Service Mesh GA |

**Performance (2026 benchmarks)**: Under low-to-mid load, Linkerd's lightweight sidecar still leads. Under high load (thousands of RPS) and with mTLS enabled, **Istio Ambient** shows the best latency. Ambient mode reduces per-pod sidecar overhead significantly; published benchmarks vary widely — consult Istio/Linkerd's own performance docs for current numbers. Cilium (eBPF L4) is fastest when L7 features aren't needed.

### Mesh Comparison

| Mesh | Model | Strengths | Weaknesses |
|------|-------|-----------|------------|
| **Istio** | Sidecar + Ambient | Feature-rich, Envoy xDS, large ecosystem, Gateway API | Operational complexity, YAML sprawl |
| **Linkerd** | Sidecar only | Ultra-simple, Rust-based `linkerd2-proxy`, great defaults | Fewer features, conservative on eBPF |
| **Cilium Service Mesh** | Sidecarless (eBPF) + shared Envoy for L7 | Near-zero overhead, CNI + mesh unified | Newer, L7 still needs a proxy hop |
| **Consul Connect** | Sidecar | Multi-runtime (VMs + K8s), HashiCorp ecosystem | Less K8s-native feel |

### What Mesh Replaces in Java Code

| Concern | Pre-mesh (Java/Spring) | With mesh |
|---------|------------------------|-----------|
| mTLS | Custom keystore config | Transparent, rotated by mesh |
| Retries / timeouts | Resilience4j annotations | `VirtualService` / `HTTPRoute` config |
| Traffic split (canary) | Feature flags | `DestinationRule` weights |
| Tracing headers | Micrometer Tracing | Proxy auto-propagates (still need context in app) |
| Rate limiting | Bucket4j / Resilience4j | Envoy rate-limit filter |

**Still needed in app code**: business-level retries (idempotency keys), circuit breakers that understand business errors, timeouts for in-process work, structured logs with trace IDs. Mesh handles *network* resilience; Resilience4j handles *domain* resilience.

---

## 9. Java Frameworks for Cloud-Native (2026)

Spring Boot is no longer the only serious choice. **Quarkus** (Red Hat) and **Micronaut** target GraalVM native image, build-time DI, and minimal runtime reflection.

| Metric | Spring Boot 3.x (native) | Quarkus (native) | Micronaut (native) |
|--------|--------------------------|------------------|---------------------|
| Cold start | ~100 ms | ~50 ms | ~50 ms |
| RSS memory | ~150 MB | ~70 MB | ~80 MB |
| DI model | Runtime reflection (+ AOT hints) | Build-time | Build-time (annotation processor) |
| Hot reload | Devtools | Dev mode (live coding) | Reload support |
| Ecosystem | Largest; Spring Cloud, Data, Security | MicroProfile + Spring compat | Polyglot (Kotlin/Groovy), GCP/AWS SDKs |
| Best fit | Enterprise, complex domain, team familiarity | Serverless, Knative, FaaS | Microservices, serverless, CLI tools |

**GraalVM native image trade-offs**: dramatically faster startup + smaller memory, at the cost of longer build times (minutes), no JIT peak throughput, and library compatibility work (reflection/proxy hints). Use native for **short-lived / scale-to-zero** workloads (Lambda, Knative, CLI). Keep JVM-mode for high-throughput services where peak throughput beats startup time.

**Spring Boot 3.x AOT**: `spring-boot-starter-aot` + `native-maven-plugin` produce GraalVM images. Spring Framework 6 `@ImportRuntimeHints` lets you register reflection/resource hints for GraalVM.

---

## 10. Cloud Provider Services (AWS Focus)

### Compute

| Service | Use Case |
|---------|----------|
| EC2 | Full control, legacy apps, GPU workloads |
| ECS/Fargate | Container orchestration without managing K8s |
| EKS | Managed Kubernetes |
| Lambda | Event-driven, short-lived functions (<15min) |

### Messaging & Streaming

| Service | Use Case |
|---------|----------|
| SQS | Simple message queue, decoupling, FIFO queues add ordering plus queue-level deduplication within a 5-minute window |
| SNS | Fan-out pub/sub notifications |
| Kinesis | Real-time data streaming, ordered per shard |
| EventBridge | Event routing with rules/filtering |
| MSK | Managed Kafka |

### Storage & Database

| Service | Use Case |
|---------|----------|
| S3 | Object storage, backups, static assets |
| RDS | Managed relational databases |
| Aurora | High-performance MySQL/PostgreSQL compatible |
| DynamoDB | Key-value/document, single-digit ms latency |
| ElastiCache | Managed Redis/Memcached |
| DocumentDB | Managed MongoDB-compatible |

---

## 11. Common Senior Interview Questions

**Q: How do you handle distributed transactions across microservices?**
Avoid 2PC if possible. Use Saga pattern (choreography for simple flows, orchestration for complex). Implement compensating transactions for rollback. Use the Outbox pattern + CDC (Debezium) for reliable event publishing, and the Inbox pattern + idempotency keys on the consumer to achieve effectively-exactly-once processing. Accept eventual consistency and design UIs to handle it (optimistic updates, status polling).

**Q: How do you decide service boundaries?**
Start with DDD: identify bounded contexts through event storming. Align with team structure (Conway's Law). A service should be independently deployable, own its data, and represent a business capability. Start coarser, split later when you have more domain understanding. Avoid splitting by technical layer (data service, logic service).

**Q: How do you handle service-to-service authentication?**
mTLS for transport security — let a service mesh (Istio, Linkerd, Cilium) handle certificate issuance and rotation transparently. For identity propagation, use short-lived JWTs (SPIFFE/SPIRE issues workload identities). OAuth2 Client Credentials flow for machine-to-machine. Avoid shared secrets across services.

**Q: What's the difference between horizontal and vertical scaling?**
Vertical: bigger machine (more CPU/RAM). Horizontal: more instances. Microservices favor horizontal scaling. Requires stateless services (externalize session, cache, file storage). Vertical scaling has hardware limits and is a single point of failure.

**Q: How do you handle idempotency in distributed systems?**
Use idempotency keys (client-generated UUIDv7). Store processed request IDs with the response for a TTL and return the cached response on replay. Design operations to be naturally idempotent (PUT vs POST, UPSERT vs INSERT, conditional updates with version). Critical for retry logic and at-least-once delivery guarantees.

**Q: How do you migrate from a monolith to microservices?**
Strangler Fig pattern: gradually replace monolith functionality by routing new requests (via API gateway / reverse proxy) to microservices. Start with the least coupled, highest-value module. Use an anti-corruption layer. Keep shared database temporarily with a clear plan to separate (split read first via CDC, then writes). Branch by abstraction for internal refactoring.

**Q: Sidecar vs ambient/sidecarless service mesh — when would you choose which?**
Sidecars (Linkerd, classic Istio) give strong per-pod isolation, simple mental model, proven at scale. Ambient/eBPF (Istio Ambient, Cilium) cuts memory overhead and decouples proxy upgrades from app pods — wins at high pod density or when CPU/memory budget is tight. Choose sidecar for strict isolation / regulated workloads, ambient for cost efficiency at scale. Linkerd remains the simplest; Istio Ambient the most feature-rich sidecarless.

**Q: OpenTelemetry or Micrometer Tracing — which and why?**
In a Spring Boot app, use Micrometer Observation API with the `micrometer-tracing-bridge-otel` bridge: you get a unified metrics + traces facade and still export OTLP to any OTel backend. For polyglot orgs or legacy apps, the OpenTelemetry Java Agent offers zero-code auto-instrumentation. OTel is the standard; Micrometer is the idiomatic Spring facade on top.

**Q: How do SLOs change how you ship code?**
An SLO + error budget makes reliability a first-class engineering signal, not a vibes-based discussion. When the budget is healthy, ship faster; when you're burning through it (burn-rate alerts), freeze risky deploys and prioritize reliability work. Define SLOs per user journey (e.g., checkout p99 < 300 ms at 99.9%), not per service, so they reflect user experience.

**Q: What replaced Hystrix and Zuul, and why?**
Hystrix is end-of-life (maintenance since 2018). **Resilience4j** replaced it — lightweight, functional composition instead of AOP-only, better Java 8+ fit, reactive support. **Zuul 1** (blocking) was deprecated; **Spring Cloud Gateway** is the successor (reactive, Netty-based, part of Spring Cloud). **Ribbon** was replaced by **Spring Cloud LoadBalancer**. Netflix OSS in Spring Cloud is now effectively just Eureka.

**Q: Quarkus/Micronaut vs Spring Boot — when would you pick a non-Spring framework?**
Pick Quarkus or Micronaut when startup time and memory dominate: AWS Lambda, Knative scale-to-zero, CLI tools, edge deployments. Quarkus (~50 ms cold start, ~70 MB RSS native) vs Spring Boot Native (~100 ms, ~150 MB). Stay on Spring Boot when ecosystem breadth (Spring Data/Security/Cloud), team familiarity, or third-party library compatibility wins — especially for long-running services where JIT peak throughput matters more than startup.
