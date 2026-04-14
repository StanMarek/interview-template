# System Design Interview Practice Tasks

A scored drill system for backend and senior engineering interviews (2025/2026 hiring bar). Every prompt is designed to be **re-runnable** — you practice the same system multiple times at different time budgets and with different twists, score yourself, and watch the score climb as your reflexes improve.

## How To Use This File

Each task is a self-contained drill card with:

1. **Time-budget variants** — 20 / 35 / 45 minute scopes. Pick the one that matches your actual prep session.
2. **Scoring rubric** — 4 dimensions, 0-5 each (20 points total). Score yourself out of 20 or, better, have a peer score you.
3. **Twist cards** — interviewer curveballs to throw in mid-session. These simulate senior-level follow-ups that separate "memorized the design" from "can actually think".
4. **Red flags** — specific anti-patterns for this problem. If you find yourself drifting toward one, stop and course-correct.

The existing worked solutions and architecture diagrams live in [`/diagrams`](diagrams/); treat them as **answer keys**, not scripts. Look only after your own attempt.

## Master Rubric Legend

Each problem is scored on four dimensions, 0-5 each.

### R1 — Requirements Clarification (0-5)
- **0** — started drawing boxes immediately.
- **1** — asked one or two surface questions, missed core scope.
- **2** — listed functional requirements only.
- **3** — functional + non-functional (latency, availability, consistency), named 1 scale figure.
- **4** — as above + explicit out-of-scope list, named the critical SLO.
- **5** — as above + pushed back on an ambiguous requirement, negotiated v1 vs v2 cut.

### R2 — High-Level Design (0-5)
- **0** — no diagram, or a diagram that doesn't match the requirements.
- **1** — boxes drawn, unclear data flow.
- **2** — boxes + request path, but storage or async paths missing.
- **3** — clear client-to-storage path, main components labeled, one sequence diagram.
- **4** — as above + async/queue paths, partition key chosen, API shape named.
- **5** — as above + justified every component's existence ("why not remove X?"), identified the hardest sub-problem.

### R3 — Scale and Capacity Reasoning (0-5)
- **0** — no numbers.
- **1** — one hand-wave ("it's big").
- **2** — QPS calc, no storage or bandwidth.
- **3** — QPS + storage + bandwidth, average only.
- **4** — as above + peak:average ratio, hot key / hot partition analysis, 1-year horizon.
- **5** — as above + identified the first bottleneck to break, sized a component concretely (N shards, X GB cache, Y replicas), reasoned about 10x growth.

### R4 — Deep-Dive and Trade-offs (0-5)
- **0** — no trade-offs named.
- **1** — mentioned one trade-off, did not commit to a side.
- **2** — picked one of: consistency model, replication strategy, caching strategy, and defended it.
- **3** — picked two, tied each to a requirement, named the downside of their choice.
- **4** — walked through a failure mode end-to-end (partial failure, grey failure, retry storm).
- **5** — as above + named what they'd cut from v1, named the monitoring signal that would catch degradation, named the next architectural evolution at 10x.

**Scoring bands**: 16-20 excellent / senior-ready, 12-15 solid mid-level, 8-11 needs rework, <8 repeat the drill.

## Suggested Cadence

For a 4-8 week interview prep ramp, aim for:

- **1 × 45-minute full session per week** — pick a new system, force yourself through all four phases.
- **2 × 20-minute lightning rounds per week** — revisit systems you've already seen; focus on speed and first-principles recall.
- **1 × 35-minute session every other week** — a previously-done system with 2-3 twist cards mixed in. This is the one that actually builds "senior reflexes".

Rotate categories: don't do four reliability problems in a row. Mix a Core Staple, a Product System, and a Streaming System each week so you stay sharp across storage, async, and coordination.

## Time-Budget Templates

Use these as the default scope for each variant; per-task notes below tighten or loosen them.

### 20-minute (Lightning)
- 2 min clarifying questions.
- 3 min back-of-envelope.
- 8 min high-level diagram.
- 5 min pick ONE deep-dive (usually data model or the hot path).
- 2 min trade-offs and what you'd cut.
- **Goal**: prove you can frame and sketch under pressure.

### 35-minute (Standard)
- 4 min requirements (including non-functional).
- 5 min back-of-envelope.
- 12 min high-level + sequence diagram.
- 10 min deep-dive on 2 components.
- 4 min failure modes and trade-offs.
- **Goal**: the real interview loop — most real interviews are 35-45 min of technical time.

### 45-minute (Full)
- 6 min requirements (functional, non-functional, out-of-scope, SLOs).
- 5 min back-of-envelope including 10x growth.
- 14 min high-level + 2 sequence diagrams.
- 15 min deep-dive on 2-3 components + failure modes.
- 5 min trade-offs, v1-vs-v2, what you'd monitor.
- **Goal**: the senior-loop experience — expect twist cards.

## What To Draw For Every Exercise

- Context diagram: clients, APIs, core services, storage, async components.
- Sequence diagram: one critical request path.
- Data model sketch: key entities, indexes, partition keys.
- Scaling view: caches, queues, replicas, shard boundaries, read/write split.
- Reliability view: retries, DLQ, circuit breaker, failover, disaster recovery.

## Universal Twist Cards (Applicable To Most Problems)

Pull one of these at the 25-minute mark of any 45-minute session, or at the start of any 35-minute re-drill. Pattern references point to the deep-dive material you should be drawing from.

- **"Now make it multi-region active-active."** → [cap-theorem.md](patterns/cap-theorem.md), [replication.md](patterns/replication.md).
- **"The regulator requires GDPR right-to-delete within 30 days."** → data lineage, soft-delete vs hard-delete, cascades to caches and analytics.
- **"One user has 100M followers / 1000x the average load."** — celebrity / hot-key problem. → fan-out-on-write vs read, per-key caching, dedicated shards.
- **"Consistency model must be stronger than what you proposed."** → [consensus-and-quorums.md](patterns/consensus-and-quorums.md), [concurrency-control.md](patterns/concurrency-control.md).
- **"Traffic just 10x'd overnight."** → what scales linearly, what doesn't, what melts first.
- **"Budget is cut by 50% — drop components."** → forces prioritization.
- **"One AZ / one region is down for 4 hours."** → RTO, RPO, failover, cold vs warm standby.
- **"A poison message is stuck in the queue."** → DLQ policy, observability, manual replay.
- **"Tell me the monitoring story."** → SLIs/SLOs, alerts, dashboards, trace IDs.
- **"What breaks at 100x?"** — the interviewer's favorite.

