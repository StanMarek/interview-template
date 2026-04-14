# Reservation System with Anti-Double-Booking Guarantees -- Architecture Design

## Requirements

### Functional
- Users can search for available time slots (rooms, tables, appointments, etc.)
- Users can book a specific slot for a specific time window
- The system must guarantee that no two users can book the same slot for overlapping times
- Support cancellations and modifications
- Send booking confirmations and reminders
- Handle waitlists when slots are fully booked

### Non-Functional
- **Consistency:** Strict serializability for booking writes -- double-booking must be impossible, not just unlikely
- **Availability:** 99.95% for reads (availability checks), 99.9% for writes (bookings)
- **Latency:** p99 < 200ms for availability checks, p99 < 500ms for booking confirmation
- **Throughput:** Handle flash-sale scenarios (thousands of concurrent booking attempts for the same slot)

## Scale Estimates
- 50,000 availability queries per second at peak
- 5,000 booking attempts per second at peak
- 100M active reservations
- ~10TB of reservation data per year
- Single region with read replicas in edge locations

## Architecture Decisions

### Decision 1: Database-Level UNIQUE Constraint as the Ultimate Guard

The anti-double-booking guarantee is enforced by a UNIQUE constraint on `(resource_id, time_slot)` in PostgreSQL. This is the last line of defense -- even if every other layer fails, the database will reject a duplicate booking with a constraint violation.

**Why this matters at the senior level:** Many candidates propose application-level checks ("read the slot, check if available, then insert"). This is a textbook race condition under concurrent requests. Two requests can both read "available" and both proceed to insert. The UNIQUE constraint is the ONLY mechanism that provides a true guarantee because it's enforced within a single serializable transaction at the storage engine level.

**Trade-off:** UNIQUE constraints create lock contention on hot rows. When 1000 users try to book the same concert seat simultaneously, 999 will get a constraint violation error. This is correct behavior -- we prefer errors over double-bookings. The application layer should handle these errors gracefully.

### Decision 2: Distributed Lock for Optimistic Pre-Filtering

Before hitting the database, the booking service acquires a short-lived distributed lock (Redis/Redlock) on the specific resource+timeslot combination. This is NOT the correctness mechanism -- it's a performance optimization to reduce database contention.

**Why this matters:** Without the distributed lock, during a flash sale, all 1000 requests would hit the database simultaneously, causing massive lock contention on the UNIQUE index. The distributed lock serializes these requests so they hit the database one at a time. The first one succeeds; the rest fail fast without even touching the database.

**Critical nuance:** The distributed lock has an expiry (e.g., 5 seconds). If it expires before the transaction completes, another request can acquire it. This is why the distributed lock is NOT the correctness mechanism -- the UNIQUE constraint is. The lock is only for performance. This is a common interview mistake: relying on Redis locks for correctness when Redis doesn't provide the same durability guarantees as a relational database.

### Decision 3: Optimistic Locking with Version Column

For booking modifications (change time, extend duration), we use optimistic locking with a version column. The UPDATE statement includes `WHERE version = expected_version`. If another transaction modified the row, the version won't match, and the UPDATE affects zero rows.

**Why this matters:** Pessimistic locking (SELECT FOR UPDATE) would block concurrent readers. For a reservation system where reads vastly outnumber writes, this is unacceptable. Optimistic locking allows concurrent reads while detecting write conflicts.

### Decision 4: Transactional Outbox Pattern for Event Delivery

When a booking is confirmed, we write the booking record AND an event record in the same database transaction. A separate CDC (Change Data Capture) process or polling worker reads the outbox table and publishes events to Kafka.

**Why this matters:** If we publish the event outside the transaction, we risk: (a) the event is published but the booking fails (ghost notification), or (b) the booking succeeds but the event is lost (missing notification). The transactional outbox guarantees exactly-once event delivery relative to the database state.

## Consistency Model

**Strict serializability for booking writes.** We use PostgreSQL's SERIALIZABLE isolation level for the booking transaction, combined with the UNIQUE constraint. This provides the strongest possible guarantee: it's as if all bookings happened one at a time, in some sequential order.

**Read-your-writes consistency for the booking user.** After a user successfully books, subsequent reads from that user must see their booking. We achieve this by routing the user's reads to the primary database for a short window (5 seconds) after a write, then falling back to replicas.

**Eventual consistency for availability views.** Availability checks read from Redis cache or read replicas, which may be slightly stale. This is acceptable because the cache is a hint, not a guarantee -- the actual guarantee is enforced at write time. A user might see a slot as "available" in the UI, attempt to book it, and get a "slot taken" error. This is the correct trade-off: stale reads are better than slow reads for availability queries.

