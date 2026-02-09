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
- **gRPC**: Binary protocol (Protobuf), strongly typed, bidirectional streaming, ~10x faster than REST. Risk: harder to debug, requires code generation.
- **GraphQL**: Client-specified queries, reduces over/under-fetching. Risk: N+1 queries on server, complexity in caching.

**Asynchronous**:
- **Message Queues** (RabbitMQ, SQS): Point-to-point, guaranteed delivery with acks.
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

**Implementations**: Spring Cloud Gateway, Kong, AWS API Gateway, Envoy + Istio

```java
// Spring Cloud Gateway configuration
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

---

## 5. Observability (Three Pillars)

### Distributed Tracing (OpenTelemetry)

```java
// Spring Boot 3+ with Micrometer Tracing
// application.yml
management:
  tracing:
    sampling:
      probability: 1.0    # 100% in dev, lower in prod
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces

// Trace propagation across services via W3C Trace Context headers
// traceparent: 00-<trace-id>-<span-id>-<flags>
```

**Trace** = end-to-end request journey across services.
**Span** = single operation within a trace (e.g., DB query, HTTP call).

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

---

## 8. Cloud Provider Services (AWS Focus)

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
| SQS | Simple message queue, decoupling, exactly-once (FIFO) |
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

## 9. Common Senior Interview Questions

**Q: How do you handle distributed transactions across microservices?**
Avoid 2PC if possible. Use Saga pattern (choreography for simple flows, orchestration for complex). Implement compensating transactions for rollback. Use the Outbox pattern + CDC for reliable event publishing. Accept eventual consistency and design UIs to handle it (optimistic updates, status polling).

**Q: How do you decide service boundaries?**
Start with DDD: identify bounded contexts through event storming. Align with team structure (Conway's Law). A service should be independently deployable, own its data, and represent a business capability. Start coarser, split later when you have more domain understanding. Avoid splitting by technical layer (data service, logic service).

**Q: How do you handle service-to-service authentication?**
mTLS for transport security (service mesh like Istio handles this transparently). JWT tokens with short expiry for identity propagation. API keys for external integrations. OAuth2 Client Credentials flow for machine-to-machine. Avoid shared secrets across services.

**Q: What's the difference between horizontal and vertical scaling?**
Vertical: bigger machine (more CPU/RAM). Horizontal: more instances. Microservices favor horizontal scaling. Requires stateless services (externalize session, cache, file storage). Vertical scaling has hardware limits and is a single point of failure.

**Q: How do you handle idempotency in distributed systems?**
Use idempotency keys (client-generated UUID). Store processed request IDs and return cached response for duplicates. Design operations to be naturally idempotent (PUT vs POST, UPSERT vs INSERT). Critical for retry logic and at-least-once delivery guarantees.

**Q: How do you migrate from a monolith to microservices?**
Strangler Fig pattern: gradually replace monolith functionality by routing new requests to microservices. Start with the least coupled, highest-value module. Use an anti-corruption layer. Keep shared database temporarily with a clear plan to separate. Branch by abstraction for internal refactoring.