---

## Core Interview Staples

Each numbered task links to a worked solution and architecture diagram in `/diagrams/core`. Use it as an answer key **after** your own attempt.

### 1. URL Shortener
→ [solution](diagrams/core/01_url_shortener.md)

**Time budgets**
- **20 min** — API + ID generation strategy + storage schema. No analytics, no custom aliases.
- **35 min** — + read path caching, hot-key handling, expiry.
- **45 min** — + custom aliases, analytics pipeline, abuse prevention, multi-region reads.

**Scoring rubric** — R1 + R2 + R3 + R4 (see master rubric).

**Twist cards**
- "Every short URL must be exactly 7 characters." (ID space math matters)
- "Add click analytics with 1-second freshness."
- "A celebrity tweets a short URL that gets 1M QPS."
- "We need vanity URLs (`/myBrand`) to coexist with generated ones."
- "GDPR: user can delete their short URLs and all click history."

**Red flags**
- Using `AUTO_INCREMENT` IDs then base62-encoding (predictable, leaks growth rate).
- No mention of the read:write ratio — this is fundamental here.
- Ignoring cache invalidation on URL update/delete.
- Hashing the long URL as the key without handling collisions.

---

### 2. Rate Limiter
→ [solution](diagrams/core/02_rate_limiter.md)

**Time budgets**
- **20 min** — pick one algorithm (token bucket or sliding window), single-node implementation, API.
- **35 min** — + distributed state (Redis), failure modes, client headers.
- **45 min** — + multi-tier limits (user / API key / global), multi-region, cost-at-scale.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Redis is down. What happens?"
- "Enterprise customers pay for custom per-endpoint limits — make it configurable without a deploy."
- "One customer is using 90% of the global budget — isolate them."
- "Limit must survive a region failure with zero data loss."
- "Add a burst allowance that refills over 24 hours."

**Red flags**
- Picking "fixed window" without acknowledging the boundary-burst problem.
- No mention of clock skew or Redis replication lag affecting correctness.
- Hardcoded limits with no config story.
- Not returning `Retry-After` or `RateLimit-*` headers. See [api-design-and-pagination.md](patterns/api-design-and-pagination.md).

---

### 3. Distributed Cache
→ [solution](diagrams/core/03_distributed_cache.md)

**Time budgets**
- **20 min** — cluster topology, client routing (consistent hashing), eviction.
- **35 min** — + replication, failover, cache invalidation strategy.
- **45 min** — + multi-region, cache warming, cold start, write-through vs write-back deep dive.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "A single key is 10x hotter than all others."
- "Two clients race on the same key during a cache miss — stampede."
- "Add cross-region read replication with <100 ms staleness."
- "A node dies mid-rehash."
- "Need monotonic reads for a given user session."

**Red flags**
- "We'll just use Redis" without addressing partitioning.
- No discussion of consistent hashing or equivalent rebalancing strategy. See [consistent-hashing.md](patterns/consistent-hashing.md).
- Missing the thundering herd / dog-pile problem.
- Treating eviction policy as an afterthought.

---

### 4. Notification Service (email, SMS, push)
→ [solution](diagrams/core/04_notification_service.md)

**Time budgets**
- **20 min** — API, queue, per-channel workers, basic retries.
- **35 min** — + templating, user preferences, dedup, DLQ.
- **45 min** — + prioritization, scheduling, rate limits per vendor, idempotency, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "User unsubscribes from marketing but must still receive transactional alerts."
- "Twilio is down; SendGrid is up. Failover policy?"
- "Marketing wants to send 100M push in 10 minutes."
- "GDPR right-to-delete — wipe a user's notification history and preferences."
- "Same notification was queued twice due to an upstream retry."

**Red flags**
- No idempotency story — notifications must not fire twice. See [idempotency.md](patterns/idempotency.md).
- Treating transactional and marketing traffic as one priority class.
- No DLQ / no plan for poison messages.
- No per-vendor rate limiting (Twilio, SES, FCM all have their own).

---

### 5. File Upload and Image Processing
→ [solution](diagrams/core/05_file_upload_image_processing.md)

**Time budgets**
- **20 min** — pre-signed upload URL, metadata DB, async worker for derivatives.
- **35 min** — + CDN integration, thumbnail pipeline, virus scanning hook.
- **45 min** — + multi-region, resumable uploads, large-file chunking, cost optimization.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Uploads can be 10 GB."
- "Detect and block NSFW content before the URL is public."
- "Thumbnail pipeline is 10 minutes behind — users complain."
- "Pre-signed URL leaks — prevent abuse."
- "Need to serve the same file from 3 regions with minimal egress cost."

