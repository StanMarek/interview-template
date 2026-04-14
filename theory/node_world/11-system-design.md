# System Design for Node.js — Senior Engineer Interview Preparation

---

## 1. Node.js Architecture Considerations

### The Event Loop: Strengths and Limitations

Node.js runs on a **single-threaded event loop** backed by libuv. Every I/O operation is non-blocking and delegated to the OS or the libuv thread pool, while the event loop processes callbacks on a single V8 thread.

```
   ┌───────────────────────────────────┐
   │           Event Loop              │
   │  ┌─────────────────────────────┐  │
   │  │ 1. timers (setTimeout)      │  │
   │  │ 2. pending I/O callbacks    │  │
   │  │ 3. idle / prepare           │  │
   │  │ 4. poll (incoming I/O)      │  │
   │  │ 5. check (setImmediate)     │  │
   │  │ 6. close callbacks          │  │
   │  └─────────────────────────────┘  │
   │                                   │
   │  microtask queues run between     │
   │  EVERY phase transition:          │
   │  - process.nextTick() queue       │
   │  - Promise microtask queue        │
   └───────────────────────────────────┘
           │              ▲
           ▼              │
   ┌──────────────────────────────────┐
   │    libuv thread pool (4 default) │
   │   - fs operations                │
   │   - DNS lookups                  │
   │   - crypto operations            │
   │   - zlib compression             │
   └──────────────────────────────────┘
```

**Key implication**: Any CPU-bound work on the main thread blocks ALL concurrent connections. A 50ms synchronous JSON parse for one request delays every other request by 50ms.

### When Node.js Is the RIGHT Choice

| Use Case | Why Node.js Excels |
|----------|-------------------|
| REST/GraphQL API servers | Non-blocking I/O handles thousands of concurrent connections with low memory |
| Real-time applications | WebSocket-native, event-driven architecture maps naturally to push-based systems |
| API gateways / BFF layers | Excellent at orchestrating multiple downstream service calls concurrently |
| Streaming / data pipelines | Native `Stream` API enables backpressure-aware data processing |
| Microservices | Fast startup time (<100ms), small memory footprint (~30MB base), fast iteration |
| SSR for React/Next.js | Same language for server and client, shared validation, isomorphic rendering |
| CLI tools and build systems | npm ecosystem, fast scripting, cross-platform compatibility |

### When Node.js Is the WRONG Choice

| Use Case | Why Node.js Struggles | Better Alternatives |
|----------|----------------------|-------------------|
| CPU-heavy computation (video encoding, ML inference) | Blocks the event loop | Go, Rust, Python (with C extensions) |
| Bare-metal performance-critical systems | V8 JIT is fast but not C/Rust fast | Rust, C++ |
| Heavy parallel computation | Single-threaded by default, worker_threads have overhead | Go (goroutines), Java (virtual threads), Rust (Rayon) |
| Large monolithic enterprise apps | Lack of strong typing at runtime, dynamic nature makes large codebases fragile | Java/Spring, C#/.NET |

**Nuance for senior interviews**: Node.js can handle CPU work using `worker_threads`, but the overhead of serializing data between threads makes it unsuitable for fine-grained parallelism. It works for coarse-grained tasks like image processing pipelines where you hand off a complete job.

### Scaling Strategies: Vertical vs Horizontal

```
Vertical Scaling                    Horizontal Scaling
┌──────────────┐                   ┌────────────┐
│  Single Node │                   │   LB/Proxy │
│  32 cores    │                   └─────┬──────┘
│  128GB RAM   │                    ┌────┼────┐
│  Cluster mode│                    ▼    ▼    ▼
│  (32 workers)│                   ┌──┐ ┌──┐ ┌──┐
└──────────────┘                   │N1│ │N2│ │N3│  (each running cluster)
                                   └──┘ └──┘ └──┘
Limit: single machine              Limit: state management,
ceiling, SPOF                       network complexity
```

### Process-per-Core Strategies

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| `cluster` module | Master process forks N workers, shares a TCP port | Built-in, zero dependencies | Manual health monitoring, no log aggregation |
| PM2 | Process manager: cluster mode, auto-restart, monitoring | Production-ready, `pm2 monit`, ecosystem file | Extra runtime dependency, PM2 daemon overhead |
| Container orchestration (K8s) | One process per container, horizontal pod autoscaler | Industry standard, health checks, rolling deploys | Operational complexity, learning curve |
| Systemd + Nginx | OS-level process management with reverse proxy | Lightweight, no extra runtime | Manual scaling, limited orchestration |

```typescript
// cluster module — process-per-core
import cluster from "node:cluster";
import { availableParallelism } from "node:os";
import { createServer } from "node:http";

const numCPUs = availableParallelism();

if (cluster.isPrimary) {
  console.log(`Primary ${process.pid} forking ${numCPUs} workers`);

  for (let i = 0; i < numCPUs; i++) {
    cluster.fork();
  }

  cluster.on("exit", (worker, code, signal) => {
    console.log(`Worker ${worker.process.pid} died (${signal || code}). Restarting...`);
    cluster.fork(); // auto-restart on crash
  });
} else {
  createServer((req, res) => {
    res.writeHead(200);
    res.end(`Handled by worker ${process.pid}\n`);
  }).listen(3000);

  console.log(`Worker ${process.pid} started`);
}
```

**Senior insight**: In containerized environments (K8s), you typically run **one process per container** and let the orchestrator handle scaling. Running cluster mode inside a container complicates health checks and defeats the purpose of container-level isolation. The exception is when you want to maximize a large VM without containerization.

---

## 2. Scaling Patterns

### Horizontal Scaling with Stateless Services

The golden rule: **any instance should be able to handle any request**. This means:

- No in-memory sessions (use Redis or JWTs)
- No local file storage (use S3/GCS)
- No in-process cron jobs (use a distributed scheduler)
- Configuration from environment variables, not local files

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│  Client  │────▶│ Load Balancer│────▶│  Node App x3 │──── Stateless
└──────────┘     └──────────────┘     └──────┬───────┘
                                             │
                               ┌─────────────┼─────────────┐
                               ▼             ▼             ▼
                         ┌──────────┐  ┌──────────┐  ┌──────────┐
                         │  Redis   │  │ Postgres │  │    S3    │
                         │ (session │  │ (data)   │  │ (files)  │
                         │  + cache)│  │          │  │          │
                         └──────────┘  └──────────┘  └──────────┘
                               Shared State Layer
```

### Session Management

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| Sticky sessions | LB routes same client to same server (cookie/IP) | Simple, in-memory sessions work | Uneven load, session lost on server crash |
| Centralized store (Redis) | All servers read/write sessions to Redis | True stateless servers, survives restarts | Extra hop (~1ms), Redis becomes dependency |
| JWT (stateless) | Token contains all session data, signed by server | No server-side storage, scales infinitely | Can't revoke easily, token size grows, payload visible |
| JWT + Redis blacklist | JWT for auth, Redis for revocation list | Best of both: stateless + revocable | Still need Redis, two-layer logic |

```typescript
// Express session with Redis store
import session from "express-session";
import RedisStore from "connect-redis";
import { createClient } from "redis";

const redisClient = createClient({ url: "redis://redis:6379" });
await redisClient.connect();

