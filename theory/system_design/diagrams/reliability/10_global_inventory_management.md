# Global Inventory Management System -- Architecture Design

## Requirements

### Functional
- Track real-time inventory across multiple warehouses and geographies
- Reserve inventory for pending orders (soft hold with TTL)
- Decrement inventory on confirmed purchase (hard commit)
- Support multiple sales channels (web, mobile, marketplace, POS, wholesale) with unified inventory view
- Allocate inventory quotas per channel to prevent one channel starving another
- Calculate Available-to-Promise (ATP) quantity factoring in reservations, in-transit stock, and backorders
- Handle stock replenishment from suppliers with inbound tracking
- Support cycle counting (physical recount) with discrepancy resolution
- Provide inventory movement audit trail (every add, remove, transfer, adjustment)

### Non-Functional
- **Consistency:** Strong consistency for inventory decrements -- overselling must be prevented at all costs
- **Availability:** 99.99% -- if inventory service is down, no orders can be placed
- **Latency:** p99 < 10ms for availability check, p99 < 50ms for reservation + commit
- **Durability:** Zero inventory record loss -- every movement is persisted and auditable
- **Scale:** Support millions of SKUs across thousands of warehouse locations

## Scale Estimates
- 10M SKUs across 50 warehouses in 5 regions
- 100K inventory checks/second (product page views)
- 10K reservation + commit operations/second (order placements)
- Peak: 10x during flash sales (100K orders/second)
- Total inventory records: ~500M (SKU x location combinations)
- Event log: ~1B inventory events/day

## Architecture Decisions

### Decision 1: Atomic Counter in Redis for Real-Time Decrements

Inventory quantity per SKU per warehouse is maintained as an atomic counter in Redis. Reservation uses `DECRBY` which is atomic -- if the result is negative, the reservation fails and the counter is rolled back with `INCRBY`. The database is updated asynchronously.

**Why atomic counters:** The fundamental challenge of inventory management is preventing overselling under high concurrency. When 1000 users try to buy the last item simultaneously, exactly one must succeed. A traditional "read quantity, check if > 0, update" pattern has a race condition window between the read and the write. Redis `DECRBY` is a single atomic operation -- there is no window.

**Why not just use the database:** PostgreSQL can handle `UPDATE inventory SET qty = qty - 1 WHERE qty > 0 AND sku_id = ?` atomically, but at 10K writes/second to the same row (hot SKU during flash sale), row-level locking creates massive contention. Redis handles this at ~100K operations/second on a single key with sub-millisecond latency.

**Trade-off:** Redis and the database can diverge. If Redis crashes after a decrement but before the async DB write, the database has more inventory than Redis. The reconciliation worker detects and fixes this. The direction of the discrepancy is safe: we might miss a sale (Redis says 0, DB says 1) but we won't oversell (Redis says 1, DB says 0). This asymmetry is by design.

### Decision 2: Two-Phase Reservation (Soft Hold + Hard Commit)

Ordering follows a two-phase protocol: (1) **Reserve** -- decrement the Redis counter with a TTL (e.g., 10 minutes). If the user doesn't complete checkout, the reservation expires and the counter is incremented back. (2) **Commit** -- after payment succeeds, the reservation is made permanent and the database is updated.

**Why two phases:** Without reservation, a user could add an item to cart, take 30 minutes to check out, and find the item sold out. With reservation, the item is "held" for a limited time. This balances user experience (the item doesn't disappear during checkout) with inventory accuracy (abandoned carts don't permanently lock inventory).

**Senior-level nuance:** The reservation TTL must be carefully tuned. Too short (1 minute): users lose their cart items frequently, causing frustration and cart abandonment. Too long (30 minutes): inventory is locked up by browsers who will never check out, causing false "out of stock" for real buyers. 10-15 minutes is the industry standard.

**Trade-off:** Reservations reduce the effective available inventory. If 100 items are in stock and 30 are reserved, only 70 are available. During flash sales, this can cause premature "sold out" signals even though many reservations will expire. Mitigation: overbooking (reserve more than physical stock) with a small buffer, accepting that a small percentage of orders may need to be backordered.

