# Restaurant Delivery Platform — Architecture Design

## Requirements

### Functional
- Customers browse restaurants near their location
- View menus, filter by cuisine, price, rating, delivery time
- Place orders with customizations (toppings, special instructions)
- Real-time order status tracking (confirmed, preparing, picked up, delivered)
- Live driver location on map during delivery
- In-app payment with tip support
- Restaurant portal: manage menu, accept/reject orders, update prep time
- Driver app: receive delivery offers, navigate, confirm pickup/delivery
- Ratings and reviews for restaurants and drivers
- Scheduled orders (order now, deliver later)

### Non-Functional
- Restaurant search < 200ms
- Order placement to restaurant notification < 5 seconds
- Driver assignment < 30 seconds after restaurant confirms
- Real-time tracking updates every 5 seconds
- 99.9% availability (revenue directly tied to uptime)
- Peak-hour traffic: 5x average (lunch and dinner rushes)
- Eventually consistent for reviews/ratings; strongly consistent for orders and payments

## Scale Estimates
- 50M DAU customers, 1M restaurants, 2M drivers
- 30M orders/day, peak 1000 orders/second during dinner rush
- Active drivers at peak: 500K, each sending GPS every 5s = 100K updates/second
- Restaurant search: 500K QPS during peak (users browse before ordering)
- Menu reads: 2M QPS (most users browse 3-4 restaurants before ordering)

## Architecture Decisions

### Three-Sided Marketplace Dispatch
Unlike ride-hailing (two-sided: rider + driver), food delivery is three-sided: customer + restaurant + driver. The key timing challenge: the driver should arrive at the restaurant exactly when the food is ready. Dispatch too early = driver waits (costly idle time). Dispatch too late = food gets cold (bad customer experience). Solution: the Dispatch Engine uses the restaurant's estimated prep time to delay driver assignment. When the restaurant confirms the order with a 15-minute prep time, dispatch schedules driver assignment for T+10 minutes, targeting a 5-minute pickup buffer.

### Batched Dispatching Over Single-Order Assignment
Instead of assigning drivers to orders one-at-a-time, the Dispatch Engine batches orders in a geographic area and runs a matching optimization every 30 seconds. This allows multi-pickup routes (one driver picks up from 2 restaurants on the way) and reduces total fleet miles. The optimization is a variant of the assignment problem (Hungarian algorithm or heuristic greedy). Single-order assignment is simpler but misses 15-20% of batching opportunities.

### Restaurant Search: Location + Availability Indexing
A restaurant is relevant only if it's (1) near the customer, (2) currently open, (3) delivering to the customer's area, and (4) within acceptable delivery time. We index restaurants in Elasticsearch with geo_point fields and filter on operating hours, delivery radius, and real-time availability (is the restaurant currently accepting orders?). The availability flag is updated in real-time via Kafka events (restaurant goes offline, becomes too busy). This is different from pure geospatial search — a restaurant 1km away with 60-minute prep time is worse than one 3km away with 15-minute prep time.

### Order State Machine with Event Sourcing
An order goes through: PLACED -> CONFIRMED -> PREPARING -> READY_FOR_PICKUP -> DRIVER_ASSIGNED -> PICKED_UP -> EN_ROUTE -> DELIVERED (or CANCELLED/REFUNDED at various points). Each state transition is an event in Kafka. Every stakeholder (customer, restaurant, driver) gets real-time updates via WebSocket. The event log enables: (1) full audit trail; (2) analytics on where orders get stuck (average time in PREPARING state per restaurant); (3) automatic escalation (if order is in PREPARING for > 2x estimated prep time, alert customer and offer cancel option).

## Component Breakdown

- **Customer App**: Browse, search, order, track. Shows ETA with confidence intervals.
- **Restaurant Portal**: Accept/reject orders, update prep times, manage menu/hours, pause when overwhelmed.
- **Driver App**: Receive and accept deliveries, GPS navigation, confirm pickup/delivery, earnings dashboard.
- **API Gateway**: Routes to services, handles auth for three different user types (customer, restaurant, driver).
- **WebSocket Gateway**: Real-time updates to all three parties. Order status + driver location.
- **Restaurant Service**: Restaurant CRUD, operating hours, delivery zones, real-time availability (is kitchen open?).
- **Menu Service**: Menu items, categories, prices, customizations, dietary tags. Cached aggressively since menus change rarely.
- **Order Service**: Order lifecycle management, state machine, saga coordinator for the checkout flow.
- **Dispatch Engine**: The brain. Batched matching of orders to drivers. Considers: driver proximity to restaurant, restaurant-to-customer distance, driver current load (already carrying an order?), prep time, and driver rating.
- **Location Service**: Ingests driver GPS. Feeds geospatial index for dispatch and real-time tracking for customers.
- **Payment Service**: Customer charge, restaurant payout (minus commission), driver payout (base + tip). Complex multi-party settlement.
- **ETA Calculator**: Predicts delivery time = prep time (from restaurant or ML model) + driver-to-restaurant travel + restaurant-to-customer travel. Updates ETA dynamically as driver location changes.
- **Pricing Engine**: Delivery fee calculation based on distance, demand, time of day. Surge pricing during peak hours.
- **Notification Worker**: Push notifications: "Order confirmed", "Driver is picking up", "Driver is 2 minutes away".