**Red flags**
- App server acts as a proxy for uploads (kills throughput).
- Synchronous image processing on the upload request.
- No mention of pre-signed URLs.
- Derivatives stored without content-addressable keys (can't dedupe).

---

### 6. Search Autocomplete
→ [solution](diagrams/core/06_search_autocomplete.md)

**Time budgets**
- **20 min** — trie / prefix index + ranking strategy.
- **35 min** — + freshness pipeline, personalization, cache tier.
- **45 min** — + multi-language, typo tolerance, global deployment, query logging pipeline.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "P99 must be under 100 ms globally."
- "Trending topics should surface within 60 seconds."
- "Personalize to the user's history."
- "Latin + CJK + RTL scripts must all work."
- "Autocomplete must not leak private documents the user can't see."

**Red flags**
- Full-text search engine for what is really a prefix problem.
- No edge / CDN caching story.
- No mention of Top-K maintenance under prefix.
- Ignoring the log -> model -> serving feedback loop.

---

### 7. API Gateway
→ [solution](diagrams/core/07_api_gateway.md)

**Time budgets**
- **20 min** — routing, auth, rate limiting, at high level.
- **35 min** — + request transformation, retries, timeout policies, observability.
- **45 min** — + multi-tenant, plugin architecture, zero-downtime deploys of config, mTLS to backends.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Customer-specific routing rules must update in <30 seconds globally."
- "One noisy tenant is starving others."
- "We need to migrate a path from service A to service B with 1% canary."
- "JWT verification is adding 30 ms P99 — fix it."
- "Region failover — how does the gateway itself failover?"

**Red flags**
- Putting business logic in the gateway.
- Synchronous fan-out to backends with no circuit breaker. See [circuit-breaker.md](patterns/circuit-breaker.md).
- No config reload story (requires restart).
- Missing mTLS / zero-trust discussion for internal traffic.

---

### 8. Feature Flag Service
→ [solution](diagrams/core/08_feature_flag_service.md)

**Time budgets**
- **20 min** — flag model, evaluation API, SDK caching.
- **35 min** — + targeting rules, percentage rollouts, audit log.
- **45 min** — + experimentation (A/B), streaming updates, multi-region consistency.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "A bad flag rollout caused an outage — how do you kill-switch in <30s globally?"
- "The flag service itself must never be in the critical path — plan for the service being down."
- "Audit: who changed what, when, to what value, and who saw which variant."
- "Sticky bucketing — a user must see the same variant forever."
- "Compliance: a flag value is PII and must not leak into logs."

**Red flags**
- SDK fetches on every flag check (not cached).
- No fail-safe default when the service is unreachable.
- No audit log.
- Percentage rollouts using non-deterministic hashing.

---

### 9. Webhook Delivery Platform
→ [solution](diagrams/core/09_webhook_delivery_platform.md)

**Time budgets**
- **20 min** — queue + worker, signature, basic retries.
- **35 min** — + DLQ, exponential backoff, per-tenant isolation, replay.
- **45 min** — + ordering guarantees, idempotency keys, observability, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "A customer's endpoint has been 500ing for 2 hours — don't let them block others."
- "Customer wants in-order delivery per entity."
- "Customer claims we never sent event X; prove it."
- "Our signing key is compromised — rotate globally."
- "Add at-least-once with deduplication on the customer side — document the contract."

**Red flags**
- Single shared queue for all customers.
- Infinite retries with no backoff.
- No signing / replay-attack protection.
- Missing the "slow consumer" failure mode. See [streaming-semantics.md](patterns/streaming-semantics.md).

---

### 10. Distributed Configuration Service
→ [solution](diagrams/core/10_distributed_config_service.md)

**Time budgets**
- **20 min** — KV store, versioned writes, client polling.
- **35 min** — + watch / streaming updates, ACLs, audit log.
- **45 min** — + multi-region consistency, staged rollouts, schema validation.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Config change must propagate in <5 seconds to 10K nodes."
- "Bad config crashes the fleet — how do you roll back?"
- "Secrets live here too — how is storage different?"
- "Regional overrides must exist without drift."
- "Schema breaking change — validate before write."

**Red flags**
- Rebuilding etcd / ZooKeeper instead of using one. See [consensus-and-quorums.md](patterns/consensus-and-quorums.md).
- No validation before write.
- Clients poll on every request.
- No bounded retry on watch reconnect.

---

## Product-Style Systems

Each links to a worked solution in `/diagrams/product`.

### 1. Chat / Messaging System (1:1, group, presence, read receipts)
→ [solution](diagrams/product/01_chat_system.md)

**Time budgets**
- **20 min** — WebSocket gateway, message fan-out, storage schema.
- **35 min** — + presence, typing indicators, push on offline, ordering.
- **45 min** — + group chats at scale, end-to-end encryption sketch, media, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Group chat with 100K members — broadcast efficiently."
- "Messages must appear in the same order on every device."
- "User has 5 devices online — sync reads and unread counts."
- "Regulator requires message export for a single user."
- "End-to-end encrypted — what does the server still know?"

**Red flags**
- DB write on every message with no batching at scale.
- Presence via polling.
- No per-conversation partition key.
- Fan-out-on-write for huge groups (use fan-out-on-read or hybrid).

---

### 2. News Feed / Timeline
→ [solution](diagrams/product/02_news_feed.md)

**Time budgets**
- **20 min** — post model + feed generation choice (push vs pull).
- **35 min** — + ranking, cache tier, celebrity handling.
- **45 min** — + multi-region, personalization loop, offline / catch-up.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "One user has 100M followers."
- "Feed ranking must incorporate ML signals with <1s freshness."
- "Users scroll infinitely — pagination?"
- "Mute / block lists must apply consistently."
- "A deleted post must vanish from cached feeds within 60s."

**Red flags**
- Pure fan-out-on-write with no celebrity handling.
- Offset pagination on a feed. See [api-design-and-pagination.md](patterns/api-design-and-pagination.md).
- No separation of ingestion and serving paths.
- No answer for "what does the home feed look like at 3am when nothing new happened."

---

### 3. Photo Sharing (Instagram-style)
→ [solution](diagrams/product/03_photo_sharing.md)

**Time budgets**
- **20 min** — upload + storage + feed.
- **35 min** — + thumbnails pipeline, CDN, followers graph.
- **45 min** — + stories (24h TTL), comments at scale, ranking, abuse detection.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Stories auto-expire in 24 hours — how?"
- "A viral post gets 100K comments in an hour — storage & read model?"
- "Celebrity fan-out, again — do it differently than News Feed."
- "Right-to-delete cascades to thumbnails, caches, CDN, analytics."
- "Image moderation must happen before the post is visible."

**Red flags**
- Treating it as News Feed with images — different scale characteristics for media.
- No CDN strategy.
- Storing images in the same DB as metadata.
- No lifecycle / archival plan.

---

### 4. Video Streaming (YouTube-lite)
→ [solution](diagrams/product/04_video_streaming.md)

**Time budgets**
- **20 min** — upload, transcoding pipeline, HLS/DASH, CDN.
- **35 min** — + watch history, recommendations feed, thumbnails.
- **45 min** — + live streaming path, DRM, global delivery, cost per minute watched.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Add live streaming with <5s glass-to-glass."
- "Creator deletes a video — must disappear globally in <1 min."
- "Copyright takedown workflow."
- "Mobile users on flaky networks — adaptive bitrate."
- "DRM for premium content."

**Red flags**
- Synchronous transcoding.
- No ABR ladder discussion.
- CDN as an afterthought (it's the core of the system).
- Missing the "long tail of storage vs short head of popularity" trade-off.

---

### 5. Collaborative Editing (Google Docs)
→ [solution](diagrams/product/05_collaborative_editing.md)

**Time budgets**
- **20 min** — OT or CRDT sketch, sync protocol, persistence.
- **35 min** — + presence, cursors, offline edit reconciliation.
- **45 min** — + large docs, access control, version history, comments.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Two users edit offline and come back online an hour later."
- "Document is 10 MB long; pagination of the editing surface."
- "Need undo/redo per-user across a shared doc."
- "Comments must be anchored even as the doc changes around them."
- "Fine-grained ACL — per-paragraph permissions."

**Red flags**
- Last-write-wins as the core strategy.
- Per-keystroke locking. See [concurrency-control.md](patterns/concurrency-control.md).
- No answer for offline editing.
- Confusing OT and CRDT as the same thing without knowing the trade-offs.

---

### 6. Ticket Booking
→ [solution](diagrams/product/06_ticket_booking.md)

**Time budgets**
- **20 min** — seat model, hold/reserve, expiry.
- **35 min** — + payment integration, anti-double-booking, queue at the door.
- **45 min** — + multi-region, inventory accuracy, fraud, celebrity show surge.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Taylor Swift drop — 1M users hit `/checkout` in 10 seconds."
- "Payment succeeded but the seat was already taken — what does the user see?"
- "Resale market — a seat goes back into inventory."
- "Concert is in 30 minutes — do you allow bookings or close?"
- "Regulator requires audit of every seat assignment."

**Red flags**
- OCC on the hot seat rows (retry storm). See [concurrency-control.md](patterns/concurrency-control.md).
- No hold / reservation TTL.
- Synchronous payment in the request path with DB row locked.
- Missing the waiting-room / queue-at-the-door pattern.

---

### 7. Ride-Hailing
→ [solution](diagrams/product/07_ride_hailing.md)

**Time budgets**
- **20 min** — driver geo index, match algorithm, trip state machine.
- **35 min** — + pricing, ETA, cancellations.
- **45 min** — + dispatch optimization, surge pricing, driver preferences, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "10k drivers in Manhattan, updating location every 4s — what's the hot shard?"
- "Match must complete in <2s end-to-end."
- "Driver goes offline mid-trip."
- "Surge pricing must react to demand within 30s."
- "Two riders want the same driver at the same instant."

**Red flags**
- Generic DB for geo queries (use geohash / H3 / quadtree / PostGIS).
- Polling for driver location at match time.
- Match service as a single-leader bottleneck.
- No state machine for the trip lifecycle.

---

### 8. E-commerce Cart and Checkout
→ [solution](diagrams/product/08_ecommerce_cart.md)

**Time budgets**
- **20 min** — cart model (logged-in vs guest), checkout API.
- **35 min** — + inventory holds, payment integration, order state machine.
- **45 min** — + multi-warehouse inventory, promotions engine, tax, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Item goes out of stock between cart and checkout."
- "Abandoned cart recovery — notify at 24h."
- "Apply a promo code — must be idempotent and atomic with checkout."
- "Guest cart merges with logged-in cart."
- "Order created but payment webhook is late — what does the user see?"

**Red flags**
- Cart in a relational DB with no TTL or abandonment story.
- No idempotency on checkout. See [idempotency.md](patterns/idempotency.md).
- Inventory decrement outside the order transaction.
- No saga / outbox for the multi-step checkout. See [saga-pattern.md](patterns/saga-pattern.md), [outbox-pattern.md](patterns/outbox-pattern.md).

---

### 9. Restaurant Delivery Platform
→ [solution](diagrams/product/09_restaurant_delivery.md)

**Time budgets**
- **20 min** — order flow, courier matching, state machine.
- **35 min** — + restaurant dashboard, ETA prediction, batching.
- **45 min** — + multi-city dispatch, surge, SLA guarantees, payments to 3 parties.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Restaurant is 15 min late — who gets notified, and what happens to the courier queue?"
- "Batch 2 orders to 1 courier — when is it worth it?"
- "Courier drops the order mid-delivery."
- "Dynamic pricing for restaurants during surge."
- "Three-way payment split with full audit trail."

**Red flags**
- Treating it as ride-hailing with food.
- No SLA / ETA monitoring.
- Synchronous coupling of restaurant and courier state.
- Missing the financial reconciliation story.

---

### 10. Calendar Scheduling
→ [solution](diagrams/product/10_calendar_scheduling.md)

**Time budgets**
- **20 min** — event model, invites, RSVP.
- **35 min** — + recurring events, conflicts, notifications.
- **45 min** — + cross-calendar availability, time zones, delegation, external calendars.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Recurring event with an exception — one instance is moved."
- "Find a 30-min slot across 8 attendees in 3 time zones."
- "User edits an event while another user is also editing."
- "DST transition lands inside a recurring event."
- "External calendar (Google, Outlook) sync must be eventually consistent."

**Red flags**
- Storing every instance of a recurring event.
- No time-zone model (naive `datetime`).
- Availability search that scans every event instead of an index.
- No OCC / ETag on event edits. See [concurrency-control.md](patterns/concurrency-control.md).

---

## Big-Scale Data and Streaming Systems

Each links to a worked solution in `/diagrams/data_streaming`. These benefit heavily from grounding in [streaming-semantics.md](patterns/streaming-semantics.md).

### 1. Real-time Leaderboard
→ [solution](diagrams/data_streaming/01_realtime_leaderboard.md)

**Time budgets**
- **20 min** — score ingestion, sorted set (Redis ZSET), read API.
- **35 min** — + sharding, top-K per region, eventual vs strong freshness.
- **45 min** — + multi-tier (global, country, friends), anti-cheat, historical rankings.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "1M players, 100 updates/sec each."
- "Friends leaderboard — per-user Top-K."
- "Cheater detection: a score that went up by 1M in 1 second."
- "Leaderboard must survive a region failure with <1 min data loss."
- "Historical leaderboards — last 30 days."

**Red flags**
- Single Redis ZSET for all users (can't scale writes).
- Synchronous DB write on every score update.
- Offset pagination on the leaderboard (fine for Top-10, breaks at page 10,000).
- No anti-cheat signal on ingestion.

---

### 2. Trending Hashtags / Top-K
→ [solution](diagrams/data_streaming/02_trending_hashtags.md)

**Time budgets**
- **20 min** — count-min sketch or heavy hitters, window definition.
- **35 min** — + tumbling vs sliding window, late data.
- **45 min** — + multi-region aggregation, anti-spam, personalization.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Spam botnet is pumping a hashtag."
- "Regional vs global trending."
- "An event trends in 5 minutes — latency target?"
- "Trending must update in near real time but also be stable (no flicker)."
- "Replay: a day's worth of events with a fixed bug."

**Red flags**
- Exact counting at global scale (memory blows up). See [bloom-filters.md](patterns/bloom-filters.md) for the probabilistic family.
- Processing time windows on user-generated events.
- No dedup / spam filter in ingestion.
- Tumbling windows when the question demands sliding.

---

### 3. Ad Click Aggregation (exactly-once semantics)
→ [solution](diagrams/data_streaming/03_ad_click_aggregation.md)

**Time budgets**
- **20 min** — ingest + dedup + aggregation sketch.
- **35 min** — + windowing, late data, state store.
- **45 min** — + end-to-end effective-once, billing reconciliation, fraud.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Advertiser claims we overcharged — reconcile."
- "5% of clicks arrive >10 minutes late."
- "Click fraud — double clicks, bot clicks."
- "Cross-device attribution for the same user."
- "Processor crashes after aggregating but before writing — no double count."

**Red flags**
- Saying "we use Kafka exactly-once" without explaining what that actually guarantees. See [streaming-semantics.md](patterns/streaming-semantics.md).
- Naive dedup that blows up state size.
- No late-data strategy.
- No reconciliation path against the source of truth.

---

### 4. Metrics and Logs Ingestion
→ [solution](diagrams/data_streaming/04_metrics_logs_ingestion.md)

**Time budgets**
- **20 min** — agent -> gateway -> TSDB / log store, retention tiers.
- **35 min** — + aggregation, downsampling, high-cardinality handling.
- **45 min** — + multi-tenant isolation, cost controls, query latency SLOs.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "One tenant has a bug that emits 10M unique labels."
- "Query over 90 days of data must complete in 5s."
- "Alerts must fire within 30s of the metric."
- "Logs must be retained 7 years for compliance."
- "Cost is 3x budget — what do you cut?"

**Red flags**
- One store for everything (metrics + logs + traces).
- No cardinality limit.
- Alert evaluation on cold storage.
- No sampling / aggregation for high-volume signals.

---

### 5. Recommendation Events Pipeline
→ [solution](diagrams/data_streaming/05_recommendation_events.md)

**Time budgets**
- **20 min** — event model, feature extraction path, serving sketch.
- **35 min** — + online vs offline features, freshness, A/B.
- **45 min** — + model serving, feature store, cold start.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "New user with no history — cold start."
- "Model update every 10 min with online features."
- "Feature drift — detect and alert."
- "Right-to-delete propagates to training data."
- "One feature source is 30 min behind."

**Red flags**
- No feature-store concept.
- Training and serving features computed differently (skew).
- No A/B infrastructure.
- Missing the offline/online consistency story.

---

### 6. Fraud Detection Event Processing
→ [solution](diagrams/data_streaming/06_fraud_detection.md)

**Time budgets**
- **20 min** — event ingestion, rule engine, block/flag action.
- **35 min** — + stateful features, sliding windows, feedback loop.
- **45 min** — + ML scoring, case management, multi-region, latency SLO.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Decision must return in <200 ms synchronously."
- "False positive rate too high — feedback loop."
- "Fraudster adapts — model retraining cadence."
- "Regulator audit — explain every decision."
- "One feature service is down — fail open or closed?"

**Red flags**
- Synchronous ML model call in the payment path with no fallback.
- No feedback / labeling pipeline.
- No explainability path.
- Stateful logic with no checkpointing.

---

### 7. Smart City / IoT Sensor Ingestion
→ [solution](diagrams/data_streaming/07_smart_city_sensors.md)

**Time budgets**
- **20 min** — device -> edge -> ingestion -> store.
- **35 min** — + ordering, late data, device auth, OTA.
- **45 min** — + multi-tenant, geospatial queries, long retention, downsampling.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Device sends a burst of 24 hours of buffered data after reconnecting."
- "Provision 1M new devices in a weekend."
- "Tamper detection on a device."
- "Query all sensors in a 1 km radius with <1 min freshness."
- "Revoke a compromised device cert."

**Red flags**
- Treating devices as trusted clients.
- No backfill / replay path for offline devices.
- Naive geospatial indexing.
- No provisioning / cert story.

---

### 8. CDC-Based Analytics Platform
→ [solution](diagrams/data_streaming/08_cdc_analytics.md)

**Time budgets**
- **20 min** — CDC source, change log topic, sink into warehouse.
- **35 min** — + schema evolution, idempotent sink, late/out-of-order.
- **45 min** — + multi-DB sources, GDPR deletes, backfill from snapshot.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Schema change on source — downstream breaks."
- "Backfill a new sink from scratch without double-counting."
- "Right-to-delete cascades through the change log."
- "CDC connector lags by 1h — how does dashboarding behave?"
- "Heterogeneous sources — Postgres + MongoDB + MySQL."

**Red flags**
- Polling instead of log-based CDC.
- No dedup / no idempotent sink. See [streaming-semantics.md](patterns/streaming-semantics.md).
- No schema registry / contract.
- Merging delete tombstones incorrectly.

---

### 9. Anomaly Detection Stream Processing
→ [solution](diagrams/data_streaming/09_anomaly_detection.md)

**Time budgets**
- **20 min** — rolling baselines, deviation detection, alert fan-out.
- **35 min** — + seasonality, per-entity models, labeling UI.
- **45 min** — + multi-signal correlation, backpressure, SLO.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Black Friday — baseline is suddenly wrong."
- "Too many alerts — deduplicate and correlate."
- "Alert latency must be <60s for critical signals."
- "A signal stops arriving — silence is also an anomaly."
- "Retrospective re-scoring of last 24h with a new model."

**Red flags**
- Fixed thresholds only.
- Single global model.
- No alert deduplication / grouping.
- No "missing data" anomaly class.

---

### 10. Large-scale Audit Logging (tamper-evident, long retention)
→ [solution](diagrams/data_streaming/10_audit_logging.md)

**Time budgets**
- **20 min** — append-only pipeline, integrity strategy, retention.
- **35 min** — + Merkle / hash chain, access API, retention tiers.
- **45 min** — + cross-region, legal hold, key management, cost.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Prove that no audit record has been modified or deleted."
- "Legal hold on a subset of records indefinitely."
- "Query: all actions by user X in Q3 2024."
- "Write path must survive any single-region outage with no loss."
- "Cost is 4x budget — retention strategy."

**Red flags**
- Audit logs in the same DB as the app (mutable, cheap to tamper).
- No integrity chain.
- Queryable by any field with no index strategy (expensive scans).
- Missing access control on the audit system itself.

---

## Reliability, Consistency, and Senior-Level Systems

Each links to a worked solution in `/diagrams/reliability`. These hit the hardest at senior loops; [consensus-and-quorums.md](patterns/consensus-and-quorums.md) and [concurrency-control.md](patterns/concurrency-control.md) are mandatory pre-reads.

### 1. Payment Processing
→ [solution](diagrams/reliability/01_payment_processing.md)

**Time budgets**
- **20 min** — API, idempotency, state machine, PSP integration.
- **35 min** — + reconciliation, refunds, webhooks from PSP, ledger basics.
- **45 min** — + double-entry ledger, multi-currency, 3DS / SCA, regional compliance.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "PSP webhook is late — user sees 'pending' for 20 min."
- "Two retries from the same client — prevent double charge." See [idempotency.md](patterns/idempotency.md).
- "Reconcile PSP daily report against your ledger."
- "Partial refund on a multi-item order."
- "Regulator requires PSD2 / SCA flow."

**Red flags**
- No idempotency key on `POST /payments`.
- Single-entry ledger.
- PSP as the source of truth (it isn't — your ledger is).
- No state machine / no terminal states.

---

### 2. Reservation System (anti-double-booking)
→ [solution](diagrams/reliability/02_reservation_system.md)

**Time budgets**
- **20 min** — resource model, hold + confirm, expiry.
- **35 min** — + concurrency control choice, payment integration.
- **45 min** — + multi-region, overbooking policy, waitlist.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Two users hit `/reserve` for the last unit in the same millisecond."
- "Hold expires while the user is in checkout."
- "Allow 2% overbooking deliberately (airlines)."
- "Multi-region inventory — how do you prevent cross-region double-book?"
- "Waitlist promotion on cancel."

**Red flags**
- OCC on a hot resource. See [concurrency-control.md](patterns/concurrency-control.md).
- No explicit hold with TTL.
- Row lock held across an external payment call.
- No fencing token if using a distributed lock.

---

### 3. Distributed Lock Service
→ [solution](diagrams/reliability/03_distributed_lock_service.md)

**Time budgets**
- **20 min** — API, lease, renewal.
- **35 min** — + fencing tokens, failure modes, clock assumptions.
- **45 min** — + multi-region, performance, formal correctness argument.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Lock holder pauses for GC longer than the lease."
- "The lock service itself loses leader."
- "Need a lock that survives region failure."
- "Dead client — how is the lock reclaimed, and safely?"
- "Read the Redlock debate and defend or critique."

**Red flags**
- No fencing token. See [leader-election.md](patterns/leader-election.md).
- Redis single-node for correctness-critical locks.
- Relying on wall-clock expiry without monotonic clocks.
- Ignoring the "what if the client is paused" scenario.

---

### 4. Object Storage (S3-like)
→ [solution](diagrams/reliability/04_object_storage.md)

**Time budgets**
- **20 min** — metadata service, data placement, erasure coding basics.
- **35 min** — + multi-AZ durability, read/write path, versioning.
- **45 min** — + multi-region replication, lifecycle, strong consistency, auth.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Promise 11 9s of durability — how?"
- "List operation on a bucket with 1B objects."
- "Delete an object — strong vs eventual delete visibility."
- "Cross-region replication with RPO <15 min."
- "Large object (1 TB) upload."

**Red flags**
- Storing object data in a DB.
- No erasure coding discussion.
- Treating list as cheap.
- No versioning / no soft-delete.

---

### 5. Multi-Region Data Replication (active-active vs active-passive)
→ [solution](diagrams/reliability/05_multi_region_replication.md)

**Time budgets**
- **20 min** — pick a model, data flow, conflict handling.
- **35 min** — + RPO/RTO, failover runbook, split-brain.
- **45 min** — + per-entity region homing, CRDTs vs LWW, cost.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "A region is down for 6 hours."
- "Two users in two regions edit the same row."
- "Split-brain — two leaders think they're primary."
- "GDPR data must not leave EU."
- "Cost of cross-region bandwidth is dominant — optimize."

**Red flags**
- Active-active without a conflict model.
- Async replication claimed as "strong consistency".
- No failover testing / game day mention.
- Ignoring regional data sovereignty. See [cap-theorem.md](patterns/cap-theorem.md), [consensus-and-quorums.md](patterns/consensus-and-quorums.md).

---

### 6. Distributed Denylist / Blocklist
→ [solution](diagrams/reliability/06_distributed_denylist.md)

**Time budgets**
- **20 min** — write path, cache, fast lookup.
- **35 min** — + propagation SLO, audit, bulk imports.
- **45 min** — + multi-region, false-positive containment, ML signals.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Blocklist update must propagate globally in <5s."
- "A false positive blocks a real user — recover and audit."
- "Bulk import 10M entries."
- "Bloom filter false-positive rate — quantify."
- "Right-to-delete on a blocklist — is that even legal/practical?"

**Red flags**
- DB lookup on every request (too slow).
- No cache TTL or eviction.
- Missing the "what to do on false positive" path.
- No audit log. See [bloom-filters.md](patterns/bloom-filters.md).

---

### 7. Secrets Management (rotation, auditing, HSM)
→ [solution](diagrams/reliability/07_secrets_management.md)

**Time budgets**
- **20 min** — KMS + secret store + API.
- **35 min** — + rotation, envelope encryption, audit.
- **45 min** — + HSM integration, multi-region, break-glass.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Rotate a DB password with zero downtime."
- "An IAM role is compromised — revoke everywhere."
- "HSM is a single point of failure — fix."
- "Break-glass access — audit trail for regulators."
- "Region failover without exposing cleartext in memory too long."

**Red flags**
- Secrets in env vars in plaintext.
- No rotation story.
- No audit.
- Client caches secrets forever.

---

### 8. Identity and Session Management (OAuth2/OIDC, MFA)
→ [solution](diagrams/reliability/08_identity_session_management.md)

**Time budgets**
- **20 min** — login flow, token issuance, session store.
- **35 min** — + refresh tokens, MFA, logout.
- **45 min** — + SSO federation, device management, step-up auth, multi-region.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Revoke all sessions for a user in <1s."
- "Refresh token theft — detect and mitigate."
- "Step-up MFA for risky actions."
- "Session lives across 5 devices."
- "One region is down — can users still log in?"

**Red flags**
- JWTs with no revocation path claimed as stateless.
- Long-lived access tokens.
- No separation of refresh and access tokens.
- Session store that can't survive region failure.

---

### 9. Idempotent API Execution
→ [solution](diagrams/reliability/09_idempotent_api.md)

**Time budgets**
- **20 min** — key storage, conflict handling, response caching.
- **35 min** — + TTL, scope, retries, atomicity with side effects.
- **45 min** — + multi-region, cross-service idempotency, long-running ops.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Same key, different body — what do you return?"
- "Key storage crashes between dedup check and side effect."
- "Idempotent over a saga."
- "Key replay weeks later — TTL."
- "Cross-region — same key used in two regions."

**Red flags**
- Storing idempotency result outside the transaction with the side effect.
- No TTL.
- Same-key-different-body silently succeeds.
- See [idempotency.md](patterns/idempotency.md) for the canonical treatment.

---

### 10. Global Inventory Management
→ [solution](diagrams/reliability/10_global_inventory_management.md)

**Time budgets**
- **20 min** — per-region inventory, rebalancing, read path.
- **35 min** — + reservation protocol, multi-warehouse.
- **45 min** — + cross-region transfers, oversell policy, forecasting.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Single SKU in one warehouse worldwide — customers globally want it."
- "Network partition between EU and US — accept orders?"
- "Overbook by 5% deliberately."
- "Rebalance stock between warehouses every night."
- "Cancel a high-volume order — unwind reservations."

**Red flags**
- Global strict inventory count (requires cross-region consensus per write).
- No reservation / two-phase approach. See [saga-pattern.md](patterns/saga-pattern.md).
- Over-claiming strong consistency.
- Missing the "source of truth per SKU" decision.

---

## Infrastructure-Focused Practice

Each links to a worked solution in `/diagrams/infrastructure`.

### 1. CDN
→ [solution](diagrams/infrastructure/01_cdn.md)

**Time budgets**
- **20 min** — edge POPs, cache fill, origin.
- **35 min** — + invalidation, cache keys, TLS termination.
- **45 min** — + dynamic content, signed URLs, DDoS, multi-tier cache.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Purge one URL globally in <30s."
- "Handle a 10 Tbps DDoS."
- "Signed URLs for private assets."
- "Cache fill storm against origin."
- "Geofence: block a country." See [cdn.md](patterns/cdn.md).

**Red flags**
- No origin shield / no cache fill protection.
- No invalidation API.
- Default TTL as "forever" without purge path.
- TLS termination only at origin.

---

### 2. Load Balancing System
→ [solution](diagrams/infrastructure/02_load_balancer.md)

**Time budgets**
- **20 min** — L4 vs L7, health checks, algorithms.
- **35 min** — + TLS, sticky sessions, autoscaling integration.
- **45 min** — + global LB, Anycast, failure domains.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Backend is returning 500s but health check is 200."
- "Sticky sessions with zero-downtime deploy."
- "Slow-start for new backends."
- "LB becomes the bottleneck."
- "Active-active DCs with GSLB."

**Red flags**
- Round-robin as the only answer.
- No outlier detection.
- No connection draining on deploy.
- LB as a SPOF. See [load-balancing.md](patterns/load-balancing.md).

---

### 3. Service Discovery
→ [solution](diagrams/infrastructure/03_service_discovery.md)

**Time budgets**
- **20 min** — registry, heartbeats, client-side vs server-side.
- **35 min** — + staleness, caching, failure modes.
- **45 min** — + multi-region, service mesh integration, mTLS identity.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Registry is down — can traffic still flow?"
- "A stale instance keeps receiving traffic."
- "A new service version — gradual rollout."
- "Cross-region service call."
- "Short-lived functions (serverless) register at massive rate."

**Red flags**
- Single source of truth with no client-side cache.
- No TTL / staleness reasoning. See [service-discovery.md](patterns/service-discovery.md).
- No health-check separation from registration.
- Ignoring the service mesh alternative.

---

### 4. Container Orchestration Control Plane (Kubernetes-like)
→ [solution](diagrams/infrastructure/04_container_orchestration.md)

**Time budgets**
- **20 min** — scheduler, API server, node agent.
- **35 min** — + etcd, controllers, watch/reconcile.
- **45 min** — + scale (10K nodes), upgrades, multi-cluster.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Scheduler is the bottleneck at 5K nodes."
- "etcd compaction lag affects API latency."
- "Upgrade the control plane with zero downtime."
- "Multi-cluster federation."
- "Malicious workload — lateral movement." See [consensus-and-quorums.md](patterns/consensus-and-quorums.md).

**Red flags**
- Missing the watch/reconcile loop.
- etcd as an afterthought.
- No cluster upgrade story.
- One giant cluster for everything.

---

### 5. Distributed Job Scheduler (cron-at-scale)
→ [solution](diagrams/infrastructure/05_distributed_job_scheduler.md)

**Time budgets**
- **20 min** — job store, worker pool, lease.
- **35 min** — + retries, at-least-once vs at-most-once, timezones.
- **45 min** — + multi-region, fairness, SLA, audit.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Job ran twice — not allowed."
- "A job takes 2 hours and the worker dies at the 1h50m mark."
- "10K jobs scheduled at midnight UTC — thundering herd."
- "Jobs depend on each other — DAG."
- "Tenant A starves tenant B."

**Red flags**
- No leasing / lock on job execution. See [leader-election.md](patterns/leader-election.md), [distributed-locking.md](patterns/distributed-locking.md).
- No idempotency for user jobs.
- Strict at-most-once without user opt-in.
- Global lock on the job store.

---

### 6. Schema Migration Platform
→ [solution](diagrams/infrastructure/06_schema_migration.md)

**Time budgets**
- **20 min** — migration model, online DDL, rollback.
- **35 min** — + expand/contract, backfill, large tables.
- **45 min** — + multi-service coordination, zero-downtime, data contracts.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Rename a column with 2B rows — zero downtime."
- "Migration fails at 80% — rollback."
- "Two services share a table — coordinate."
- "Backfill eats IOPS — throttle."
- "Schema change that breaks a downstream analytics pipeline."

**Red flags**
- `ALTER TABLE` on a huge table with no online DDL tool.
- Drop-then-add rename.
- No backfill / verify / cutover phases.
- No rollback plan beyond "we'll reverse it."

---

### 7. Centralized Observability (metrics, logs, tracing)
→ [solution](diagrams/infrastructure/07_centralized_observability.md)

**Time budgets**
- **20 min** — three signals, ingestion path.
- **35 min** — + sampling, correlation IDs, alerting.
- **45 min** — + SLOs, error budget, cost, long retention.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "One service emits 100x more telemetry than others."
- "Trace an end-to-end request across 12 hops."
- "Alert storm — dedupe."
- "Cost is 30% of infra spend."
- "Log a PII field accidentally — scrub retroactively."

**Red flags**
- One storage for metrics + logs + traces.
- No sampling strategy for traces.
- No correlation IDs.
- Alerts on symptoms, not SLOs.

---

### 8. Multi-Tenant SaaS Architecture
→ [solution](diagrams/infrastructure/08_multi_tenant_saas.md)

**Time budgets**
- **20 min** — tenancy model (pool vs silo vs bridge), data isolation.
- **35 min** — + noisy-neighbor, per-tenant rate limits, auth.
- **45 min** — + per-tenant backup, migration between tiers, regional residency.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Enterprise tenant demands data in EU only."
- "One tenant's bug takes the DB to 100% CPU."
- "Tenant churns — export all their data."
- "Per-tenant feature flags."
- "Blue/green deploy at the tenant level."

**Red flags**
- Pooled DB with no row-level isolation or query filter.
- Shared caches with no key namespacing.
- No per-tenant observability.
- Upgrading all tenants at once.

---

### 9. Zero-Downtime Deployment Platform
→ [solution](diagrams/infrastructure/09_zero_downtime_deployment.md)

**Time budgets**
- **20 min** — blue/green or canary, traffic shift, rollback.
- **35 min** — + schema compatibility, flag-driven rollout, automated bake.
- **45 min** — + multi-region, auto-rollback on SLO burn, progressive delivery.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Canary looks good at 1%, breaks at 50%."
- "DB migration requires a long-running backfill."
- "Rollback after 20 min — data already written."
- "Feature flag vs canary — when each."
- "Global blast radius — limit it."

**Red flags**
- In-place deploys for stateful services.
- No automated rollback trigger.
- Canary promoted on metric averages only (needs percentile SLOs).
- Schema change deployed in the same release as the code that uses it.

---

### 10. Backup and Disaster Recovery
→ [solution](diagrams/infrastructure/10_backup_disaster_recovery.md)

**Time budgets**
- **20 min** — RPO/RTO definitions, backup cadence, storage.
- **35 min** — + restore testing, cross-region, encryption.
- **45 min** — + tiered recovery, game day, audit, compliance.

**Scoring rubric** — R1 + R2 + R3 + R4.

**Twist cards**
- "Full region is gone — restore."
- "Backup integrity — a tampered backup."
- "Restore time is 12 hours — reduce to 1."
- "Ransomware scenario — immutable backups."
- "Compliance: 7-year retention with access audit."

**Red flags**
- Backups in the same region as the source.
- No restore testing.
- No immutability.
- RPO / RTO not tied to business impact.

---

## Modern Topics Worth Practicing (2025/2026)

Layer these onto any of the above prompts or run them standalone. They appear frequently in recent interview loops.

| Topic | Notes | Related |
|-------|-------|---------|
| LLM-powered chat / RAG service | Embeddings, vector DB, prompt caching, streaming responses, token budgets | [streaming-semantics.md](patterns/streaming-semantics.md) |
| Vector search / similarity search | HNSW, IVF, product quantization | |
| Inference serving platform | GPU scheduling, batching, autoscaling cold starts | |
| Real-time collaboration backend | WebSockets / SSE, presence, backpressure | [streaming-semantics.md](patterns/streaming-semantics.md) |
| Feature store for ML | Online + offline parity | |
| Data quality / lineage on lakehouse | | |
| Event-driven payments (SCA/3DS, PSD2/PCI) | | [concurrency-control.md](patterns/concurrency-control.md), [idempotency.md](patterns/idempotency.md) |
| Privacy-compliant data deletion (GDPR/CCPA) | Cascades to caches, analytics, backups | |
| Multi-region active-active with conflict resolution | CRDTs, LWW, vector clocks | [consensus-and-quorums.md](patterns/consensus-and-quorums.md), [cap-theorem.md](patterns/cap-theorem.md) |
| Zero-trust service mesh | mTLS, SPIFFE identity | |

## Suggested Practice Order (Ramp-Up)

Structured order for a 6-8 week ramp. Mix time budgets (45 / 35 / 20) across weeks as per cadence.

1. URL shortener
2. Rate limiter
3. Notification service
4. Chat system
5. News feed
6. Ticket booking
7. Real-time leaderboard
8. Metrics ingestion pipeline
9. Payment system
10. Reservation system
11. Object storage
12. Multi-region replication

## How To Push Yourself During Practice

- Add exact latency and availability targets (p50 / p99, 99.9% vs 99.99%).
- Add peak traffic assumptions instead of average-only traffic.
- Force a multi-region requirement.
- Force GDPR / CCPA or auditability requirements.
- Discuss what happens during partial failures and grey failures (slow, not down).
- Explain what is synchronous vs asynchronous.
- Identify one consistency trade-off explicitly.
- Name the bottleneck that will fail first.
- Propose a v1 design and then scale it to v2 (10× and 100×).
- State your SLOs and the error budget implications.

## Strong Follow-Up Questions To Ask Yourself

- What should be cached and where?
- What is the partition key and why?
- Where can duplicates happen?
- What needs idempotency?
- What happens when one dependency is slow or unavailable?
- Which data must be strongly consistent?
- Which APIs need pagination, filtering, or cursor-based access?
- How will I monitor correctness and performance in production?
- How does the design change at 10x scale?
- What would I cut from v1 to ship faster?

## Core Companion Patterns

Every drill above draws from these — skim them before a session and re-read after.

- [back-of-envelope.md](patterns/back-of-envelope.md) — scale reasoning.
- [cap-theorem.md](patterns/cap-theorem.md) — consistency vs availability.
- [consensus-and-quorums.md](patterns/consensus-and-quorums.md) — Raft, Paxos, quorum math.
- [concurrency-control.md](patterns/concurrency-control.md) — OCC, pessimistic, MVCC.
- [streaming-semantics.md](patterns/streaming-semantics.md) — delivery, windowing, watermarks.
- [api-design-and-pagination.md](patterns/api-design-and-pagination.md) — REST/RPC/GraphQL, cursors, versioning.
- [idempotency.md](patterns/idempotency.md) — safe retries.
- [caching.md](patterns/caching.md) — the single biggest lever.
- [sharding-partitioning.md](patterns/sharding-partitioning.md) — horizontal scale.
- [replication.md](patterns/replication.md) — durability, read scaling, failover.