### Decision 3: Channel-Level Allocation Quotas

Total inventory is partitioned into per-channel quotas (e.g., 60% web, 20% marketplace, 10% POS, 10% wholesale). Each channel can only sell up to its quota. Unsold quota from one channel can be reallocated to another dynamically.

**Why allocation:** Without quotas, a flash sale on the web store could deplete all inventory, leaving the retail stores with nothing to sell. Allocation ensures each channel has guaranteed stock while allowing dynamic rebalancing based on demand.

**Implementation:** Each channel has its own Redis counter per SKU. The allocation engine adjusts counters periodically based on sales velocity. If the web channel is selling faster than expected and the wholesale channel is slow, quota is transferred from wholesale to web.

**Trade-off:** Allocation reduces the total inventory available to any single channel. An item might show "out of stock" on the web store while there's still stock allocated to the POS system. This is a deliberate business decision -- it prevents channel cannibalization.

### Decision 4: Event Sourcing for Inventory Movements

Every inventory change (sale, return, transfer, adjustment, recount) is recorded as an immutable event in Kafka. The current inventory state can be reconstructed by replaying events. The Redis counters and database are materialized views derived from the event log.

**Why event sourcing:** Inventory systems have a unique requirement: you need to know not just the current quantity but HOW you got there. "We have 42 units" is not enough -- you need "we started with 100, sold 50, received 10, adjusted -3, transferred -15 = 42." Event sourcing provides this naturally. It also enables point-in-time queries ("what was our inventory at 3 PM yesterday?") and makes auditing trivial.

**Trade-off:** Event sourcing adds complexity. Computing the current state requires aggregating all events (or maintaining materialized views that are updated on each event). The event log grows unboundedly and requires periodic snapshotting/compaction.

## Consistency Model

**Strong consistency for inventory decrements (writes).** Every reservation and commit goes through the Redis atomic counter. The `DECRBY` operation is linearizable within a single Redis master. If the result is negative (oversold), the operation is immediately rolled back. The database is updated in the same transaction as the commit for durability.

**Eventual consistency for availability reads.** Product pages show inventory from the read cache (updated every 1-5 seconds from Redis). This means a user might see "in stock" and then get an "out of stock" error when trying to buy. This is acceptable because: (a) the cache is a hint, not a guarantee, (b) the error is handled gracefully in the UI, and (c) the alternative (reading from Redis on every page view at 100K/second) would overwhelm the counter store.

**Why this split:** The writes must be strongly consistent because overselling creates real business problems (customer dissatisfaction, fulfillment failure, financial loss). The reads can be eventually consistent because showing "2 left" vs "4 left" doesn't materially change the user experience -- both mean "buy now before it's gone."

## Failure Modes

### Redis (counter store) failure
All reservations and commits fail. Orders cannot be placed. The system fails-closed to prevent overselling. The database still has the correct inventory, but we cannot serve real-time decrements at the required throughput.

**Mitigation:** Redis Cluster with 3 replicas per shard. Sentinel for automatic failover (~5 seconds). During failover, retry with exponential backoff. After failover, the new Redis master is re-hydrated from the database.

### Database failure
New commits are not durably persisted. Redis continues to serve decrements, but if Redis also fails, the uncommitted changes are lost. Mitigation: synchronous replication to at least one standby. Automatic failover with zero data loss.

### Inventory discrepancy (Redis and DB disagree)
The reconciliation worker runs every 5 minutes, comparing Redis counters with database values. If they differ, the database is the source of truth and Redis is re-synced. The direction of the discrepancy determines the action: if Redis shows less than DB (conservative), no action needed. If Redis shows more than DB (risk of overselling), Redis is corrected downward immediately.

### Flash sale thundering herd
1M users hitting the same SKU simultaneously. Redis handles ~100K operations/second per key, so the decrement throughput is sufficient. The read cache (showing availability) may show stale data, causing some users to attempt purchases that will fail. The UI must handle this gracefully with "item is no longer available" messages.

### Warehouse reports different physical count
Cycle counting reveals that the physical inventory doesn't match the system. The recount service creates an adjustment event (positive or negative) to reconcile. All adjustments require approval if they exceed a threshold (>5% discrepancy).

