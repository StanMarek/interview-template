# E-Commerce Cart & Checkout System — Architecture Design

## Requirements

### Functional
- Browse product catalog with search and filters
- Add/remove items to cart, update quantities
- Persistent cart (survives logout, cross-device)
- Guest cart that merges on login
- Apply promo codes and coupons
- Checkout: shipping address, delivery options, payment
- Order confirmation and tracking
- Inventory reservation during checkout
- Multiple payment methods (card, wallet, buy-now-pay-later)

### Non-Functional
- Cart operations < 100ms (add/remove must feel instant)
- Checkout completion < 3 seconds (including payment)
- No overselling (inventory consistency)
- 99.99% availability for cart and catalog (revenue-critical)
- Support 10M concurrent carts during sales events (Black Friday)
- Eventually consistent for catalog reads; strong consistency for inventory and orders

## Scale Estimates
- 200M DAU, 500M products in catalog
- Average cart: 5 items, 50M active carts at peak
- Cart operations: 100M add/remove/update per day = ~5K QPS average, 50K peak
- Checkout attempts: 10M/day, 80% completion rate = 8M orders/day
- Catalog reads: 1B/day = 50K QPS average
- Black Friday: 5-10x normal traffic for 48 hours

## Architecture Decisions

### Cart in Redis with DB Backup
Carts are extremely hot — users add/remove items frequently, and the cart must render on every page. Storing carts in MySQL means database load for every page view. Instead: cart state lives in Redis (hash per user: field = product_id, value = quantity + metadata). Redis operations are sub-millisecond. We asynchronously persist cart snapshots to MySQL every 30 seconds and on significant changes (add item, remove item). If Redis fails, we fall back to the MySQL snapshot. Guest carts use a session cookie as the key.

### Saga Pattern for Checkout (Not 2PC)
Checkout spans multiple services: Cart -> Inventory -> Payment -> Order. A distributed transaction (2PC) across these services would be slow and fragile. Instead, we use an **orchestrated saga**: the Order Service coordinates the workflow. Steps: (1) Reserve inventory, (2) Charge payment, (3) Create order, (4) Send confirmation. If any step fails, compensating actions roll back previous steps (release inventory, refund payment). The saga state is persisted in the Orders DB so it survives crashes. Key insight: the saga is idempotent — re-executing a step that already succeeded is a no-op (checked by unique order_id).

### Inventory: Soft Reserve Then Hard Deduct
When a user starts checkout, we **soft-reserve** inventory (decrement `available_count`, increment `reserved_count`). This prevents overselling while the user enters payment details. If payment succeeds, we **hard-deduct** (decrement `reserved_count`, increment `sold_count`). If payment fails or the reservation expires (10 minutes), we release back to available. This is better than holding no reservation (risk of selling the last item to two people) or hard-deducting upfront (blocking inventory on abandoned checkouts).

### Separate Catalog Read Path
The product catalog is read 1000x more than it's updated. We serve catalog reads from a denormalized read store (Elasticsearch for search, Redis for product detail pages) that's eventually consistent with the source-of-truth MySQL catalog. Catalog updates (price changes, new products) are published to Kafka and consumed by the read stores. This means a price change takes 1-2 seconds to propagate — acceptable for catalog data. Critical data (actual charge amount) always comes from the source of truth at checkout time.

## Component Breakdown

- **CDN**: Product images, static assets. Aggressive caching for product thumbnails.
- **Session Service**: Manages guest sessions (cookie-based) and authenticated sessions. Used to link guest carts to logged-in users.
- **API Gateway**: Routes to services, rate limiting (especially during sales), auth.
- **Catalog Service**: Product CRUD, category management, pricing. Source of truth in MySQL, read replicas + Elasticsearch for serving.
- **Cart Service**: Add/remove/update cart items. Cart validation (product exists, in stock, price check). Redis-first with MySQL backup.
- **Order Service**: Checkout orchestrator (saga coordinator). Creates order records, manages order lifecycle (placed -> processing -> shipped -> delivered).
- **Inventory Service**: Real-time stock levels. Soft reserve / hard deduct / release operations. The most consistency-critical service.
- **Payment Service**: Integrates with Stripe, PayPal, BNPL providers. Idempotent charge/refund operations. Webhook handling for async payment confirmations.
- **Promo/Coupon Service**: Validates and applies promo codes. Handles single-use, multi-use, percentage, fixed-amount, minimum-spend rules. Prevents double-use via Redis SET NX.
- **Order Processor Worker**: Post-checkout async work: generate invoice, notify warehouse, update inventory, trigger shipping label.
- **Email Worker**: Order confirmation, shipping updates, abandoned cart reminders.
- **Fraud Checker**: Async fraud scoring on orders. Flags suspicious orders for manual review (unusual shipping address, high-value first order, velocity checks).