app.use(
  session({
    store: new RedisStore({ client: redisClient, prefix: "sess:" }),
    secret: process.env.SESSION_SECRET!,
    resave: false,
    saveUninitialized: false,
    cookie: {
      secure: true,
      httpOnly: true,
      maxAge: 1000 * 60 * 60 * 24, // 24 hours
      sameSite: "strict",
    },
  })
);
```

### Database Connection Pooling in Scaled Environments

A critical problem: 10 Node.js instances each opening 20 connections = 200 connections to Postgres. Scale to 50 instances and you hit the default `max_connections = 100` wall.

```
Without PgBouncer:                 With PgBouncer:
50 Node instances                  50 Node instances
x 20 connections each              x 20 connections each
= 1000 DB connections (BREAKS)     = 1000 app-side connections
                                         |
                                   ┌─────▼──────┐
                                   │  PgBouncer  │  transaction pooling
                                   │  (pool: 50) │
                                   └─────┬──────┘
                                         |
                                   50 actual DB connections (WORKS)
```

```typescript
// Prisma with connection pooling configuration
// schema.prisma
// datasource db {
//   provider = "postgresql"
//   url      = env("DATABASE_URL")  // ?connection_limit=5&pool_timeout=10
// }

// Application-level pooling with node-postgres
import pg from "pg";

const pool = new pg.Pool({
  host: process.env.DB_HOST,
  database: process.env.DB_NAME,
  max: 10,                  // max connections per Node process
  idleTimeoutMillis: 30000, // close idle connections after 30s
  connectionTimeoutMillis: 5000,
});

// Always release connections back to the pool
const client = await pool.connect();
try {
  const result = await client.query("SELECT * FROM orders WHERE id = $1", [orderId]);
  return result.rows[0];
} finally {
  client.release(); // CRITICAL: always release, even on error
}
```

### Load Balancing Algorithms

| Algorithm | Description | Best For |
|-----------|-------------|----------|
| Round Robin | Requests distributed sequentially | Homogeneous servers, stateless apps |
| Least Connections | Route to server with fewest active connections | Varying request durations (long-polling, WebSocket) |
| IP Hash | Hash client IP to consistently route to same server | Sticky sessions without cookies |
| Weighted Round Robin | Servers assigned different weights | Heterogeneous hardware |
| Random with Two Choices | Pick 2 random servers, choose the less loaded one | Simple yet effective load distribution |

### Reverse Proxy Configuration (Nginx)

```nginx
upstream node_app {
    least_conn;                         # Least connections algorithm
    server 10.0.0.1:3000 weight=3;     # More powerful server
    server 10.0.0.2:3000 weight=1;
    server 10.0.0.3:3000 weight=1;
    keepalive 64;                       # Keep connections alive to upstream
}

