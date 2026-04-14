# Service for Idempotent API Execution -- Architecture Design

## Requirements

### Functional
- Accept API requests with a client-provided idempotency key
- Guarantee exactly-once execution semantics for any request with the same idempotency key
- Return the cached response for duplicate requests (same key + same parameters)
- Reject requests where the same key is used with different parameters (misuse detection)
- Support configurable TTL for idempotency records (key expiration)
- Handle concurrent duplicate requests (only one executes, others wait)
- Provide status tracking for in-flight requests
- Support both synchronous and asynchronous execution patterns

### Non-Functional
- **Consistency:** Linearizable for idempotency checks -- two concurrent requests with the same key must never both execute
- **Availability:** 99.99% -- this is inline middleware on critical API paths
- **Latency:** < 2ms overhead per request for the idempotency check
- **Durability:** Completed responses must survive crashes (so retries after recovery return the cached response)
- **Transparency:** The downstream service should not need to be aware of the idempotency layer

## Scale Estimates
- 100K API requests/second through the idempotency layer
- 5% duplicate rate (5K duplicates/second)
- 1B idempotency records per day (with 24-hour TTL)
- Average response size: 2 KB
- Idempotency store size: ~2 TB (1B records x 2 KB)

## Architecture Decisions

### Decision 1: Idempotency Key as a First-Class API Concept

The idempotency key is passed as an HTTP header (`Idempotency-Key: <uuid>`), NOT as part of the request body. The idempotency layer is a transparent middleware that wraps the actual API handler.

**Why as a header:** The idempotency key is metadata about the request, not data for the business logic. Putting it in the body would require every API handler to understand idempotency. As a header, the middleware intercepts it before the handler runs, making idempotency a cross-cutting concern that works with ANY API.

**Why client-generated:** The client must generate the key BEFORE sending the request. This is because the client is the only entity that knows whether a request is a retry. If the server generated the key, the server couldn't distinguish between a retry (should return cached response) and a new request (should execute).

**The critical insight that separates senior from mid-level:** Idempotency is NOT the same as deduplication. Deduplication detects and drops duplicate requests. Idempotency ensures that duplicate requests return the SAME response as the original. The response caching is essential -- without it, you have deduplication but not idempotency, and the client doesn't know the result of its operation.

### Decision 2: Request Fingerprinting for Misuse Detection

When an idempotency key is reused, we compute a fingerprint (SHA-256 hash) of the request body and compare it with the stored fingerprint. If they don't match, the request is rejected with 422 Unprocessable Entity.

**Why this matters:** Without fingerprinting, a client could accidentally reuse an idempotency key for a different operation. For example, using the same key for "charge $10" and "charge $50" -- without fingerprinting, the second request would silently return the cached response from the first, and the client would think it charged $50 when it actually charged $10.

**Trade-off:** Fingerprinting means the full request body must be hashed on every request. For large request bodies (file uploads), this adds latency. For most API requests (JSON payloads < 10 KB), the SHA-256 computation takes < 0.1ms.

### Decision 3: Three-Phase State Machine (STARTED -> COMPLETED / FAILED)

Each idempotency key has a state: STARTED (request is being executed), COMPLETED (response is cached), or FAILED (execution failed, retryable). When a duplicate request arrives for a STARTED key, it waits (polls or long-polls) until the state transitions to COMPLETED or FAILED.

**Why a state machine:** Without the STARTED state, two concurrent requests with the same key would both see "key doesn't exist" and both proceed to execute. The STARTED state acts as a mutex: the first request sets the state to STARTED, and the second request sees STARTED and waits.

**Why FAILED is a separate state:** If the original request fails, we want the retry to re-execute (not return a cached error). The FAILED state signals "this key was tried but didn't succeed -- feel free to try again." This is different from COMPLETED (which returns the cached response regardless of whether it was a success or error response).

**Senior-level nuance:** The STARTED state must have a timeout. If the original request crashes without transitioning to COMPLETED or FAILED, the key is stuck in STARTED forever. A timeout (e.g., 60 seconds) allows the state to automatically transition to FAILED, unblocking retries.

### Decision 4: Dual-Storage -- Redis for Hot Path, Database for Durability

The idempotency key state and cached response are stored in both Redis (for fast lookup) and a relational database (for durability). Redis is the primary read path. The database is the source of truth and is consulted when Redis misses (e.g., after a Redis restart).

