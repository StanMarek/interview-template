# Cloud & Microservices — Senior Engineer Interview Preparation

---

## 1. Containerization

### Docker for Node.js — Multi-Stage Dockerfile

```dockerfile
# ── Stage 1: Build ──────────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json tsconfig.json ./
RUN npm ci --ignore-scripts        # Exact lockfile deps, no lifecycle scripts
COPY src/ src/
RUN npm run build && npm prune --production

# ── Stage 2: Production ────────────────────────────────────
FROM node:22-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
ENV NODE_OPTIONS="--max-old-space-size=384"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1
USER app
EXPOSE 3000
CMD ["node", "dist/main.js"]
```

**Why `npm ci`?** Installs exact versions from lockfile, deletes existing `node_modules`, deterministic builds.

### Layer Caching Optimization

Copy `package.json` + lockfile **before** source code. `npm ci` layer is cached until dependencies change:

```
Layer 1: FROM node:22-alpine           ← cached (base)
Layer 2: COPY package*.json ./         ← cached until deps change
Layer 3: RUN npm ci                    ← cached until deps change
Layer 4: COPY src/ src/                ← invalidated on code change
Layer 5: RUN npm run build             ← re-runs
```

### .dockerignore Essentials

```
node_modules
dist
.git
.env*
coverage
*.log
Dockerfile
docker-compose*.yml
```

Without it, `COPY . .` sends `node_modules` to the daemon, destroying cache and slowing builds.

### Container Security

| Practice | Why |
|----------|-----|
| Non-root user (`USER app`) | Prevents container-breakout escalation |
| Read-only filesystem (`--read-only`) | Blocks runtime tampering |
| Minimal base image (Alpine/distroless) | Fewer CVEs, smaller attack surface |
| Pin image digests in production | Prevents supply-chain tag mutation |
| No secrets in layers | Use runtime env vars or mounted secrets |
| Scan images (`trivy`, `docker scout`) | Detect known vulnerabilities |

### Docker Compose for Local Dev

```yaml
services:
  api:
    build: { context: ., target: builder }
    volumes: ["./src:/app/src", "/app/node_modules"]
    ports: ["3000:3000", "9229:9229"]    # app + debugger
    command: npx tsx watch src/main.ts
    depends_on:
      db: { condition: service_healthy }
  db:
    image: postgres:16-alpine
    healthcheck: { test: ["CMD-SHELL", "pg_isready"], interval: 5s }
```

---

## 2. Kubernetes for Node.js

### Pod Probes

```
Startup Probe  → passes once → enables liveness + readiness
Liveness Probe → fails       → kill + restart container
Readiness Probe→ fails       → remove from Service endpoints (no traffic)
```

```yaml
containers:
  - name: api
    image: my-app:1.2.0
    startupProbe:
      httpGet: { path: /health, port: 3000 }
      failureThreshold: 30
      periodSeconds: 2        # 60s max startup
    livenessProbe:
      httpGet: { path: /health/live, port: 3000 }
      periodSeconds: 10
    readinessProbe:
      httpGet: { path: /health/ready, port: 3000 }
      periodSeconds: 5
```

```typescript
// Liveness: process alive? Readiness: dependencies healthy?
app.get("/health/live", (_req, res) => res.json({ status: "alive" }));
app.get("/health/ready", async (_req, res) => {
  try {
    await pool.query("SELECT 1");
    res.json({ status: "ready" });
  } catch {
    res.status(503).json({ status: "not ready" });
  }
});
```

### Deployment Strategies

| Strategy | How | Rollback | Risk |
|----------|-----|----------|------|
| Rolling Update | Replace pods gradually | `kubectl rollout undo` | Mixed versions briefly |
| Blue-Green | Two environments, switch traffic | Switch back | 2x resources during deploy |
| Canary | Small % traffic to new version | Remove canary | Needs Istio/Argo for traffic split |

### Resource Requests, Limits & HPA

```yaml
resources:
  requests: { memory: "256Mi", cpu: "100m" }  # Scheduler placement
  limits:   { memory: "512Mi", cpu: "500m" }  # Hard ceiling (OOMKill / throttle)
```