server {
    listen 80;
    server_name api.example.com;

    location / {
        proxy_pass http://node_app;
        proxy_http_version 1.1;
        proxy_set_header Connection "";          # Enable keepalive
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts
        proxy_connect_timeout 5s;
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
    }

    # WebSocket support
    location /ws {
        proxy_pass http://node_app;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

### WebSocket Scaling with Redis Pub/Sub

```
                    ┌────────────┐
                    │  Redis     │
                    │  Pub/Sub   │
                    └──┬──┬──┬──┘
                 ┌─────┘  │  └─────┐
                 ▼        ▼        ▼
              ┌──────┐ ┌──────┐ ┌──────┐
              │Node 1│ │Node 2│ │Node 3│
              │WS: A │ │WS: B │ │WS: C │   User A connected to Node 1
              │   D  │ │   E  │ │   F  │   User B connected to Node 2
              └──────┘ └──────┘ └──────┘

When User A sends message to User B:
1. Node 1 receives message from User A
2. Node 1 publishes to Redis channel
3. Redis broadcasts to all subscribed Node instances
4. Node 2 receives broadcast, delivers to User B
```

### Auto-Scaling Policies

| Metric | Threshold | Scale Action | Cooldown |
|--------|-----------|-------------|----------|
| CPU utilization | > 70% for 2 min | Scale out +2 instances | 3 min |
| Memory usage | > 80% for 3 min | Scale out +1 instance | 5 min |
| Request count | > 10K RPM per instance | Scale out +1 instance | 2 min |
| Response latency (p99) | > 500ms for 5 min | Scale out +2 instances | 3 min |
| Event loop lag | > 100ms | Scale out (Node-specific!) | 2 min |
| Queue depth | > 1000 messages | Scale out consumers | 1 min |

**Node-specific metric**: Event loop lag is the most meaningful scaling signal for Node.js. High event loop lag means the main thread is saturated, even if CPU averages look low (because the other cores are idle).

```typescript
// Monitoring event loop lag
import { monitorEventLoopDelay } from "node:perf_hooks";

const histogram = monitorEventLoopDelay({ resolution: 20 });
histogram.enable();

setInterval(() => {
  const lagMs = histogram.mean / 1e6; // nanoseconds to milliseconds
  console.log(
    `Event loop lag: p50=${(histogram.percentile(50) / 1e6).toFixed(1)}ms, ` +
    `p99=${(histogram.percentile(99) / 1e6).toFixed(1)}ms, ` +
    `max=${(histogram.max / 1e6).toFixed(1)}ms`
  );
  histogram.reset();

  // Expose to Prometheus for auto-scaling decisions
  eventLoopLagGauge.set(lagMs);
}, 5000);
```

---

## 3. Caching Strategies

### Multi-Tier Caching Architecture

```
┌────────┐    ┌────────┐    ┌──────────────┐    ┌──────────┐    ┌────────┐
│ Browser│───▶│  CDN   │───▶│ Reverse Proxy│───▶│ App Cache│───▶│   DB   │
│ Cache  │    │ (Edge) │    │(Nginx/Varnish)│   │ (Redis)  │    │        │
└────────┘    └────────┘    └──────────────┘    └──────────┘    └────────┘
  ~0ms          ~5ms            ~1ms                ~1ms          ~5-50ms

  HTTP Cache    Static +       Full page or       Query results   Source of
  Headers       API cache      fragment cache      Object cache    truth
```

### In-Process Caching

Best for: frequently accessed, small datasets that can tolerate staleness across instances.

```typescript
// Using lru-cache (most popular in-process cache for Node.js)
import { LRUCache } from "lru-cache";

const userCache = new LRUCache<string, User>({
  max: 5000,                        // max 5000 entries
  ttl: 1000 * 60 * 5,               // 5 minute TTL
  maxSize: 50 * 1024 * 1024,        // 50MB max memory
  sizeCalculation: (value) => JSON.stringify(value).length,
  allowStale: true,                  // return stale while revalidating
  updateAgeOnGet: true,              // reset TTL on access
  fetchMethod: async (userId) => {
    // Built-in coalescing: concurrent fetches for same key
    // trigger only ONE actual fetch
    return await db.users.findUnique({ where: { id: userId } });
  },
});

// Usage — automatically fetches on miss
const user = await userCache.fetch(userId);
```

**Warning**: In-process caches create consistency problems in horizontally scaled environments. Node 1 may have a stale cached version while Node 2 has the updated value. Acceptable for read-heavy, staleness-tolerant data (user profiles, product catalogs) but dangerous for financial data or inventory counts.

### Distributed Caching (Redis)

```typescript
import { createClient } from "redis";

const redis = createClient({ url: "redis://redis-cluster:6379" });
await redis.connect();

// Cache-aside pattern implementation
async function getUserWithCache(userId: string): Promise<User> {
  const cacheKey = `user:${userId}`;

  // 1. Try cache first
  const cached = await redis.get(cacheKey);
  if (cached) {
    return JSON.parse(cached);
  }

  // 2. Cache miss — fetch from database
  const user = await db.users.findUnique({ where: { id: userId } });
  if (!user) throw new NotFoundError("User not found");

  // 3. Populate cache with TTL
  await redis.setEx(cacheKey, 300, JSON.stringify(user)); // 5 min TTL

  return user;
}

// Invalidate on write
async function updateUser(userId: string, data: Partial<User>): Promise<User> {
  const user = await db.users.update({ where: { id: userId }, data });
  await redis.del(`user:${userId}`);  // Invalidate cache
  return user;
}
```

### Cache Patterns Comparison

| Pattern | Flow | Pros | Cons | Use Case |
|---------|------|------|------|----------|
| Cache-Aside | App checks cache, fetches DB on miss, writes to cache | Simple, app controls logic | Cache miss penalty, possible stale data | General purpose, most common |
| Read-Through | Cache itself fetches from DB on miss | Simpler app code, guaranteed consistency | Cache library must support it | ORM-level caching |
| Write-Through | App writes to cache, cache writes to DB synchronously | Cache always consistent | Write latency (two writes) | Strong consistency needed |
| Write-Behind | App writes to cache, cache writes to DB asynchronously | Fast writes, batching possible | Risk of data loss if cache crashes | High write throughput |
| Refresh-Ahead | Cache proactively refreshes before TTL expires | No cache miss latency | Wasted refreshes for unused keys | Predictable access patterns |

### Cache Invalidation Strategies

| Strategy | Mechanism | Consistency | Complexity |
|----------|-----------|-------------|------------|
| TTL-based | Set expiry on cache entry | Eventual (up to TTL) | Low |
| Event-based | Publish invalidation event on write | Near real-time | Medium |
| Version-based | Append version to cache key (`user:123:v7`) | Strong (new key = new data) | Medium |
| Write-through | Update cache and DB atomically | Strong | High |
| Pub/Sub broadcast | Redis pub/sub to invalidate across nodes | Near real-time | Medium |

```typescript
// Event-based invalidation with Redis Pub/Sub
const subscriber = redis.duplicate();
await subscriber.connect();

// Publisher side (on data change)
async function invalidateUserCache(userId: string): Promise<void> {
  await redis.del(`user:${userId}`);
  await redis.publish("cache-invalidation", JSON.stringify({
    type: "user",
    id: userId,
    timestamp: Date.now(),
  }));
}

// Subscriber side (every Node instance subscribes)
await subscriber.subscribe("cache-invalidation", (message) => {
  const event = JSON.parse(message);
  // Invalidate in-process LRU cache too
  localCache.delete(`${event.type}:${event.id}`);
});
```

### HTTP Caching Headers

```typescript
// Express middleware for HTTP caching
app.get("/api/products/:id", async (req, res) => {
  const product = await getProduct(req.params.id);

  // ETag for conditional requests
  const etag = crypto
    .createHash("md5")
    .update(JSON.stringify(product))
    .digest("hex");

  // Check If-None-Match
  if (req.headers["if-none-match"] === etag) {
    return res.status(304).end(); // Not Modified
  }

  res.set({
    "Cache-Control": "public, max-age=60, stale-while-revalidate=300",
    "ETag": etag,
    "Vary": "Accept-Encoding, Authorization",
  });

  res.json(product);
});

// Static assets — aggressive caching with content hash in filename
app.use("/static", express.static("dist", {
  maxAge: "1y",           // Cache for 1 year (files have content hash)
  immutable: true,        // Tell browser this will NEVER change
}));
```

| Header | Purpose | Example |
|--------|---------|---------|
| `Cache-Control` | Primary caching directive | `public, max-age=3600, stale-while-revalidate=60` |
| `ETag` | Content fingerprint for conditional requests | `"a1b2c3d4"` |
| `Last-Modified` | Timestamp-based conditional requests | `Wed, 04 Apr 2026 12:00:00 GMT` |
| `Vary` | Cache varies by these request headers | `Accept-Encoding, Authorization` |
| `stale-while-revalidate` | Serve stale content while fetching fresh | Included in `Cache-Control` |
| `stale-if-error` | Serve stale content if origin errors | Included in `Cache-Control` |

---

## 4. Real-Time Communication

### Protocol Comparison

| Protocol | Direction | Connection | Overhead | Best For |
|----------|-----------|-----------|----------|----------|
| WebSocket | Bidirectional | Persistent TCP | Very low (2 bytes framing) | Chat, gaming, collaborative editing |
| Server-Sent Events (SSE) | Server to Client only | Persistent HTTP | Low (text/event-stream) | Notifications, live feeds, dashboards |
| Long Polling | Simulated push | Repeated HTTP requests | High (new connection each time) | Fallback when WebSocket/SSE unavailable |
| HTTP/2 Server Push | Server to Client | Within HTTP/2 connection | Medium | Preloading assets (deprecated in Chrome) |

### WebSockets with `ws` Library

```typescript
import { WebSocketServer, WebSocket } from "ws";
import { createServer } from "node:http";

const server = createServer();
const wss = new WebSocketServer({ server });

// Connection management
const clients = new Map<string, WebSocket>();

wss.on("connection", (ws, req) => {
  const userId = authenticateFromHeaders(req.headers);
  clients.set(userId, ws);

  ws.on("message", (data) => {
    const message = JSON.parse(data.toString());
    handleMessage(userId, message);
  });

  ws.on("close", () => {
    clients.delete(userId);
  });

  // Heartbeat to detect dead connections
  (ws as any).isAlive = true;
  ws.on("pong", () => { (ws as any).isAlive = true; });
});

// Ping all clients every 30 seconds, terminate dead ones
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!(ws as any).isAlive) return ws.terminate();
    (ws as any).isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on("close", () => clearInterval(heartbeat));
server.listen(3000);
```

### Server-Sent Events (SSE)

SSE is simpler than WebSockets and works over standard HTTP. Use when you only need server-to-client streaming.

```typescript
// SSE endpoint — native Node.js (no library needed)
app.get("/api/events", (req, res) => {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",   // Disable Nginx buffering
  });

  // Send initial connection event
  res.write("event: connected\ndata: {\"status\":\"ok\"}\n\n");

  // Subscribe to events
  const onNotification = (data: string) => {
    res.write(`event: notification\ndata: ${data}\n\n`);
  };

  eventEmitter.on("notification", onNotification);

  // Client disconnect cleanup
  req.on("close", () => {
    eventEmitter.off("notification", onNotification);
  });
});
```

**When to use SSE over WebSocket**:
- Server-to-client only (notifications, live scores, stock tickers)
- You want automatic reconnection (built into EventSource API)
- You need to work through HTTP/2 without special proxy config
- Simpler debugging (it is just HTTP with text/event-stream)

**When WebSocket is better**:
- Bidirectional (chat, gaming)
- Binary data (file transfer, audio/video signaling)
- High-frequency messages where HTTP overhead matters

### Scaling WebSockets Across Multiple Servers

```typescript
// Socket.IO with Redis adapter
import { Server } from "socket.io";
import { createAdapter } from "@socket.io/redis-adapter";
import { createClient } from "redis";

const pubClient = createClient({ url: "redis://redis:6379" });
const subClient = pubClient.duplicate();
await Promise.all([pubClient.connect(), subClient.connect()]);

const io = new Server(httpServer, {
  adapter: createAdapter(pubClient, subClient),
  cors: { origin: "https://app.example.com" },
});

io.on("connection", (socket) => {
  // Join a room — works across ALL server instances via Redis
  socket.join(`user:${socket.data.userId}`);
  socket.join(`org:${socket.data.orgId}`);
});

// Emit to a room — Redis adapter broadcasts to all servers
// that have sockets in this room
io.to("org:acme-corp").emit("notification", {
  type: "new-message",
  payload: { text: "Hello everyone" },
});
```

### GraphQL Subscriptions

```typescript
// GraphQL subscriptions with graphql-ws
import { createServer } from "node:http";
import { WebSocketServer } from "ws";
import { useServer } from "graphql-ws/lib/use/ws";
import { schema } from "./schema";
import { RedisPubSub } from "graphql-redis-subscriptions";

const pubsub = new RedisPubSub({
  publisher: createClient({ url: "redis://redis:6379" }),
  subscriber: createClient({ url: "redis://redis:6379" }),
});

// In resolvers
const resolvers = {
  Subscription: {
    messageAdded: {
      subscribe: (_: unknown, args: { channelId: string }) => {
        return pubsub.asyncIterator(`CHANNEL_${args.channelId}`);
      },
    },
  },
  Mutation: {
    sendMessage: async (_: unknown, args: { channelId: string; text: string }) => {
      const message = await db.messages.create({ data: args });
      await pubsub.publish(`CHANNEL_${args.channelId}`, {
        messageAdded: message,
      });
      return message;
    },
  },
};
```

### Message Ordering and Delivery Guarantees

| Guarantee | Implementation | Trade-off |
|-----------|---------------|-----------|
| At-most-once | Fire and forget, no acks | Fast but messages can be lost |
| At-least-once | Acknowledge after processing, retry on timeout | Duplicates possible, use idempotency keys |
| Exactly-once | At-least-once + idempotent consumer (dedup by message ID) | Slowest, most complex, required for financial ops |
| Ordered delivery | Sequence numbers per channel, client-side reordering buffer | Added latency for out-of-order messages |

```typescript
// Client-side message ordering with sequence numbers
class OrderedMessageBuffer {
  private buffer = new Map<number, Message>();
  private nextExpected = 0;

  process(msg: Message): Message[] {
    this.buffer.set(msg.sequence, msg);

    const ordered: Message[] = [];
    while (this.buffer.has(this.nextExpected)) {
      ordered.push(this.buffer.get(this.nextExpected)!);
      this.buffer.delete(this.nextExpected);
      this.nextExpected++;
    }

    return ordered; // Returns all messages that can be delivered in order
  }
}
```

---

## 5. API Gateway Patterns

### Gateway Architecture

```
┌─────────┐  ┌─────────┐  ┌─────────┐
│  Web    │  │  Mobile │  │  3rd    │
│  SPA    │  │  App    │  │  Party  │
└────┬────┘  └────┬────┘  └────┬────┘
     │            │            │
     ▼            ▼            ▼
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Web BFF │  │ Mobile  │  │ Public  │
│ (Next.js│  │ BFF     │  │ API GW  │
│  API)   │  │ (lean)  │  │ (rate   │
└────┬────┘  └────┬────┘  │ limited)│
     │            │       └────┬────┘
     └────────────┼────────────┘
                  ▼
        ┌──────────────────┐
        │  Internal Service│
        │  Mesh / API GW   │
        ├──────────────────┤
        │ - Auth validation│
        │ - Rate limiting  │
        │ - Request routing│
        │ - Circuit breaker│
        │ - Logging/tracing│
        └───────┬──────────┘
      ┌─────────┼─────────┐
      ▼         ▼         ▼
  ┌────────┐ ┌────────┐ ┌────────┐
  │ User   │ │ Order  │ │ Payment│
  │ Service│ │ Service│ │ Service│
  └────────┘ └────────┘ └────────┘
```

### BFF (Backend for Frontend)

The BFF pattern creates a dedicated backend for each frontend. Each BFF is owned by the frontend team and tailors responses to that client's needs.

```typescript
// Mobile BFF — lean responses, minimal payload
app.get("/api/mobile/feed", async (req, res) => {
  const [posts, notifications] = await Promise.all([
    postService.getRecentPosts({ limit: 10, fields: ["id", "title", "thumbnail"] }),
    notificationService.getUnreadCount(req.userId),
  ]);

  // Mobile-optimized: small payloads, combined endpoints
  res.json({
    posts: posts.map((p) => ({
      id: p.id,
      title: p.title,
      thumb: p.thumbnail?.small,  // Smallest image variant
    })),
    unreadNotifications: notifications.count,
  });
});

// Web BFF — richer responses, more data
app.get("/api/web/feed", async (req, res) => {
  const [posts, notifications, trending, suggestions] = await Promise.all([
    postService.getRecentPosts({ limit: 20, fields: ["*"] }),
    notificationService.getRecent(req.userId, { limit: 5 }),
    trendingService.getTopics({ limit: 10 }),
    suggestionService.getForUser(req.userId),
  ]);

  // Web-optimized: richer data, sidebar content included
  res.json({ posts, notifications, trending, suggestions });
});
```

### Rate Limiting at the Gateway

```typescript
// Sliding window rate limiter with Redis
import { createClient } from "redis";

const redis = createClient({ url: "redis://redis:6379" });

async function slidingWindowRateLimit(
  key: string,
  limit: number,
  windowMs: number
): Promise<{ allowed: boolean; remaining: number; retryAfter?: number }> {
  const now = Date.now();
  const windowStart = now - windowMs;

  // Atomic operation using Redis pipeline
  const multi = redis.multi();
  multi.zRemRangeByScore(key, 0, windowStart);  // Remove expired entries
  multi.zCard(key);                               // Count current entries
  multi.zAdd(key, { score: now, value: `${now}:${Math.random()}` });
  multi.pExpire(key, windowMs);                   // Set key expiry

  const results = await multi.exec();
  const currentCount = results![1] as number;

  if (currentCount >= limit) {
    // Over limit — remove the entry we just added
    await redis.zRemRangeByRank(key, -1, -1);
    const oldestInWindow = await redis.zRangeWithScores(key, 0, 0);
    const retryAfter = oldestInWindow.length > 0
      ? Math.ceil((oldestInWindow[0].score + windowMs - now) / 1000)
      : 1;

    return { allowed: false, remaining: 0, retryAfter };
  }

  return { allowed: true, remaining: limit - currentCount - 1 };
}

// Express middleware
async function rateLimitMiddleware(
  req: Request,
  res: Response,
  next: NextFunction
) {
  const key = `ratelimit:${req.ip}`;
  const result = await slidingWindowRateLimit(key, 100, 60_000); // 100 req/min

  res.set({
    "X-RateLimit-Limit": "100",
    "X-RateLimit-Remaining": String(result.remaining),
  });

  if (!result.allowed) {
    res.set("Retry-After", String(result.retryAfter));
    return res.status(429).json({ error: "Rate limit exceeded" });
  }

  next();
}
```

### GraphQL Federation with Apollo

```typescript
// Subgraph — User service
import { buildSubgraphSchema } from "@apollo/subgraph";
import { gql } from "graphql-tag";

const typeDefs = gql`
  extend schema @link(url: "https://specs.apollo.dev/federation/v2.0",
                      import: ["@key"])

  type User @key(fields: "id") {
    id: ID!
    name: String!
    email: String!
  }

  type Query {
    me: User
  }
`;

// Subgraph — Order service (references User)
const orderTypeDefs = gql`
  extend schema @link(url: "https://specs.apollo.dev/federation/v2.0",
                      import: ["@key"])

  type User @key(fields: "id") {
    id: ID!
    orders: [Order!]!    # Extend User type with orders
  }

  type Order @key(fields: "id") {
    id: ID!
    total: Float!
    status: OrderStatus!
  }
`;

// Apollo Router (Rust-based, replaces Apollo Gateway)
// router.yaml configuration:
// supergraph:
//   listen: 0.0.0.0:4000
// subgraphs:
//   users:
//     routing_url: http://user-service:4001/graphql
//   orders:
//     routing_url: http://order-service:4002/graphql
```

### tRPC for End-to-End Type Safety

```typescript
// Server — define procedures with full type inference
import { initTRPC, TRPCError } from "@trpc/server";
import { z } from "zod";

const t = initTRPC.context<Context>().create();

const appRouter = t.router({
  user: t.router({
    getById: t.procedure
      .input(z.object({ id: z.string().uuid() }))
      .query(async ({ input, ctx }) => {
        const user = await ctx.db.users.findUnique({
          where: { id: input.id },
        });
        if (!user) throw new TRPCError({ code: "NOT_FOUND" });
        return user;  // Return type is automatically inferred
      }),

    update: t.procedure
      .input(z.object({
        id: z.string().uuid(),
        name: z.string().min(1).max(100).optional(),
        email: z.string().email().optional(),
      }))
      .mutation(async ({ input, ctx }) => {
        return ctx.db.users.update({
          where: { id: input.id },
          data: input,
        });
      }),
  }),
});

export type AppRouter = typeof appRouter; // Export type for client

// Client — full type safety, zero codegen
import { createTRPCClient, httpBatchLink } from "@trpc/client";
import type { AppRouter } from "../server/router";

const trpc = createTRPCClient<AppRouter>({
  links: [httpBatchLink({ url: "http://localhost:3000/trpc" })],
});

// Fully typed — IDE autocomplete, compile-time errors
const user = await trpc.user.getById.query({ id: "abc-123" });
// user.name — TypeScript knows the shape
```

---

## 6. Database Design at Scale

### Read Replicas and Write Splitting

```
                    ┌──────────────┐
                    │   App Layer  │
                    └───┬──────┬───┘
                  writes│      │reads
                        ▼      ▼
              ┌──────────┐  ┌──────────────────┐
              │ Primary  │  │  Read Replicas    │
              │ (Writer) │──│  Replica 1        │
              │          │  │  Replica 2        │
              └──────────┘  │  Replica 3        │
               replication  └──────────────────┘
               (async)       Load balanced reads
```

```typescript
// Prisma read replica setup
import { PrismaClient } from "@prisma/client";

const writePrisma = new PrismaClient({
  datasources: { db: { url: process.env.DATABASE_PRIMARY_URL } },
});

const readPrisma = new PrismaClient({
  datasources: { db: { url: process.env.DATABASE_REPLICA_URL } },
});

// Service layer — explicit read/write separation
class OrderService {
  async createOrder(data: CreateOrderInput): Promise<Order> {
    return writePrisma.order.create({ data }); // Always goes to primary
  }

  async getOrder(id: string): Promise<Order | null> {
    return readPrisma.order.findUnique({ where: { id } }); // Goes to replica
  }

  async getOrderAfterWrite(id: string): Promise<Order | null> {
    // Read-your-own-writes: use primary immediately after write
    return writePrisma.order.findUnique({ where: { id } });
  }
}
```

### Sharding Strategies

| Strategy | How It Works | Pros | Cons |
|----------|-------------|------|------|
| Hash-based | `shard = hash(key) % N` | Even distribution | Resharding requires data migration |
| Range-based | `shard = range(key)` (e.g., A-M, N-Z) | Easy range queries | Hotspots on popular ranges |
| Geographic | Shard by user region | Data locality, compliance (GDPR) | Cross-region queries are expensive |
| Consistent hashing | Hash ring with virtual nodes | Minimal data movement on scale | More complex implementation |

```typescript
// Application-level sharding with consistent hashing
import { createHash } from "node:crypto";

class ShardRouter {
  private shards: Map<string, DatabaseClient> = new Map();

  constructor(shardConfigs: ShardConfig[]) {
    for (const config of shardConfigs) {
      this.shards.set(config.name, new DatabaseClient(config.url));
    }
  }

  getShardForKey(key: string): DatabaseClient {
    const hash = createHash("md5").update(key).digest("hex");
    const shardIndex = parseInt(hash.substring(0, 8), 16) % this.shards.size;
    const shardName = [...this.shards.keys()][shardIndex];
    return this.shards.get(shardName)!;
  }

  async query<T>(
    key: string,
    queryFn: (db: DatabaseClient) => Promise<T>
  ): Promise<T> {
    const shard = this.getShardForKey(key);
    return queryFn(shard);
  }
}

// Usage
const router = new ShardRouter([
  { name: "shard-0", url: "postgres://shard0:5432/db" },
  { name: "shard-1", url: "postgres://shard1:5432/db" },
  { name: "shard-2", url: "postgres://shard2:5432/db" },
]);

const user = await router.query(userId, (db) =>
  db.query("SELECT * FROM users WHERE id = $1", [userId])
);
```

### CQRS (Command Query Responsibility Segregation)

```
Commands (writes)                    Queries (reads)
     │                                    │
     ▼                                    ▼
┌──────────┐                       ┌──────────────┐
│ Command  │                       │ Query Handler│
│ Handler  │                       │              │
└────┬─────┘                       └──────┬───────┘
     │                                    │
     ▼                                    ▼
┌──────────┐     event bus        ┌──────────────┐
│ Write DB │────────────────────▶│  Read DB      │
│ (Postgres│    (projections)     │ (ElasticSearch│
│  normal- │                      │  / Redis /    │
│  ized)   │                      │  Mongo)       │
└──────────┘                      └──────────────┘
```

```typescript
// CQRS implementation pattern in Node.js
interface Command {
  type: string;
  payload: unknown;
  metadata: { userId: string; timestamp: number; correlationId: string };
}

interface Event {
  type: string;
  payload: unknown;
  metadata: { timestamp: number; version: number };
}

// Command side
class OrderCommandHandler {
  async handle(command: Command): Promise<void> {
    switch (command.type) {
      case "CREATE_ORDER": {
        const order = await this.writeDb.orders.create({
          data: command.payload as CreateOrderData,
        });
        await this.eventBus.publish({
          type: "ORDER_CREATED",
          payload: order,
          metadata: { timestamp: Date.now(), version: 1 },
        });
        break;
      }
    }
  }
}

// Query side — projection handler
class OrderProjectionHandler {
  async handleEvent(event: Event): Promise<void> {
    switch (event.type) {
      case "ORDER_CREATED": {
        const data = event.payload as Order;
        // Update denormalized read model optimized for queries
        await this.readDb.orderViews.upsert({
          orderId: data.id,
          customerName: data.customer.name,
          totalAmount: data.total,
          itemCount: data.items.length,
          status: "pending",
        });
        break;
      }
    }
  }
}
```

### Event Sourcing

Instead of storing current state, store every state change as an immutable event.

```typescript
// Event store implementation
interface DomainEvent {
  aggregateId: string;
  type: string;
  payload: Record<string, unknown>;
  version: number;
  timestamp: Date;
}

class EventStore {
  async appendEvents(
    aggregateId: string,
    events: DomainEvent[],
    expectedVersion: number
  ): Promise<void> {
    // Optimistic concurrency: ensure no other writes since we read
    const currentVersion = await this.getCurrentVersion(aggregateId);
    if (currentVersion !== expectedVersion) {
      throw new ConcurrencyError(
        `Expected version ${expectedVersion}, got ${currentVersion}`
      );
    }

    await this.db.events.createMany({
      data: events.map((e, i) => ({
        aggregateId,
        type: e.type,
        payload: e.payload,
        version: expectedVersion + i + 1,
        timestamp: new Date(),
      })),
    });
  }

  async getEvents(aggregateId: string): Promise<DomainEvent[]> {
    return this.db.events.findMany({
      where: { aggregateId },
      orderBy: { version: "asc" },
    });
  }
}

// Rebuild aggregate state from events
class OrderAggregate {
  private state: OrderState = { status: "draft", items: [], total: 0 };

  static async fromEvents(events: DomainEvent[]): Promise<OrderAggregate> {
    const aggregate = new OrderAggregate();
    for (const event of events) {
      aggregate.apply(event);
    }
    return aggregate;
  }

  private apply(event: DomainEvent): void {
    switch (event.type) {
      case "ORDER_CREATED":
        this.state = { ...event.payload, status: "pending" } as OrderState;
        break;
      case "ITEM_ADDED":
        this.state.items.push(event.payload.item as OrderItem);
        this.state.total += (event.payload.item as OrderItem).price;
        break;
      case "ORDER_CANCELLED":
        this.state.status = "cancelled";
        break;
    }
  }
}
```

### Polyglot Persistence

| Data Type | Best Database | Why |
|-----------|--------------|-----|
| User profiles, orders | PostgreSQL | ACID, relational integrity, complex queries |
| Session data, cache | Redis | Sub-ms latency, TTL support, pub/sub |
| Product catalog | MongoDB | Flexible schema, nested documents, text search |
| Activity feed, time series | Cassandra | High write throughput, time-ordered, no single point of failure |
| Search / full text | Elasticsearch | Inverted index, fuzzy matching, aggregations |
| Social graph | Neo4j | Traverse relationships efficiently |
| File/media storage | S3 / GCS | Unlimited scale, cheap, CDN integration |

### CAP Theorem Practical Implications

```
             Consistency
                /\
               /  \
              /    \
             / CP   \
            /systems \
           /          \
          /____________\
         /\            /\
        /  \    CA    /  \
       / AP \systems /    \
      /______\______/______\
  Availability          Partition
                        Tolerance
```

| System | CAP Category | Trade-off |
|--------|-------------|-----------|
| PostgreSQL (single node) | CA | No partition tolerance — single point of failure |
| MongoDB (replica set) | CP | Rejects writes during leader election |
| Cassandra | AP | Eventual consistency, tunable with consistency level |
| Redis Cluster | AP | Async replication, possible data loss on failover |
| CockroachDB | CP | Consistent but higher write latency across regions |
| DynamoDB | AP (default), CP (strong reads) | Configurable per-query consistency |

**Senior interview insight**: In distributed systems, partition tolerance is non-negotiable. The real choice is between CP and AP. Most Node.js services use AP systems (Redis, Cassandra, DynamoDB) for speed and accept eventual consistency, then use compensating mechanisms (reconciliation jobs, conflict resolution) for correctness.

---

## 7. Resilience Patterns

### Circuit Breaker

```
     ┌────────┐  success  ┌────────┐
     │ CLOSED │◀──────────│  HALF  │
     │(normal)│           │  OPEN  │
     └───┬────┘           └───┬────┘
         │ failure             ▲
         │ threshold           │ timer
         │ reached             │ expires
         ▼                     │
     ┌────────┐               │
     │  OPEN  │───────────────┘
     │(reject │  after cooldown,
     │ all)   │  allow 1 probe request
     └────────┘
```

```typescript
// Circuit breaker implementation
enum CircuitState {
  CLOSED = "CLOSED",
  OPEN = "OPEN",
  HALF_OPEN = "HALF_OPEN",
}

class CircuitBreaker {
  private state = CircuitState.CLOSED;
  private failureCount = 0;
  private lastFailureTime = 0;
  private successCount = 0;

  constructor(
    private readonly options: {
      failureThreshold: number;   // Failures before opening
      resetTimeout: number;       // ms before trying half-open
      halfOpenMax: number;        // Successes to close from half-open
    }
  ) {}

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    if (this.state === CircuitState.OPEN) {
      if (Date.now() - this.lastFailureTime > this.options.resetTimeout) {
        this.state = CircuitState.HALF_OPEN;
        this.successCount = 0;
      } else {
        throw new CircuitOpenError("Circuit breaker is OPEN");
      }
    }

    try {
      const result = await fn();

      if (this.state === CircuitState.HALF_OPEN) {
        this.successCount++;
        if (this.successCount >= this.options.halfOpenMax) {
          this.state = CircuitState.CLOSED;
          this.failureCount = 0;
        }
      } else {
        this.failureCount = 0; // Reset on success in CLOSED state
      }

      return result;
    } catch (error) {
      this.failureCount++;
      this.lastFailureTime = Date.now();

      if (this.failureCount >= this.options.failureThreshold) {
        this.state = CircuitState.OPEN;
      }

      throw error;
    }
  }

  getState(): CircuitState {
    return this.state;
  }
}

// Usage
const paymentCircuit = new CircuitBreaker({
  failureThreshold: 5,
  resetTimeout: 30_000,   // 30 seconds
  halfOpenMax: 3,         // 3 successes to fully close
});

async function processPayment(orderId: string): Promise<PaymentResult> {
  return paymentCircuit.execute(() =>
    paymentService.charge(orderId)
  );
}
```

### Bulkhead Pattern

Isolate critical resources so a failure in one area does not cascade to others.

```typescript
// Semaphore-based bulkhead
class Bulkhead {
  private active = 0;
  private queue: Array<{
    resolve: () => void;
    reject: (err: Error) => void;
  }> = [];

  constructor(
    private readonly maxConcurrent: number,
    private readonly maxQueue: number,
    private readonly timeout: number
  ) {}

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    if (this.active >= this.maxConcurrent) {
      if (this.queue.length >= this.maxQueue) {
        throw new BulkheadFullError("Bulkhead queue is full");
      }

      // Wait for a slot
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new BulkheadTimeoutError("Bulkhead wait timeout")),
          this.timeout
        );
        this.queue.push({
          resolve: () => { clearTimeout(timer); resolve(); },
          reject,
        });
      });
    }

    this.active++;
    try {
      return await fn();
    } finally {
      this.active--;
      if (this.queue.length > 0) {
        const next = this.queue.shift()!;
        next.resolve();
      }
    }
  }
}

// Separate bulkheads for different downstream services
const paymentBulkhead = new Bulkhead(10, 50, 5000);
const inventoryBulkhead = new Bulkhead(20, 100, 3000);
const emailBulkhead = new Bulkhead(5, 200, 10000);

// Payment failure will not exhaust connections for inventory
async function checkout(order: Order): Promise<void> {
  const [paymentResult, inventoryResult] = await Promise.all([
    paymentBulkhead.execute(() => paymentService.charge(order)),
    inventoryBulkhead.execute(() => inventoryService.reserve(order.items)),
  ]);

  // Email is non-critical — do not fail checkout if email fails
  emailBulkhead.execute(() =>
    emailService.sendConfirmation(order)
  ).catch(console.error);
}
```

### Retry with Exponential Backoff + Jitter

```typescript
interface RetryOptions {
  maxRetries: number;
  baseDelay: number;       // ms
  maxDelay: number;        // ms cap
  retryableErrors?: (error: Error) => boolean;
}

async function retryWithBackoff<T>(
  fn: () => Promise<T>,
  options: RetryOptions
): Promise<T> {
  let lastError: Error;

  for (let attempt = 0; attempt <= options.maxRetries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error as Error;

      // Do not retry non-retryable errors (4xx, validation, etc.)
      if (options.retryableErrors && !options.retryableErrors(lastError)) {
        throw lastError;
      }

      if (attempt === options.maxRetries) break;

      // Exponential backoff with full jitter
      const exponentialDelay = options.baseDelay * Math.pow(2, attempt);
      const jitteredDelay = Math.random() *
        Math.min(exponentialDelay, options.maxDelay);

      console.log(
        `Retry ${attempt + 1}/${options.maxRetries} ` +
        `after ${jitteredDelay.toFixed(0)}ms`
      );
      await new Promise((resolve) => setTimeout(resolve, jitteredDelay));
    }
  }

  throw lastError!;
}

// Usage
const result = await retryWithBackoff(
  () => fetch("https://api.external-service.com/data").then((r) => {
    if (!r.ok) throw new HttpError(r.status, r.statusText);
    return r.json();
  }),
  {
    maxRetries: 3,
    baseDelay: 200,
    maxDelay: 5000,
    retryableErrors: (err) =>
      err instanceof HttpError && err.status >= 500,
  }
);
```

### Timeout Cascades Prevention

```typescript
// Nested timeouts must DECREASE at each layer
// Otherwise, upstream times out before downstream responds

// Gateway: 10s total budget
// -> Service A: 5s budget
//    -> Database: 2s budget
//    -> External API: 3s budget (with circuit breaker)
// -> Service B: 4s budget

import { AbortController } from "node:abort_controller";

async function withTimeout<T>(
  fn: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
  label: string
): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    return await fn(controller.signal);
  } catch (error) {
    if (controller.signal.aborted) {
      throw new TimeoutError(`${label} timed out after ${timeoutMs}ms`);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

// Usage with decreasing timeouts
async function handleRequest(req: Request): Promise<Response> {
  return withTimeout(async (outerSignal) => {
    const userData = await withTimeout(
      (signal) => userService.getUser(req.userId, { signal }),
      2000,  // 2s for user fetch
      "user-service"
    );

    const recommendations = await withTimeout(
      (signal) => recoService.getForUser(req.userId, { signal }),
      3000,  // 3s for recommendations
      "reco-service"
    );

    return { user: userData, recommendations };
  }, 8000, "request-handler"); // 8s total (leaves margin)
}
```

### Health Checks: Liveness vs Readiness

```typescript
// Health check endpoints for Kubernetes
app.get("/health/live", (req, res) => {
  // Liveness: is the process alive and not deadlocked?
  // Should NEVER check external dependencies
  res.status(200).json({ status: "alive", uptime: process.uptime() });
});

app.get("/health/ready", async (req, res) => {
  // Readiness: can this instance handle traffic?
  // Check ALL critical dependencies
  const checks = await Promise.allSettled([
    checkDatabase(),
    checkRedis(),
    checkMessageQueue(),
  ]);

  const results = {
    database: checks[0].status === "fulfilled" ? "ok" : "fail",
    redis: checks[1].status === "fulfilled" ? "ok" : "fail",
    messageQueue: checks[2].status === "fulfilled" ? "ok" : "fail",
  };

  const allHealthy = Object.values(results).every((r) => r === "ok");

  res.status(allHealthy ? 200 : 503).json({
    status: allHealthy ? "ready" : "not_ready",
    checks: results,
    eventLoopLag: getEventLoopLag(),
    memoryUsage: process.memoryUsage().heapUsed / 1024 / 1024,
  });
});
```

**Critical distinction**: If liveness fails, Kubernetes **restarts** the pod. If readiness fails, Kubernetes **removes it from the load balancer** but keeps it running. Making liveness depend on a database means a database outage will restart all your pods simultaneously, causing cascading failure.

### Graceful Shutdown

```typescript
// Graceful shutdown — critical for zero-downtime deployments
async function gracefulShutdown(signal: string): Promise<void> {
  console.log(`Received ${signal}. Starting graceful shutdown...`);

  // 1. Stop accepting new connections
  server.close();

  // 2. Stop consuming from message queues
  await messageConsumer.stop();

  // 3. Wait for in-flight requests to complete (with timeout)
  const shutdownTimeout = setTimeout(() => {
    console.error("Graceful shutdown timed out. Forcing exit.");
    process.exit(1);
  }, 30_000);

  try {
    // 4. Drain connection pools
    await Promise.all([
      database.disconnect(),
      redis.quit(),
      messageQueue.close(),
    ]);

    clearTimeout(shutdownTimeout);
    console.log("Graceful shutdown complete.");
    process.exit(0);
  } catch (error) {
    console.error("Error during shutdown:", error);
    process.exit(1);
  }
}

process.on("SIGTERM", () => gracefulShutdown("SIGTERM"));
process.on("SIGINT", () => gracefulShutdown("SIGINT"));
```

### Dead Letter Queues

```
Normal flow:
  Producer ──▶ Main Queue ──▶ Consumer ──▶ Success

Failure flow:
  Producer ──▶ Main Queue ──▶ Consumer ──▶ FAIL
                    │                        │
                    │    retry (max 3x)      │
                    │◀───────────────────────┘
                    │
                    │  after max retries
                    ▼
              Dead Letter Queue ──▶ Alert + Manual Investigation
                    │
                    ▼
              DLQ Consumer (reprocess after fix)
```

```typescript
// BullMQ dead letter queue pattern
import { Queue, Worker } from "bullmq";

const mainQueue = new Queue("orders", {
  connection: { host: "redis", port: 6379 },
  defaultJobOptions: {
    attempts: 3,
    backoff: { type: "exponential", delay: 1000 },
    removeOnComplete: 1000,
    removeOnFail: false,    // Keep failed jobs for inspection
  },
});

const worker = new Worker("orders", async (job) => {
  const result = await processOrder(job.data);
  return result;
}, {
  connection: { host: "redis", port: 6379 },
});

// Move permanently failed jobs to DLQ
worker.on("failed", async (job, error) => {
  if (job && job.attemptsMade >= job.opts.attempts!) {
    // All retries exhausted — move to DLQ
    const dlq = new Queue("orders-dlq", {
      connection: { host: "redis", port: 6379 },
    });

    await dlq.add("failed-order", {
      originalJob: job.data,
      error: error.message,
      failedAt: new Date().toISOString(),
      attempts: job.attemptsMade,
    });

    // Alert operations team
    await alerting.notify({
      severity: "high",
      message: `Order processing failed after ${job.attemptsMade} attempts`,
      jobId: job.id,
      error: error.message,
    });
  }
});
```

---

## 8. Common System Design Interview Questions (Node.js Perspective)

### Design a URL Shortener

**Key components**:

```
┌────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Client │───▶│  API GW  │───▶│ Shortener│───▶│ Postgres │
└────────┘    │(rate limit)│   │ Service  │    │ (mapping)│
              └──────────┘    └────┬─────┘    └──────────┘
                                   │
                              ┌────▼─────┐
                              │  Redis   │
                              │ (cache   │
                              │  lookups)│
                              └──────────┘
```

**Node.js specific considerations**:
- URL generation is CPU-light, I/O-heavy (DB writes, cache reads) — perfect for Node.js
- Use `crypto.randomBytes(6).toString("base64url")` for short IDs (8 chars, 281 trillion combinations)
- Redis cache-aside for redirect lookups: >99% of traffic is reads
- 301 (permanent) vs 302 (temporary) redirect affects analytics — use 302 if tracking clicks

```typescript
async function shortenUrl(originalUrl: string): Promise<string> {
  // Generate unique short code
  const shortCode = crypto.randomBytes(6).toString("base64url").substring(0, 8);

  // Store with collision check
  try {
    await db.urls.create({
      data: { shortCode, originalUrl, clicks: 0 },
    });
  } catch (e) {
    if (isDuplicateKeyError(e)) return shortenUrl(originalUrl); // Retry
    throw e;
  }

  return `https://short.ly/${shortCode}`;
}

async function resolveUrl(shortCode: string): Promise<string | null> {
  // Check cache first
  const cached = await redis.get(`url:${shortCode}`);
  if (cached) return cached;

  // Cache miss — query DB
  const record = await db.urls.findUnique({ where: { shortCode } });
  if (!record) return null;

  // Populate cache (24 hour TTL)
  await redis.setEx(`url:${shortCode}`, 86400, record.originalUrl);

  // Increment click count asynchronously (do not block redirect)
  db.urls.update({
    where: { shortCode },
    data: { clicks: { increment: 1 } },
  }).catch(console.error);

  return record.originalUrl;
}
```

**Scaling approach**: Shard by short code hash. Pre-generate short codes in batches to avoid contention. Use CDN/edge functions for redirect to minimize latency.

---

### Design a Real-Time Chat System

**Key components**:

```
┌─────────┐     ┌──────────┐     ┌────────────┐     ┌──────────┐
│ Clients │◀───▶│   LB     │◀───▶│  WS Servers│◀───▶│  Redis   │
│(WebSocket)    │(sticky/  │     │  (Node.js) │     │  Pub/Sub │
└─────────┘     │ upgrade) │     └─────┬──────┘     └──────────┘
                └──────────┘           │
                               ┌───────┼───────┐
                               ▼       ▼       ▼
                         ┌────────┐ ┌──────┐ ┌───────┐
                         │Cassandra│ │ S3  │ │ Push  │
                         │(messages)│ │(media)│ │Service│
                         └────────┘ └──────┘ └───────┘
```

**Node.js specific considerations**:
- WebSocket connections are long-lived I/O — Node.js can hold 100K+ connections per instance
- Use Socket.IO with Redis adapter for cross-server message delivery
- Cassandra (AP system) for message storage: write-heavy, ordered by timestamp per conversation
- Message IDs use ULID (lexicographically sortable, no coordination) instead of UUIDs
- Presence detection via Redis key expiry with heartbeat refresh

**Scaling approach**: Partition chat rooms across server groups. Use Redis pub/sub for cross-server delivery. For group chats, fan-out on write to each participant's message queue. Offline users get messages via push notifications and sync on reconnect.

---

### Design a Rate Limiter

**Node.js specific considerations**:
- Token bucket or sliding window log in Redis (atomic operations)
- Lua scripts in Redis for atomic rate limit check-and-increment
- Apply at API gateway level, not per-service
- Differentiate by API key, user ID, and IP (layered limiting)

```typescript
// Redis Lua script for atomic sliding window rate limit
const SLIDING_WINDOW_SCRIPT = `
  local key = KEYS[1]
  local now = tonumber(ARGV[1])
  local window = tonumber(ARGV[2])
  local limit = tonumber(ARGV[3])

  redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
  local count = redis.call('ZCARD', key)

  if count < limit then
    redis.call('ZADD', key, now, now .. ':' .. math.random())
    redis.call('PEXPIRE', key, window)
    return {1, limit - count - 1}  -- allowed, remaining
  end

  return {0, 0}  -- rejected, 0 remaining
`;

async function checkRateLimit(
  identifier: string,
  limit: number,
  windowMs: number
) {
  const result = await redis.eval(SLIDING_WINDOW_SCRIPT, {
    keys: [`ratelimit:${identifier}`],
    arguments: [String(Date.now()), String(windowMs), String(limit)],
  }) as [number, number];

  return { allowed: result[0] === 1, remaining: result[1] };
}
```

**Scaling approach**: Use Redis Cluster for distributed state. For extreme throughput, use local in-memory counters with periodic sync to Redis (approximate but fast). Implement tiered limits: global, per-tenant, per-endpoint.

---

### Design a Notification Service

**Key components**:

```
┌──────────┐    ┌──────────┐    ┌──────────────┐
│ Services │───▶│  Message │───▶│ Notification │
│ (events) │    │  Queue   │    │  Router      │
└──────────┘    │(RabbitMQ/│    └──────┬───────┘
                │ SQS)     │     ┌─────┼─────┐
                └──────────┘     ▼     ▼     ▼
                          ┌──────┐ ┌─────┐ ┌──────┐
                          │ Email│ │ SMS │ │ Push │
                          │Worker│ │Worker│ │Worker│
                          └──────┘ └─────┘ └──────┘
                                           │
                          ┌────────────────┐
                          │ User Preference│
                          │ Store (Redis)  │
                          └────────────────┘
```

**Node.js specific considerations**:
- Perfect use case: I/O-bound work (calling email/SMS/push APIs)
- BullMQ for reliable job processing with priorities, rate limiting per channel
- Template rendering with Handlebars/mjml (Node.js has excellent templating ecosystem)
- Respect user preferences: channel, frequency, quiet hours
- Deduplication: hash(userId + event + timeWindow) to prevent notification spam

**Scaling approach**: Separate queues per channel (email, SMS, push). Rate limit per provider API. Batch notifications where possible (digest emails). Use dead letter queues for failed deliveries.

---

### Design a File Upload Service

**Key components**:

```
┌────────┐  presigned URL  ┌──────────┐  direct upload  ┌─────┐
│ Client │◀────────────────│  API     │                  │ S3  │
│        │────────────────▶│  Server  │                  │     │
└───┬────┘                 └──────────┘                  └──┬──┘
    │                                                       │
    │  direct upload with presigned URL                     │
    └──────────────────────────────────────────────────────▶│
                                                            │
                           ┌──────────┐   S3 event          │
                           │  Worker  │◀────────────────────┘
                           │(validate,│
                           │ process, │
                           │ thumbnail)│
                           └──────────┘
```

**Node.js specific considerations**:
- Never proxy large uploads through Node.js — use presigned URLs for direct-to-S3 upload
- Streaming is fine for small files (<10MB) using `Readable.pipe()`
- For image processing, use `sharp` (libvips bindings) — fast, memory-efficient
- Multipart uploads: use `busboy` for streaming multipart parsing without buffering to memory
- `worker_threads` for CPU-heavy processing (thumbnail generation, virus scanning)

```typescript
// Presigned URL approach — Node.js never touches the file bytes
import { S3Client, PutObjectCommand } from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

async function getUploadUrl(
  req: Request
): Promise<{ uploadUrl: string; fileId: string }> {
  const fileId = crypto.randomUUID();
  const key = `uploads/${req.userId}/${fileId}`;

  const command = new PutObjectCommand({
    Bucket: "my-uploads",
    Key: key,
    ContentType: req.body.contentType,
    ContentLength: req.body.fileSize,      // Enforce size limit
    Metadata: { userId: req.userId },
  });

  const uploadUrl = await getSignedUrl(s3Client, command, {
    expiresIn: 300,  // 5 minute window
  });

  // Track pending upload
  await redis.setEx(`upload:${fileId}`, 600, JSON.stringify({
    key, userId: req.userId, status: "pending",
  }));

  return { uploadUrl, fileId };
}
```

**Scaling approach**: Presigned URLs eliminate Node.js as a bottleneck for upload bandwidth. Use S3 event notifications to trigger processing workers. Scale workers independently based on processing queue depth. Use step functions / workflow engine for multi-step processing (scan, validate, thumbnail, transcode).

---

## Quick Reference: When to Use What

| Decision | Option A | Option B | Choose A When | Choose B When |
|----------|----------|----------|---------------|---------------|
| Caching | In-process (LRU) | Distributed (Redis) | Single instance, <100MB data | Multi-instance, shared state |
| Real-time | WebSocket | SSE | Bidirectional needed | Server-push only |
| Session | JWT | Server-side (Redis) | Stateless scaling, short-lived sessions | Need revocation, large session data |
| DB scaling | Read replicas | Sharding | Read-heavy, <1TB | Write-heavy, >1TB |
| Job processing | In-process queue | BullMQ/SQS | Single instance, non-critical | Multi-instance, must not lose jobs |
| API style | REST | tRPC/GraphQL | Public APIs, caching | Internal APIs, TypeScript monorepo |
| Process model | Cluster module | K8s pods | Bare metal / single VM | Container orchestration available |
| File uploads | Stream through Node | Presigned URL + S3 | Files <10MB, need inline processing | Files >10MB, async processing OK |
