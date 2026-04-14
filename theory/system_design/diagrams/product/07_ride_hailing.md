# Ride-Hailing Platform — Architecture Design

## Requirements

### Functional
- Rider requests a ride with pickup and dropoff locations
- System matches rider with nearest available driver
- Real-time driver location tracking on rider's map
- Dynamic (surge) pricing based on supply/demand
- ETA estimation for pickup and trip
- In-app payment (card, wallet)
- Trip history and receipts
- Driver and rider ratings
- Multiple vehicle types (economy, premium, XL)

### Non-Functional
- Match latency < 10 seconds (time from request to driver notification)
- Location update frequency: every 4 seconds from active drivers
- ETA accuracy within 20% of actual
- 99.99% availability (people depend on this for transport)
- Eventual consistency acceptable for ratings/history; strong consistency for trip state machine
- Support 1M concurrent trips

## Scale Estimates
- 100M riders, 10M drivers, 20M DAU riders
- 15M trips/day, peak 500 trips/second
- Active drivers at any time: 5M, each sending location every 4 seconds = 1.25M GPS updates/second
- Read QPS (rider checking map, ETA): 5M/second during peak
- Location data: ~50 bytes per update * 1.25M/sec = 5.4TB/day

## Architecture Decisions

### Geospatial Indexing with S2/H3 Cells
Finding "nearest available drivers" is the core spatial query. Naive approach: compute distance to all 5M active drivers. Better: partition the world into hierarchical grid cells (Google S2 or Uber H3). Each cell is a hexagon ~1km wide. We maintain a Redis set per cell containing active driver IDs. To find nearby drivers: compute the rider's cell and its neighboring cells (ring-1), fetch driver IDs from those cells. This reduces the search space from 5M to ~50 drivers. When a driver moves, we update their cell membership. H3 is preferred over geohash because hexagons have uniform neighbors (no edge/corner distortion).