**Critical**: Set `NODE_OPTIONS=--max-old-space-size=384` (~75% of memory limit) or V8 may OOMKill.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: api }
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource: { name: memory, target: { type: Utilization, averageUtilization: 75 } }
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 5min cooldown before scale-down
```

**Memory is the key autoscaling metric for Node.js** -- CPU scaling reacts too late for I/O-bound workloads.

### Service Types

| Type | Scope | Use Case |
|------|-------|----------|
| ClusterIP | Internal only | Service-to-service |
| NodePort | External via node IP:port | Dev/testing |
| LoadBalancer | Provisions cloud LB | Simple external access |
| Ingress | L7 routing (host/path, TLS) | Production external traffic |

### Graceful Shutdown (SIGTERM)

```typescript
function shutdown(signal: string) {
  console.log(`${signal} received. Draining...`);
  server.close(() => console.log("HTTP server closed"));
  pool.end().then(() => console.log("DB pool closed"));
  setTimeout(() => process.exit(1), 25_000); // safety net < terminationGracePeriodSeconds
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
```

**PID 1 gotcha**: Node.js as PID 1 may not receive signals. Use `tini` or `dumb-init` as entrypoint.

---

## 3. Serverless (AWS Lambda)

### Cold Start Lifecycle

```
Cold Start:  Download code → Init runtime → Init app → Handler   (~300ms-5s total)
Warm Start:  Handler only   (~1-50ms overhead)
```

| Mitigation | Effect | Tradeoff |
|-----------|--------|----------|
| Provisioned Concurrency | Pre-warms N instances | Pay for idle |
| ESBuild single-file bundle | Faster download + parse | Build step |
| Lazy DB initialization | Defer to first request | First-request latency |
| Smaller dependencies | Faster cold start | May limit library choice |

### Lambda vs Long-Running Server

| Aspect | Lambda | Express/Fastify |
|--------|--------|-----------------|
| Lifecycle | Per-invocation | Long-running |
| Connections | Per cold start | Pooled |
| Timeout | 15min max | Unlimited |
| Concurrency | 1 req/instance | Thousands/process |
| Cost | Per invocation | Per hour (idle or not) |

```typescript
import { APIGatewayProxyHandlerV2 } from "aws-lambda";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";

// Module scope: initialized ONCE per cold start, reused on warm
const dynamo = new DynamoDBClient({});

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  const body = JSON.parse(event.body ?? "{}");
  const result = await processOrder(body);
  return { statusCode: 200, body: JSON.stringify(result) };
};
```

### Event Sources

```
Synchronous:  API Gateway / ALB → Lambda → Response
Asynchronous: S3 / SNS / EventBridge → Lambda (retry 2x on failure)
Poll-based:   SQS / DynamoDB Streams / Kinesis → Lambda (batches)
```

```typescript
// SQS handler with partial batch failure reporting
import { SQSBatchResponse, SQSEvent } from "aws-lambda";

export const handler = async (event: SQSEvent): Promise<SQSBatchResponse> => {
  const failures: string[] = [];
  for (const record of event.Records) {
    try { await processMessage(JSON.parse(record.body)); }
    catch { failures.push(record.messageId); }
  }
  return { batchItemFailures: failures.map((id) => ({ itemIdentifier: id })) };
};
```

### Framework Comparison

| Tool | Strengths | Weaknesses |
|------|-----------|------------|
| Serverless Framework | Huge ecosystem, multi-cloud | Plugin quality varies |
| AWS SAM | Official AWS, local testing | Verbose, AWS-only |
| AWS CDK | Type safety, full language | Steeper learning curve |
| SST (Ion) | Built for fullstack, live dev | Smaller community |

**When serverless is wrong**: Long processes (>15min), WebSocket-heavy apps, steady high-throughput (cheaper on containers), vendor lock-in concern.

---

## 4. Microservice Communication Patterns

### Synchronous: REST, gRPC, GraphQL Federation

- **REST**: Ubiquitous, human-readable. Risk: temporal coupling.
- **gRPC**: Binary Protobuf, HTTP/2 streaming, ~10x throughput vs REST. Risk: harder to debug.
- **GraphQL Federation**: Gateway composes schemas from multiple services. Risk: N+1 on server side.

### Asynchronous: Queues vs Streams

```
Queue (SQS/RabbitMQ):  Producer → Queue → Consumer    (deleted after ack)
Stream (Kafka):        Producer → Partition → Consumer A/B  (retained, replayable)
```

### Circuit Breaker Pattern

```typescript
class CircuitBreaker {
  private state: "CLOSED" | "OPEN" | "HALF_OPEN" = "CLOSED";
  private failures = 0;
  private lastFailure = 0;

