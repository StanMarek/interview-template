# Ticket Booking Platform — Architecture Design

## Requirements

### Functional
- Browse events (concerts, sports, theater) with seat maps
- Search events by name, date, venue, category
- Select seats and add to cart with temporary hold
- Checkout with payment processing
- E-ticket delivery (email, in-app QR code)
- Waitlist for sold-out events
- Resale/transfer tickets
- Venue and event management for organizers

### Non-Functional
- No double-booking (strong consistency for seat inventory)
- Seat hold TTL: 10 minutes (release if not paid)
- Support 100K concurrent users during flash sales (Taylor Swift scenario)
- Payment processing < 5 seconds
- 99.99% availability during on-sale events
- Read-heavy for browsing (1000:1 browse:purchase ratio)

## Scale Estimates
- 50M DAU browsing, 500K purchases/day average
- Flash sale scenario: 10M users competing for 50K seats in 60 seconds
- Peak QPS during flash sale: 500K seat-check requests/second, 50K booking attempts/second
- Average event: 10K seats, 500 events/day
- Storage: relatively modest — metadata-heavy, not media-heavy

## Architecture Decisions

### Virtual Queue for Flash Sales
The biggest architectural challenge is flash sales: millions of users hitting the system simultaneously for limited inventory. Without protection, the backend collapses. Solution: a **virtual queue** at the edge layer. When traffic exceeds a threshold, users enter a FIFO queue and receive a queue position and estimated wait time. Users are admitted to the booking flow in controlled batches (e.g., 5000 users at a time). The queue is implemented as a Redis sorted set (score = join timestamp). This converts a thundering herd into a controlled flow, and the backend only needs to handle the batch size, not the total demand.

### Pessimistic Locking with TTL for Seat Holds
When a user selects seats, we need to prevent others from booking the same seats during the payment window. Two approaches: (1) **Optimistic** — let everyone select, fail at payment time if someone else completed first; (2) **Pessimistic** — lock seats when selected, release after TTL or payment. We choose pessimistic because the user experience of "completing a 5-minute checkout flow only to be told the seat is gone" is terrible. Implementation: Redis SET with NX (set-if-not-exists) and EX (TTL of 600 seconds). The key is `seat:{eventId}:{seatId}`, value is `bookingId`. If the SET returns false, the seat is taken. If the TTL expires, the lock auto-releases.

### Two-Phase Booking: Reserve Then Confirm
The booking flow is: (1) Reserve — lock seats in Redis, create a pending booking record; (2) Confirm — process payment, mark booking as confirmed, generate tickets. If payment fails, the booking is cancelled and seats are released. If the booking service crashes between reserve and confirm, the TTL releases the lock automatically, and a cleanup worker marks abandoned bookings as cancelled. This ensures we never have ghost locks or orphaned bookings.

### Event-Sourced Inventory
Instead of a mutable `available_seats` counter, we model inventory as an event log: SeatReserved, SeatReleased, BookingConfirmed, BookingCancelled. The current availability is a materialized view derived from the event log. Benefits: full audit trail (critical for financial compliance), easy to debug "where did the inventory go" problems, and natural fit for the reserve/confirm two-phase model. The trade-off: slightly more complex reads (need to materialize the view), but we cache the materialized view in Redis.

## Component Breakdown

- **CDN**: Serves venue images, seat maps, static content. Seat map SVGs can be cached aggressively.
- **Rate Limiter**: Per-user rate limiting to prevent bots. CAPTCHA integration for flash sales. IP-based throttling.
- **Virtual Queue**: Redis-backed waiting room. Admits users in FIFO batches. Shows real-time position and ETA via SSE.
- **API Gateway**: Routes requests, handles auth, applies rate limits.
- **Event Service**: CRUD for events, venues, seat maps. Read-heavy, cached aggressively.
- **Inventory Service**: The critical service. Manages seat availability, handles lock/release, maintains consistency. Single source of truth for "is this seat available?"
- **Booking Service**: Orchestrates the two-phase booking flow. Creates booking records, coordinates with inventory and payment.
- **Payment Service**: Integrates with Stripe/PayPal. Handles idempotent payment processing, refunds, and webhook callbacks.
- **Notification Service**: Sends confirmation emails, e-tickets (QR codes), and reminder notifications.
- **Search Service**: Elasticsearch-backed search for events by name, date, venue, category, location.
- **Redis Seat Locks**: SET NX EX pattern for temporary seat holds. The most critical infrastructure component during flash sales.
- **Delay Queue**: Triggers seat release after TTL expiry (backup for Redis TTL, handles edge cases).

## Data Model

### Events (MySQL)
- PK: event_id
- Columns: venue_id, name, description, category, date_time, on_sale_time, status
- Index: (date_time, category), (venue_id)

### Seats (MySQL)
- PK: (event_id, seat_id)
- Columns: section, row, number, price_tier, status (available/locked/sold)
- The status is a materialized view of the event log

### Bookings (MySQL, sharded by booking_id)
- PK: booking_id
- Columns: user_id, event_id, seats (JSON array), status (pending/confirmed/cancelled), total_amount, created_at, expires_at
- Index: (user_id, created_at DESC)

### Payments (MySQL)
- PK: payment_id
- Columns: booking_id, stripe_payment_intent_id, amount, currency, status, created_at
- Index: (booking_id)

### Inventory Events (Kafka / append-only table)
- event_id, seat_id, action (reserved/released/confirmed/cancelled), booking_id, timestamp

## Key Trade-offs

- **Pessimistic vs optimistic locking**: Pessimistic locking (our choice) wastes inventory — users lock seats then abandon carts. With a 10-minute TTL and 30% abandonment, ~30% of locked seats are temporarily unavailable to other buyers. But the UX is far better than optimistic locking's "sorry, gone" at checkout.
- **Virtual queue vs over-provisioning**: The queue adds user-facing latency (waiting) but protects backend stability. Over-provisioning the backend for peak flash-sale load would require 100x normal capacity that sits idle 99.9% of the time.
- **Strong consistency vs availability for inventory**: We sacrifice some availability for inventory consistency (no double-booking). If the Redis cluster for seat locks goes down, booking fails rather than allowing double-sells. This is the correct choice — overselling has legal and reputational consequences.
- **TTL duration**: 10 minutes balances "enough time to complete payment" vs "don't lock inventory too long." Shorter TTL improves throughput but frustrates slow users; longer TTL reduces effective inventory.

## What Fails First

**Redis seat lock cluster during flash sales.** A 50K-seat event with 500K concurrent booking attempts means 500K SET NX operations per second on a single logical keyspace (the event). Redis is single-threaded per shard — all seats for one event hit the same shard. Solution: pre-shard seats by section across multiple Redis instances (section A on shard 1, section B on shard 2). Also use Redis Cluster with hash tags: `{event:123}:seat:A1` to co-locate an event's seats while distributing across events.

## v1 vs v2

### v1 (MVP)
- Browse events with basic listing (no seat map)
- General admission tickets only (no seat selection)
- Simple inventory counter (decrement on purchase)
- Stripe Checkout (redirect, not embedded)
- Email confirmation with PDF ticket
- PostgreSQL for everything
- No virtual queue (rate limiting only)

### v2
- Interactive seat map with real-time availability
- Pessimistic seat locking with Redis
- Virtual queue for flash sales
- Embedded payment with Apple Pay / Google Pay
- QR code e-tickets in app
- Waitlist and price alerts
- Resale marketplace
- Dynamic pricing based on demand
- Bot detection and CAPTCHA
- Event-sourced inventory with audit trail