## Data Model

### Restaurants (MySQL)
- PK: restaurant_id
- Columns: name, address, lat/lng, cuisine_tags, avg_rating, delivery_radius_km, min_order_amount, commission_rate, is_active
- Elasticsearch mirror with geo_point for search

### Menu Items (MySQL)
- PK: item_id
- Columns: restaurant_id, name, description, price, category, image_url, customizations (JSON), is_available
- Index: (restaurant_id, category)

### Orders (MySQL, sharded by order_id)
- PK: order_id
- Columns: customer_id, restaurant_id, driver_id, items (JSON), subtotal, delivery_fee, tip, total, status, estimated_delivery_at, placed_at, confirmed_at, picked_up_at, delivered_at
- Index: (customer_id, placed_at DESC), (restaurant_id, placed_at DESC), (driver_id, placed_at DESC)

### Driver Locations (Redis geospatial)
- Key: `drivers:active:{h3_cell}` -> Set of driver_ids
- Per-driver: `driver:{id}:location` -> {lat, lng, heading, speed, current_order_ids, status}

### Delivery Zones (PostgreSQL + PostGIS)
- restaurant_id, zone_polygon (geometry), delivery_fee_base, max_delivery_time_min

## Key Trade-offs

- **Batch dispatch vs instant dispatch**: Batching (every 30 seconds) finds better global matches but delays individual orders by up to 30 seconds. During low-demand periods, we switch to instant dispatch to avoid unnecessary delay. The threshold is configurable per market.
- **Restaurant-estimated vs ML-predicted prep time**: Restaurants often under-estimate prep time (to appear faster in search) or over-estimate (to have buffer). ML models trained on historical data (actual time from confirmed to ready) are more accurate but require sufficient data per restaurant. We use restaurant estimates with ML correction: `predicted = restaurant_estimate * correction_factor`.
- **Commission vs subsidized delivery**: Higher commission from restaurants funds lower delivery fees, attracting customers. But too-high commission drives away restaurants. The marketplace balance is a business decision, but technically, the Pricing Engine must support complex multi-variable fee structures.
- **Driver payout model**: Per-delivery fixed fee vs per-mile/per-minute variable fee. Fixed is simpler but unfair for long-distance deliveries. Variable requires accurate tracking and calculation but better incentivizes distant deliveries.

## What Fails First

**Dispatch Engine during dinner rush.** The batched matching algorithm runs every 30 seconds on all unassigned orders in a market. During dinner rush, a large city might have 5,000 unassigned orders and 10,000 available drivers. The matching optimization is O(n*m) at best — 50M comparisons per batch. Solutions: (1) Partition by geographic zone (divide the city into 20 zones, run independent dispatch per zone); (2) Pre-filter candidates to a small radius before optimization; (3) Use greedy heuristics instead of optimal matching during peak load (sacrifice 5% efficiency for 10x speed).

## v1 vs v2

### v1 (MVP)
- Single city, curated restaurant list
- Static menus (no real-time availability)
- Instant single-order dispatch (nearest available driver)
- Fixed delivery fee
- Basic order tracking (status text, no map)
- Restaurant-provided prep time estimates
- PostgreSQL for everything
- Stripe for payments (customer charge only, manual restaurant payouts)

### v2
- Multi-city with market-specific configuration
- Real-time menu availability (item sold out)
- Batched dispatch with multi-pickup optimization
- Dynamic delivery fee based on distance and demand
- Live driver tracking on map via WebSocket
- ML-predicted prep times and delivery ETAs
- Automated three-party settlement (customer -> platform -> restaurant + driver)
- Scheduled orders
- Loyalty program and subscription (DashPass model)
- Restaurant analytics dashboard (popular items, peak hours, avg prep time)
- Group orders (multiple people ordering from same restaurant)
