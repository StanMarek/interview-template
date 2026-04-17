# Rate Limiting & Throttling

## What It Is
Rate limiting controls the number of requests a client can make to a service within a given time window. It protects services from abuse, prevents resource exhaustion, and ensures fair usage.

## Why It Matters
Every public-facing API needs rate limiting. Without it, a single misbehaving client can take down an entire service. It's also a common system design interview question ("Design a rate limiter").

## Rate Limiting Algorithms

### 1. Token Bucket
A bucket holds tokens (max = bucket size). Tokens are added at a fixed rate. Each request consumes one token. If the bucket is empty, the request is rejected.

- **Pros**: Allows bursts up to bucket size; smooth average rate
- **Cons**: Slightly complex to implement
- **Parameters**: Bucket size (burst), refill rate
- **Used by**: AWS, Stripe, most production systems

### 2. Leaky Bucket
Requests enter a FIFO queue (bucket). The queue is processed at a fixed rate. If the queue is full, new requests are dropped.

- **Pros**: Perfectly smooth output rate
- **Cons**: Bursts are queued (added latency) or dropped; no burst tolerance
- **Best for**: Systems that need a constant processing rate

### 3. Fixed Window Counter
Count requests in fixed time windows (e.g., 100 requests per minute, window resets at :00, :01, :02...).

- **Pros**: Simple, low memory
- **Cons**: Boundary problem — 100 requests at 0:59 + 100 requests at 1:00 = 200 requests in 2 seconds

### 4. Sliding Window Log
Store the timestamp of every request. Count requests in the last N seconds. Reject if count exceeds limit.

- **Pros**: Perfectly accurate, no boundary problem
- **Cons**: High memory (stores every timestamp)

### 5. Sliding Window Counter
Combines fixed window and sliding window. Estimate the current window count using a weighted average of the current and previous window counts.

- **Pros**: Good accuracy, low memory
- **Cons**: Approximate, not exact
- **Formula**: `rate = current_window_count + prev_window_count × overlap_fraction`, where `overlap_fraction` is the fraction of the PREVIOUS fixed window still within the sliding range from current time.

## Rate Limiting Dimensions

| Dimension | Example |
|-----------|---------|
| Per user/API key | 1000 req/min per API key |
| Per IP | 100 req/min per IP address |
| Per endpoint | 10 req/sec on `/login` |
| Per service | Service A can make 5000 req/min to Service B |
| Global | 100K req/sec total system capacity |

Multiple dimensions are often combined: per-user AND per-endpoint.

## Distributed Rate Limiting
A single-server rate limiter is simple, but in a distributed system with multiple API servers, you need coordination.

### Approaches
- **Centralized store (Redis)**: All servers check/increment a counter in Redis. `INCR` + `EXPIRE` for fixed window; sorted sets for sliding window. Most common approach.
- **Local rate limiters + sync**: Each server maintains local counters, periodically syncing. Fast but approximate.
- **Sticky sessions**: Route each client to the same server. Avoids distributed counting but limits load balancing.

### Redis Race Condition
Two servers read count=99 simultaneously (limit=100), both allow, actual count = 101. Solution: Use Redis Lua scripts for atomic check-and-increment, or use `INCR` which is atomic.

### INCR + EXPIRE Atomicity Bug
Naive `INCR` + separate `EXPIRE` has a race window where INCR succeeds but EXPIRE fails — creating a key without TTL that accumulates counts forever. Fix with one of:
- **Lua script** (atomic): `local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return c`
- **Pipeline**: `SET key 0 EX ttl NX` then `INCR` — the `SET ... NX` creates the key with TTL only if absent, then `INCR` bumps it.

## Response Handling

### What to Return
- **HTTP 429 Too Many Requests** with `Retry-After` header
- Include rate limit headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### Graceful Degradation
Instead of hard rejecting, consider:
- **Throttling**: Slow down processing (add artificial delay)
- **Deprioritization**: Put rate-limited requests in a low-priority queue
- **Feature degradation**: Return cached/simplified responses

## Possible Interview Questions
1. "Design a rate limiter for a cloud API."
2. "How would you implement rate limiting across multiple API servers?"
3. "Compare token bucket vs sliding window. When would you use each?"
4. "How do you handle rate limiting for authenticated vs unauthenticated users?"
5. "What happens when your Redis rate limiter goes down? Do you allow all traffic or block all?"
6. "How would you rate-limit by both user AND endpoint?"