  constructor(private threshold: number, private resetMs: number) {}

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    if (this.state === "OPEN") {
      if (Date.now() - this.lastFailure > this.resetMs) {
        this.state = "HALF_OPEN";
      } else {
        throw new Error("Circuit OPEN — request rejected");
      }
    }
    try {
      const result = await fn();
      this.failures = 0;
      if (this.state === "HALF_OPEN") this.state = "CLOSED";
      return result;
    } catch (err) {
      this.failures++;
      this.lastFailure = Date.now();
      if (this.failures >= this.threshold) this.state = "OPEN";
      throw err;
    }
  }
}
```

### Retry with Exponential Backoff + Jitter

```typescript
async function withRetry<T>(fn: () => Promise<T>, maxRetries = 3, baseMs = 100): Promise<T> {
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try { return await fn(); }
    catch (err) {
      if (attempt === maxRetries) throw err;
      const delay = baseMs * 2 ** attempt;
      const jitter = Math.random() * delay;  // Prevent thundering herd
      await new Promise((r) => setTimeout(r, delay + jitter));
    }
  }
  throw new Error("unreachable");
}
```

### Saga Pattern (Distributed Transactions)

```
Orchestrator Saga: Create Order
  1. Reserve Inventory  → success
  2. Process Payment    → success
  3. Confirm Shipping   → FAILURE
     ← Compensate: 2c. Refund Payment
     ← Compensate: 1c. Release Inventory
```

```typescript
interface SagaStep<T> {
  name: string;
  execute: (ctx: T) => Promise<void>;
  compensate: (ctx: T) => Promise<void>;
}

async function runSaga<T>(steps: SagaStep<T>[], ctx: T): Promise<void> {
  const completed: SagaStep<T>[] = [];
  for (const step of steps) {
    try {
      await step.execute(ctx);
      completed.push(step);
    } catch (err) {
      for (const s of completed.reverse()) await s.compensate(ctx);
      throw new Error(`Saga failed at "${step.name}": ${err}`);
    }
  }
}
```

### Outbox Pattern

```
┌──────────────────────────────────┐
│  BEGIN TRANSACTION               │
│    INSERT INTO orders (...)      │
│    INSERT INTO outbox (event)    │
│  COMMIT                          │
└─────────────┬────────────────────┘
              │
   CDC (Debezium) or Polling Worker
              │
              ▼
       Kafka / SQS Topic
```

Avoids the **dual-write problem**: DB commit succeeds but message publish fails (or vice versa).

---

## 5. Message Queues & Event Streaming

### RabbitMQ

Exchange types: **Direct** (exact key), **Fanout** (broadcast), **Topic** (pattern), **Headers**. Manual acks; nack with requeue=false sends to DLX.

```typescript
import amqplib from "amqplib";

const conn = await amqplib.connect("amqp://localhost");
const ch = await conn.createChannel();
await ch.prefetch(10);
await ch.assertExchange("order-events", "topic", { durable: true });
await ch.assertQueue("payment-processor", {
  durable: true,
  arguments: { "x-dead-letter-exchange": "dlx", "x-message-ttl": 30000 },
});
await ch.bindQueue("payment-processor", "order-events", "order.created");

// Publish
ch.publish("order-events", "order.created", Buffer.from(JSON.stringify(payload)), {
  persistent: true, messageId: crypto.randomUUID(),
});

// Consume with manual ack
ch.consume("payment-processor", async (msg) => {
  if (!msg) return;
  try { await handle(JSON.parse(msg.content.toString())); ch.ack(msg); }
  catch { ch.nack(msg, false, false); } // → DLQ
});
```

### Apache Kafka

```
Topic: "orders"   (3 partitions)
┌─────────┐  ┌─────────┐  ┌─────────┐
│  P0     │  │  P1     │  │  P2     │     Same key → same partition → ordering
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
Consumer Group: "payment-service"
  ConsumerA     ConsumerB    ConsumerC     Each partition → 1 consumer in group
```

```typescript
import { Kafka } from "kafkajs";

const kafka = new Kafka({ brokers: ["kafka:9092"], clientId: "order-svc" });

// Producer (idempotent for exactly-once semantics)
const producer = kafka.producer({ idempotent: true });
await producer.send({
  topic: "order-events",
  messages: [{ key: order.customerId, value: JSON.stringify(event) }],
});