## Data Model

### Products (MySQL, sharded by product_id)
- PK: product_id
- Columns: name, description, price, category_id, seller_id, images (JSON), attributes (JSON), status, created_at
- Index: (category_id), (seller_id)

### Inventory (MySQL, sharded by product_id)
- PK: (product_id, warehouse_id)
- Columns: available_count, reserved_count, sold_count, version (optimistic locking)
- Critical: `available_count >= 0` constraint enforced at DB level

### Carts (Redis hash + MySQL backup)
- Redis key: `cart:{user_id}` or `cart:{session_id}`
- Redis hash fields: product_id -> `{qty, added_at, price_at_add}`
- MySQL: user_id, cart_json, updated_at

### Orders (MySQL, sharded by order_id)
- PK: order_id
- Columns: user_id, items (JSON), subtotal, discount, tax, shipping, total, status, shipping_address, payment_id, saga_state, created_at
- Index: (user_id, created_at DESC)

### Payments (MySQL)
- PK: payment_id
- Columns: order_id, provider, provider_ref, amount, currency, status, created_at
- Unique index: (order_id) — one payment per order

### Promo Codes (MySQL)
- PK: promo_id
- Columns: code (unique), type (percentage/fixed), value, min_spend, max_uses, current_uses, valid_from, valid_to
- Used codes tracking: (promo_id, user_id) unique — prevents reuse

## Key Trade-offs

- **Cart in Redis vs DB**: Redis gives sub-millisecond reads but risks data loss if Redis fails before async MySQL write. We accept a small window (up to 30 seconds) of potential cart data loss in exchange for speed. Most users would simply re-add the item.
- **Soft reservation vs no reservation**: Soft reservations reduce effective inventory (items locked by users who never complete checkout). With a 10-minute TTL and 20% abandonment, ~2% of inventory is temporarily unavailable. But without reservations, the last-item-sold-twice problem creates customer support nightmares.
- **Saga vs synchronous checkout**: Sagas add complexity (compensating actions, idempotency tokens, state management) but prevent cascading failures. A synchronous call chain where one service is slow blocks the entire checkout. The saga approach means the user sees "Order Placed" quickly, and background processing handles the rest.
- **Exact inventory count vs approximate**: We show approximate stock ("In Stock" / "Only 3 left" / "Out of Stock") based on cached data. The exact availability is checked at checkout time from the source of truth. This reduces load on the inventory DB by 99% at the cost of occasional "sorry, this went out of stock" at checkout.

## What Fails First

**Inventory Service during flash sales.** When a popular item drops (Supreme, limited-edition sneakers), thousands of concurrent checkout attempts hit the inventory service for the same product_id. With optimistic locking, most writes fail and retry, creating a retry storm. Solutions: (1) Use Redis DECR for real-time inventory (atomic, single-threaded, no contention) with async DB reconciliation; (2) Pre-shard high-demand items across multiple inventory slots; (3) Rate limit checkout attempts per product using a token bucket.

## v1 vs v2

### v1 (MVP)
- Simple product catalog with PostgreSQL + basic search
- Cart stored in PostgreSQL
- Synchronous checkout (cart -> inventory check -> Stripe charge -> order)
- Email confirmation
- Single warehouse, no shipping options
- No promo codes
- No guest checkout

### v2
- Redis cart with cross-device sync
- Elasticsearch-powered catalog search with faceted filters
- Saga-based async checkout with inventory reservation
- Multiple payment methods (card, wallet, BNPL)
- Promo codes and tiered discounts
- Guest cart with merge on login
- Fraud detection pipeline
- Abandoned cart recovery emails
- Multi-warehouse inventory with nearest-warehouse routing
- Real-time stock level indicators ("Only 2 left!")