**Why dual storage:** Redis alone is risky because it can lose data on restart (even with AOF persistence, there's a small window). If the server crashes after executing the request but before caching the response in Redis, the retry would re-execute the request (violating idempotency). The database ensures the response is durably stored.

**Trade-off:** Dual writes add complexity and latency (~1ms for the DB write). The DB write can be asynchronous (write to Redis immediately, write to DB in background) with the risk that a crash between the two writes loses the record. For most APIs, this risk is acceptable. For financial APIs, the DB write must be synchronous.

## Consistency Model

**Linearizability for the idempotency check.** When two concurrent requests arrive with the same idempotency key, exactly one must win the race and execute. We achieve this with a Redis `SET NX` (set if not exists) operation with a TTL. This is an atomic compare-and-swap that guarantees only one request can set the key.

**Why SET NX is sufficient (and when it isn't):** Redis `SET NX` is a single-node atomic operation. For a single Redis master, this provides linearizability. For Redis Cluster, the operation is atomic within a single hash slot (which is determined by the key). As long as all requests with the same idempotency key hash to the same slot (which they will, since the key is the same), `SET NX` provides the necessary guarantee.

**When it isn't sufficient:** If Redis uses master-replica with async replication and the master fails after the SET but before replication, the new master doesn't have the key. A retry could then re-execute. For financial APIs, use Redlock (multi-node consensus) or a database-level unique constraint as the authoritative check.

**Eventual consistency for the response cache.** After the request completes, the response is cached in Redis and written to the database. The dual write is not atomic, so there's a brief window where the response is in one store but not the other. This is acceptable because the worst case is that a retry re-executes (the idempotency guarantee degrades to at-most-twice, which converges to exactly-once once both stores are consistent).

## Failure Modes

### Redis failure during idempotency check
Fall back to the database for the idempotency check. This adds ~5ms latency but maintains correctness. If both Redis and the database are down, fail-closed (reject the request with 503).

### Server crash after execution, before caching response
The request has executed but the response is not cached. On retry, the system sees no idempotency record and re-executes. This violates idempotency. **Mitigation:** Write the response to the database WITHIN the same transaction as the business logic (transactional outbox pattern). This guarantees the response is durably stored if and only if the business logic committed.

### Idempotency key collision (different clients generate the same UUID)
UUIDv4 has a collision probability of ~1 in 2^122. In practice, this never happens. But as a defense-in-depth measure, the idempotency key is scoped to the authenticated user (client_id + idempotency_key). Two different clients with the same key are treated as different requests.

### Long-running request with duplicate arrival
Request A starts executing (STARTED state). Request B arrives with the same key. Request B sees STARTED and polls. If Request A takes longer than the polling timeout, Request B returns 409 Conflict ("request in progress, try again later"). The client SDK retries with exponential backoff.

### Idempotency store fills up (TTL not aggressive enough)
With 1B records/day and 24-hour TTL, the store holds ~1B records at steady state. If the TTL cleanup worker falls behind, the store grows unboundedly. **Mitigation:** Redis TTL handles this automatically for Redis. For the database, a scheduled job purges expired records in batches.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Client SDK** | Generates idempotency keys (UUIDv4), handles automatic retry with backoff |
| **API Gateway** | Extracts `Idempotency-Key` header, routes to idempotency middleware |
| **Key Exists Check** | `SET NX` in Redis to atomically claim the key |
| **Request Fingerprint Validator** | SHA-256 of request body, compared with stored fingerprint |
| **Status Check** | Determines if request is STARTED, COMPLETED, or FAILED |
| **Distributed Lock** | Per-key lock to serialize concurrent duplicates |
| **Response Cache** | Stores the response associated with each completed key |
| **Execution Engine** | Runs the actual API handler with the request |
| **Request State Machine** | Tracks the lifecycle: STARTED -> COMPLETED / FAILED |
| **Retry Handler** | Manages retries for failed downstream calls with exponential backoff |
| **Circuit Breaker** | Protects downstream services from cascade failure |
| **Downstream Service** | The actual service being called (payment, order, etc.) |
| **Compensation Handler** | Rolls back partial operations on failure |
| **Idempotency Store (Redis + DB)** | Dual storage for idempotency records |
| **Request Log** | Full request payload stored for debugging and fingerprint comparison |
| **Response Store** | Cached responses for completed requests |
| **Audit Log** | Records deduplication events for debugging |
| **TTL Cleanup** | Garbage collects expired idempotency records |
| **Monitoring** | Tracks dedup rate, cache hit rate, execution failures |

## Key Trade-offs

### Transparency vs. Control
The idempotency layer is transparent middleware -- the downstream service doesn't know about it. This is simpler to adopt but means the idempotency boundary doesn't always align with the business logic boundary. For complex operations (multi-step sagas), idempotency at the API level isn't sufficient -- each step needs its own idempotency key.

### Latency vs. Durability
Redis gives < 1ms lookups but can lose data. The database gives durability but adds 5ms. Dual-write gives both at the cost of complexity. The choice depends on how catastrophic a double-execution would be.

### TTL vs. Storage Cost
Longer TTLs (7 days) protect against late retries but consume more storage. Shorter TTLs (1 hour) save storage but miss retries that come after the TTL expires. 24 hours is a reasonable default -- most legitimate retries happen within minutes.

## What Fails First

**The response store becomes the bottleneck.** At 100K requests/second with 2 KB average response size, the system writes 200 MB/second of cached responses. Over 24 hours, this is 17 TB. The database must handle this write throughput and the cleanup worker must keep pace.

**Mitigation:** Store responses in Redis with TTL (auto-cleanup). Only write to the database for requests that need durable idempotency (financial operations). Use response compression to reduce storage. For large responses, store only a hash and a pointer to the full response in object storage.

## v1 vs v2

### v1 (Ship first)
- Redis-only idempotency store (no database fallback)
- Simple key existence check (no state machine)
- No fingerprint validation
- Fixed 24-hour TTL
- Synchronous execution only
- No response caching (just deduplication, not true idempotency)

### v2 (Scale and harden)
- Dual-storage (Redis + database) with transactional outbox
- Three-phase state machine (STARTED -> COMPLETED / FAILED)
- Request fingerprint validation with SHA-256
- Configurable TTL per API endpoint
- Concurrent duplicate handling (wait for in-flight request)
- Response caching with compression
- Async execution support (return 202, poll for result)
- Client SDK with automatic retry and jitter
- Metrics dashboard (dedup rate, collision rate, response cache hit rate)
- Integration with saga coordinators for multi-step idempotency