// Consumer
const consumer = kafka.consumer({ groupId: "payment-service" });
await consumer.subscribe({ topic: "order-events" });
await consumer.run({
  eachMessage: async ({ message }) => {
    await processEvent(JSON.parse(message.value!.toString()));
  },
});
```

### SQS/SNS & Ordering Guarantees

| System | Ordering |
|--------|----------|
| Kafka | Per partition (use message key) |
| RabbitMQ | Per queue with single consumer |
| SQS Standard | No guarantee |
| SQS FIFO | Per message group ID (3k msg/s limit) |

**SNS + SQS fan-out**: SNS topic publishes to multiple SQS queues -- each service gets its own queue.

### Idempotency

```typescript
async function processOnce(messageId: string, redis: Redis, handler: () => Promise<void>) {
  const acquired = await redis.set(`processed:${messageId}`, "1", "EX", 86400, "NX");
  if (!acquired) return; // duplicate
  try { await handler(); }
  catch (err) { await redis.del(`processed:${messageId}`); throw err; }
}
```

---

## 6. Observability

### Structured Logging (pino)

```typescript
import pino from "pino";

const logger = pino({
  level: process.env.LOG_LEVEL ?? "info",
  redact: ["req.headers.authorization", "password"],
});

const reqLog = logger.child({ requestId: "abc-123", userId: "u-456" });
reqLog.info({ orderId: "ord-789", total: 99.99 }, "Order created");
// → {"level":"info","requestId":"abc-123","orderId":"ord-789","msg":"Order created"}
```

| Aspect | pino | winston |
|--------|------|---------|
| Speed | 5-10x faster | Slower (sync serialization) |
| Output | JSON by default | Configurable |
| Philosophy | Log to stdout only | Built-in transports |

### Distributed Tracing (OpenTelemetry)

```typescript
// tracing.ts — MUST import before all other modules
import { NodeSDK } from "@opentelemetry/sdk-node";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { HttpInstrumentation } from "@opentelemetry/instrumentation-http";
import { ExpressInstrumentation } from "@opentelemetry/instrumentation-express";

const sdk = new NodeSDK({
  traceExporter: new OTLPTraceExporter({ url: "http://otel-collector:4318/v1/traces" }),
  instrumentations: [new HttpInstrumentation(), new ExpressInstrumentation()],
});
sdk.start();
```

Traces propagate via W3C `traceparent` header. **Span** = single operation. **Trace** = full journey across services.

### Metrics (Prometheus)

```typescript
import promClient from "prom-client";

promClient.collectDefaultMetrics({ prefix: "node_" }); // heap, event loop, GC

const httpDuration = new promClient.Histogram({
  name: "http_request_duration_seconds",
  help: "Request duration",
  labelNames: ["method", "route", "status"],
  buckets: [0.01, 0.05, 0.1, 0.5, 1, 5],
});

app.get("/metrics", async (_req, res) => {
  res.set("Content-Type", promClient.register.contentType);
  res.end(await promClient.register.metrics());
});
```

**Four Golden Signals** (Google SRE): **Latency** (p50/p95/p99), **Traffic** (req/s), **Errors** (5xx rate), **Saturation** (event loop lag, heap usage).

### Correlation IDs with AsyncLocalStorage

```typescript
import { AsyncLocalStorage } from "node:async_hooks";

const als = new AsyncLocalStorage<{ requestId: string }>();

// Middleware: set context for entire request lifecycle
app.use((req, _res, next) => {
  const requestId = (req.headers["x-request-id"] as string) ?? crypto.randomUUID();
  als.run({ requestId }, next);
});

// Anywhere in the call chain — no manual passing needed
function getRequestId(): string | undefined {
  return als.getStore()?.requestId;
}
```

---

## 7. Infrastructure as Code

### AWS CDK (TypeScript)

```typescript
import * as cdk from "aws-cdk-lib";
import * as ecs from "aws-cdk-lib/aws-ecs";
import * as ecsPatterns from "aws-cdk-lib/aws-ecs-patterns";
import * as sqs from "aws-cdk-lib/aws-sqs";