### Reservation TTL expires during payment processing
The user started checkout, the 10-minute reservation expired, and inventory was released. Another user bought the last item. The original user's payment succeeds but the inventory is now gone. **Mitigation:** The commit operation checks available inventory again. If it fails, the payment is refunded. The user gets a "sorry, this item sold out during checkout" message. This is rare but unavoidable without longer reservation TTLs.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Sales Channels** | Web, mobile, marketplace, POS, wholesale -- all converge on the API gateway |
| **API Gateway** | Authentication, rate limiting, routing |
| **Reservation Service** | Creates soft holds with TTL using Redis `DECRBY` |
| **Inventory Service** | Commits confirmed purchases, updates database |
| **Allocation Engine** | Manages per-channel quotas, dynamic rebalancing |
| **Availability Calculator** | Computes ATP (on-hand - reserved - allocated + in-transit) |
| **Oversell Check** | Final gate: rejects if atomic decrement goes negative |
| **Primary DB** | Source of truth for inventory state, partitioned by SKU |
| **Counter Store (Redis)** | Real-time atomic counters for fast decrements |
| **Inventory Event Log (Kafka)** | Immutable event stream of all inventory movements |
| **Read Cache** | Cached availability for product pages |
| **Warehouses** | Physical locations with their own inventory management |
| **Supplier Feed** | Inbound stock from suppliers |
| **Cycle Count** | Physical recount process for discrepancy detection |
| **Reconciliation Worker** | Compares Redis counters with database, fixes divergence |
| **Demand Forecast** | Predicts demand to set reorder points |
| **Audit Trail** | Every inventory movement for compliance and debugging |
| **Monitoring** | Tracks oversell rate, stock-out rate, reservation expiry rate |

## Key Trade-offs

### Consistency vs. Throughput
Atomic Redis counters provide strong consistency for decrements at 100K ops/second. A database-only approach would provide the same consistency but at ~5K ops/second (row locking contention). We accept the complexity of dual-storage for the throughput gain.

### Accuracy vs. User Experience
Slightly stale availability data (eventual consistency for reads) provides better user experience (fast page loads) than always-accurate data (which would require hitting Redis on every view). The cost is occasional "out of stock" errors during checkout.

### Simplicity vs. Channel Fairness
Without allocation quotas, the system is simpler (single global counter per SKU) but one channel can starve others. Quotas add complexity but ensure business fairness across channels.

## What Fails First

**Hot SKU contention during flash sales.** A single SKU with millions of concurrent buyers creates a hot partition. Even Redis can handle only ~100K operations/second per key. At 1M simultaneous requests, the queue depth causes p99 latency to spike to seconds.

**Mitigation:** 
1. **Sharding by warehouse:** Instead of one global counter, maintain per-warehouse counters. A purchase request is routed to the nearest warehouse with stock.
2. **Probabilistic early rejection:** When stock is low (< 100 remaining), randomly reject a percentage of incoming requests without hitting Redis. This reduces contention while still selling all inventory.
3. **Virtual queue:** For anticipated flash sales, queue all requests and process them in order. Only the first N get the item; the rest are immediately rejected.

## v1 vs v2

### v1 (Ship first)
- Single warehouse, single region
- PostgreSQL as the only inventory store (no Redis)
- `UPDATE SET qty = qty - 1 WHERE qty > 0` for decrements
- No reservation (decrement on order confirmation only)
- No channel allocation (single counter per SKU)
- Basic availability check from database
- Simple audit log table

### v2 (Scale and harden)
- Multi-warehouse with per-location inventory
- Redis atomic counters for real-time decrements
- Two-phase reservation with configurable TTL
- Channel-level allocation quotas with dynamic rebalancing
- Event sourcing for complete inventory history
- Available-to-Promise (ATP) calculation (on-hand + in-transit - reserved - allocated)
- Reconciliation worker for Redis-DB consistency
- Cycle count integration with discrepancy resolution
- Demand forecasting for automatic reorder points
- Safety stock thresholds with automatic alerts
- Multi-region deployment with partition-level inventory ownership
- Integration with warehouse management systems (WMS)