### Trip State Machine
A trip goes through well-defined states: REQUESTED -> MATCHED -> DRIVER_EN_ROUTE -> ARRIVED -> IN_PROGRESS -> COMPLETED (or CANCELLED at any point). This state machine is the source of truth. Every state transition is an event published to Kafka. State transitions must be atomic and idempotent — the Trip Service uses optimistic locking (version column) on the trip row. The state machine prevents invalid transitions (e.g., can't go from COMPLETED back to IN_PROGRESS).

### Supply-Demand Based Surge Pricing
Surge pricing balances the marketplace. When demand exceeds supply in a geographic area, prices increase to (1) attract more drivers to that area and (2) reduce marginal demand. Implementation: divide the city into zones. Every 60 seconds, compute the demand/supply ratio per zone. Map this ratio to a surge multiplier (1.0x to 5.0x) using a tuned curve. The surge multiplier is cached and served to riders at ride-request time. Key insight: surge must be computed at the zone level, not globally — a concert ending creates localized demand spikes.

### Separate Location Ingestion from Trip Logic
At 1.25M GPS updates/second, the Location Service must be extremely lightweight — it receives, validates, and writes to the geospatial index. It does NOT process trip logic, ETA computation, or notification. Those are downstream consumers of location events. This separation ensures that a spike in location traffic doesn't affect trip matching or payment processing.

## Component Breakdown

- **Rider App / Driver App**: GPS streaming via WebSocket, real-time map updates, push notification handling. Driver app runs in background mode.
- **WebSocket Gateway**: Persistent connections for both riders and drivers. Routes GPS updates to Location Service, sends match/status updates to clients.
- **API Gateway**: REST endpoints for ride requests, trip history, payment methods, ratings.
- **Matching Engine**: The core algorithm. On ride request: (1) query Location Service for nearby available drivers; (2) filter by vehicle type, rating, acceptance rate; (3) rank by distance + ETA; (4) send offer to top driver with 15-second acceptance timeout; (5) if declined, cascade to next driver. Uses a dispatch ring pattern for efficiency.
- **Location Service**: Ingests GPS updates at 1.25M/sec. Updates geospatial index (H3 cell -> driver set). Publishes location events to Kafka for downstream consumers (rider map, ETA, analytics).
- **Trip Service**: Manages trip lifecycle state machine. Source of truth for trip state. Publishes state transition events.
- **Pricing/Surge Service**: Computes surge multipliers per zone per minute. Also handles fare estimation (base fare + distance * rate + time * rate) * surge.
- **Payment Service**: Processes end-of-trip charges. Handles pre-authorization at ride request, final charge at completion, tips, refunds.
- **Notification Service**: Push notifications for match found, driver arriving, trip complete, receipt.
- **ETA Model (ML)**: Predicts pickup ETA and trip ETA using historical trip data, current traffic, time of day, weather. Serves predictions with < 50ms latency.
- **Demand Forecast (ML)**: Predicts demand by zone for the next 30 minutes. Feeds into surge pricing and driver repositioning suggestions.

## Data Model

### Trips (MySQL, sharded by trip_id)
- PK: trip_id
- Columns: rider_id, driver_id, vehicle_type, status, pickup_lat/lng, dropoff_lat/lng, pickup_eta_sec, fare_estimate, final_fare, surge_multiplier, created_at, matched_at, started_at, completed_at, version (for optimistic locking)
- Index: (rider_id, created_at DESC), (driver_id, created_at DESC)

### Driver Locations (Redis geospatial / H3 index)
- H3 cell -> Set of active driver_ids
- Per-driver: driver_id -> {lat, lng, heading, speed, last_updated, status (available/busy/offline)}
- TTL: 30 seconds (driver considered offline if no update)

### Location History (Cassandra)
- Partition key: (driver_id, date)
- Clustering key: timestamp
- Columns: lat, lng, speed, heading
- Used for route reconstruction, analytics, fraud detection

### Users (MySQL)
- PK: user_id
- Columns: name, phone, email, type (rider/driver), rating_avg, rating_count, payment_method_id

### Payments (MySQL, sharded by trip_id)
- PK: payment_id
- Columns: trip_id, rider_id, amount, tip, payment_method, status, stripe_charge_id

## Key Trade-offs

- **Match speed vs match quality**: Sending the ride offer to the single closest driver is fast but ignores factors like the driver heading away from the rider (close but slow to arrive). Considering multiple candidates and ranking by ETA is better but takes longer. We use a time-boxed approach: consider drivers within a radius, rank by predicted pickup ETA, send to best within 2 seconds.
- **Surge transparency vs revenue**: Showing the surge multiplier upfront is honest but some riders cancel and retry hoping for lower surge. Hiding it (just showing the total estimate) is more opaque. Regulatory requirements usually mandate transparency.
- **GPS frequency vs battery**: 4-second updates provide smooth tracking but drain battery. 10-second updates save battery but make the map jerky. We interpolate on the client side and use adaptive frequency (4s during active trip, 30s when idle).
- **Consistency in trip state**: We use strong consistency (optimistic locking on a single DB row) for trip state to prevent race conditions (e.g., both rider and driver cancelling simultaneously). This means the Trip DB is a scaling bottleneck, but trip throughput (500/sec) is modest.

## What Fails First

**Geospatial index hotspots.** In a dense city during rush hour, a single H3 cell might contain 10,000 drivers. Nearby riders all query the same cell, creating a Redis hotspot. Solution: (1) Use finer-grained H3 resolution in dense areas (dynamically); (2) Replicate hot cells across multiple Redis replicas with read distribution; (3) Pre-compute "top 10 available drivers per cell" every 2 seconds instead of computing on every ride request.

## v1 vs v2

### v1 (MVP)
- Single vehicle type
- Match to closest available driver (no ranking)
- Fixed pricing (no surge)
- Basic ETA using straight-line distance / average speed
- GPS updates via HTTP polling (every 10s)
- Single PostgreSQL database
- Payment via Stripe Checkout at trip end

### v2
- Multiple vehicle types with type-specific pricing
- ML-based matching considering ETA, driver heading, acceptance rate
- Dynamic surge pricing per zone
- ML ETA model trained on historical trips
- WebSocket for real-time location streaming
- Geospatial index with H3 cells in Redis
- Pre-authorized payments with automatic charge
- Demand forecasting for driver repositioning
- Shared rides / carpooling
- Scheduled rides
- Safety features (trip sharing, emergency button, driver verification)