export class ApiStack extends cdk.Stack {
  constructor(scope: cdk.App, id: string) {
    super(scope, id);

    const dlq = new sqs.Queue(this, "DLQ", { retentionPeriod: cdk.Duration.days(14) });
    const queue = new sqs.Queue(this, "OrderQ", {
      deadLetterQueue: { queue: dlq, maxReceiveCount: 3 },
    });

    const service = new ecsPatterns.ApplicationLoadBalancedFargateService(this, "Api", {
      cpu: 512, memoryLimitMiB: 1024, desiredCount: 2,
      taskImageOptions: {
        image: ecs.ContainerImage.fromAsset("./"),
        containerPort: 3000,
        environment: { NODE_OPTIONS: "--max-old-space-size=768", SQS_QUEUE_URL: queue.queueUrl },
      },
    });
    queue.grantSendMessages(service.taskDefinition.taskRole);

    const scaling = service.service.autoScaleTaskCount({ minCapacity: 2, maxCapacity: 10 });
    scaling.scaleOnCpuUtilization("Cpu", { targetUtilizationPercent: 60 });
  }
}
```

### CDK vs Terraform vs Pulumi

| Aspect | AWS CDK | Terraform | Pulumi |
|--------|---------|-----------|--------|
| Language | TypeScript, Python | HCL | TypeScript, Go |
| Cloud | AWS only | Multi-cloud | Multi-cloud |
| State | CloudFormation | `.tfstate` (must manage) | Pulumi Cloud |
| Abstractions | L2/L3 constructs | Modules | npm packages |

### CI/CD Pipeline

```
┌──────┐   ┌──────┐   ┌───────┐   ┌─────────┐   ┌──────────┐
│ Lint │ → │ Test │ → │ Build │ → │ Deploy  │ → │ Deploy   │
│      │   │      │   │ Image │   │ Staging │   │ Prod     │
└──────┘   └──────┘   └───────┘   └─────────┘   │ (canary) │
                                                  └──────────┘
```

```yaml
# .github/workflows/deploy.yml (key steps)
jobs:
  test:
    steps:
      - run: npm ci && npm run lint && npm run typecheck && npm test
  build:
    needs: test
    steps:
      - run: docker build -t $ECR/order-svc:${{ github.sha }} . && docker push ...
  deploy-staging:
    needs: build
    environment: staging
    steps:
      - run: aws ecs update-service --cluster staging --service order-api --force-new-deployment
  deploy-prod:
    needs: deploy-staging
    environment: production    # Manual approval gate
```

### Feature Flags & Progressive Rollout

```typescript
class FeatureFlagService {
  constructor(private flags: Map<string, { enabled: boolean; percentage?: number }>) {}

  isEnabled(key: string, userId?: string): boolean {
    const flag = this.flags.get(key);
    if (!flag?.enabled) return false;
    if (flag.percentage !== undefined && userId) {
      const hash = [...userId + key].reduce((h, c) => ((h << 5) - h + c.charCodeAt(0)) | 0, 0);
      return (Math.abs(hash) % 100) < flag.percentage;
    }
    return true;
  }
}

// Progressive: 5% → 25% → 50% → 100% over days. Kill switch: set to 0%.
```

---

## 8. Common Senior Interview Questions

**Q: How do you design a Node.js app for Kubernetes?**
Multi-stage Dockerfile, `--max-old-space-size` at 75% of memory limit. Liveness probe (process alive), readiness probe (dependencies healthy). Handle SIGTERM: stop accepting connections, drain in-flight requests, close pools, exit. Non-root user. Memory-based HPA because Node.js is single-threaded and I/O-bound. JSON logs to stdout.

**Q: Kafka vs SQS vs RabbitMQ?**
Kafka: event streaming, replay, high throughput, ordering per partition, multiple independent consumer groups. SQS: managed job queue, dead-letter queues, no replay. SQS FIFO for ordering (<3k msg/s). RabbitMQ: complex routing (topic/header exchanges), flexible acks, on-premises. All require idempotent consumers.

**Q: How do you handle Lambda cold starts?**
First ask: does it matter? (Not for async SQS processing.) If yes: Provisioned Concurrency, bundle with esbuild (single file), initialize SDK clients at module scope, lazy DB connections, minimal dependencies. For extreme latency requirements, consider Fargate with ALB instead.

**Q: Reliable event publishing in microservices?**
Outbox pattern: write event to outbox table in same DB transaction as business data. Publish asynchronously via CDC (Debezium) or polling. Avoids dual-write problem. Consumers must be idempotent (dedup by message ID in Redis with TTL).

**Q: CDK vs Terraform?**
CDK for AWS-only TypeScript teams: type safety, IDE autocomplete, L2/L3 constructs. Terraform for multi-cloud: larger community, more providers, simpler plan/apply. Either way: version-controlled, peer-reviewed, CI/CD deployed -- never manual.