**Why NOT strong consistency for reads:** Making all reads hit the primary would limit throughput to ~10K reads/second on a single PostgreSQL instance. With replicas and cache, we can handle 50K+ reads/second. The trade-off (occasional "slot taken" errors) is vastly preferable to the alternative (slow/unavailable availability checks).

## Failure Modes

### Redis (distributed lock) failure
The system continues to work correctly but with degraded performance. Without the pre-filtering lock, all booking requests go directly to the database, causing higher contention. The UNIQUE constraint still prevents double-booking. This is why correctness must never depend on Redis.

### Primary database failure
Failover to the synchronous standby (RPO = 0). During failover (~30 seconds), booking writes fail. Availability reads continue from replicas. After failover, the new primary has all committed bookings.

### Concurrent booking race condition
Two users simultaneously try to book the same slot. The distributed lock serializes them. Even without the lock, the UNIQUE constraint ensures only one succeeds. The losing request gets a clear error: "Slot no longer available."

### Outbox delivery failure
Events accumulate in the outbox table. The CDC process or polling worker has at-least-once delivery semantics. Downstream consumers must be idempotent. Notifications may be delayed but never lost.

### Cache inconsistency
The availability cache shows a slot as available, but it's actually booked. User attempts to book, gets a conflict error, UI refreshes and shows the slot as taken. This is expected behavior and handled in the UI with a clear error message and automatic refresh.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **API Gateway** | Rate limiting (prevents abuse during flash sales), authentication, routing |
| **Availability Service** | Read-only service for checking slot availability, reads from cache/replicas |
| **Booking Service** | Handles booking creation, modification, and cancellation |
| **Distributed Lock (Redis)** | Performance optimization to reduce DB contention on hot slots |
| **Availability Cache (Redis)** | Cached availability data for fast reads, TTL-based invalidation |
| **Primary DB (PostgreSQL)** | Source of truth with UNIQUE constraint on (resource_id, time_slot) |
| **Read Replica** | Serves availability reads with acceptable staleness |
| **Outbox Table** | Stores events in the same transaction as the booking for reliable delivery |
| **Optimistic Lock (version)** | Detects concurrent modifications to existing bookings |
| **Event Bus (Kafka)** | Distributes booking events to downstream services |
| **Notification Service** | Sends booking confirmations, reminders, and cancellation notices |
| **Audit Log** | Immutable record of all booking operations for dispute resolution |
| **Monitoring** | Tracks booking success/failure rates, contention metrics, cache hit rates |

## Key Trade-offs

### Consistency vs. Read Performance
We choose eventual consistency for availability reads and strict serializability for booking writes. This gives us the best of both worlds: fast reads and correct writes. The cost is occasional "slot taken" errors in the UI.

### Lock Granularity
Locking at the (resource_id, time_slot) level provides fine-grained concurrency. Two users booking different slots on the same resource don't block each other. But for overlapping time ranges (not just discrete slots), we need range locking, which is more expensive.

### Database Choice
PostgreSQL gives us SERIALIZABLE isolation and UNIQUE constraints. A NoSQL database would be faster for reads but couldn't provide the same write correctness guarantees without significant application-level complexity.

## What Fails First

**The distributed lock becomes the bottleneck during flash sales.** When 10,000 users try to book the same slot at the same instant, they all contend on the same Redis key. The lock serializes them, but the tail latency grows linearly with the queue depth.

**Mitigation:** For flash-sale scenarios, use a queue-based approach. All booking requests for a hot slot are enqueued in a FIFO queue. A single worker processes them sequentially. The first one succeeds; the rest are rejected immediately. This eliminates lock contention entirely.

## v1 vs v2

### v1 (Ship first)
- Single resource type (e.g., restaurant tables only)
- Discrete time slots (not arbitrary time ranges)
- UNIQUE constraint on (resource_id, slot_id)
- Simple Redis cache for availability with TTL invalidation
- No distributed lock (rely solely on DB constraint)
- Single-region deployment
- Email notifications only

### v2 (Scale and harden)
- Multiple resource types with different booking rules
- Arbitrary time range bookings with overlap detection (exclusion constraints in PostgreSQL)
- Distributed lock for flash-sale performance
- Waitlist functionality with automatic promotion
- Multi-region with conflict-free booking (shard by resource geography)
- Real-time availability updates via WebSocket
- Calendar integration (Google Calendar, Outlook)
- Recurring reservation support
- Dynamic pricing based on demand
