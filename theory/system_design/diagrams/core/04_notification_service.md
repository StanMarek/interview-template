# Notification Service -- Architecture Design

## Requirements

### Functional
- Send notifications via email, SMS, and push (iOS/Android)
- Support templated messages with variable substitution (e.g., "Hi {{name}}, your order {{orderId}} shipped")
- Respect user notification preferences (opt-in/opt-out per channel)
- Priority levels: critical (password reset, 2FA), high (order confirmation), normal (marketing)
- Deduplication: don't send the same notification twice within a time window
- Delivery tracking: sent, delivered, opened, bounced, failed
- Scheduled notifications (send at a future time, respect user timezone)

### Non-Functional
- **Latency:** Critical notifications delivered within 30 seconds; normal within 5 minutes
- **Throughput:** 1M notifications/hour sustained, 10M/hour burst (e.g., marketing campaign)
- **Reliability:** At-least-once delivery -- better to send twice than not at all (dedup handles the rest)
- **Availability:** 99.9% for the ingestion API; individual channel failures should not block other channels

## Scale Estimates
- **DAU:** 50M users
- **Notifications/day:** 500M across all channels
- **Email:** 300M/day (~3,500/sec)
- **Push:** 150M/day (~1,700/sec)
- **SMS:** 50M/day (~580/sec) -- most expensive, so more selective
- **Storage:** Notification log at 500B/record * 500M/day = ~250 GB/day. Retain 90 days = ~22 TB

## Architecture Decisions

### Async-First with Priority Queues
Notification delivery is inherently asynchronous -- the caller shouldn't block waiting for an email to be sent. The API accepts the notification request, validates it, and immediately enqueues it. This has several benefits:
- **Decouples producers from delivery:** If SES is slow, the order service doesn't slow down
- **Natural backpressure:** Queue depth signals when to scale workers
- **Retry without producer involvement:** Failed sends are re-enqueued automatically

**Priority queues (not just FIFO):** A password reset code must not wait behind 10M marketing emails. Using Kafka with separate topics per priority (or SQS with separate queues), critical notifications bypass the backlog entirely.

### Channel-Specific Workers
Each channel (email, SMS, push) has its own worker pool because:
- **Different throughput characteristics:** Email can be sent at 100K/sec via SES bulk API; SMS is rate-limited by Twilio; push depends on APNs/FCM connection pools
- **Independent scaling:** A marketing email blast shouldn't require more push notification workers
- **Independent failure isolation:** If Twilio is down, email and push continue unaffected
- **Different retry strategies:** Email retries are cheap (retry in 1 min); SMS retries are expensive (Twilio charges per attempt)

### Deduplication Layer
Duplicate notifications happen when:
- Producer retries after a timeout (but the first request succeeded)
- Queue delivers a message twice (at-least-once semantics)
- Bug in upstream service triggers multiple events

**Solution:** Each notification has a unique `idempotency_key` (e.g., `order_confirmed:{orderId}:{channel}`). Before sending, workers check Redis for this key. If present, skip. If absent, set with a TTL (e.g., 24 hours) and proceed.

**Why not use the message queue's dedup?** Kafka's exactly-once semantics (transactional producers + `read_committed` consumers) only cover Kafka-to-Kafka writes within a transaction — they do not extend to external side effects like "send an email via SES." Once the worker calls SES, the send happened regardless of whether the Kafka offset commit succeeds. SQS FIFO has dedup but only within a 5-minute window. Application-level dedup with Redis gives us full control over the window and works across any sink.

### Template Engine Separation
Templates are managed independently from the notification logic:
- Templates stored in a DB with versioning (not hardcoded in caller services)
- Marketing team can update email templates without engineering deploys
- Supports A/B testing of notification content
- Template rendering happens at enqueue time (not delivery time) to avoid template changes affecting already-queued notifications

### User Preference Check at Ingestion
Check preferences BEFORE enqueuing, not at delivery time. This avoids wasting queue capacity and worker time on notifications the user won't receive. The preference check is cached in Redis (user preferences change infrequently).

Exception: Critical notifications (2FA, password reset) bypass preferences -- they're always delivered.

## Component Breakdown

| Component | Role |
|---|---|
| **Producer Services** | Order, Auth, Marketing, Billing -- any service that triggers a notification |
| **Notification API** | REST API that validates, deduplicates, checks preferences, renders template, and enqueues |
| **Template Engine** | Renders Mustache/Handlebars templates with user-specific variables |
| **Priority Queue (Kafka)** | Separate topics: `notifications.critical`, `notifications.high`, `notifications.normal` |
| **User Preferences (Redis)** | Cached opt-in/opt-out settings per user per channel. TTL 1 hour |
| **Email Worker** | Consumes from queue, batches where possible, sends via SES |
| **SMS Worker** | Consumes from queue, sends via Twilio with rate limiting |
| **Push Worker** | Consumes from queue, sends via APNs (iOS) and FCM (Android) |
| **Dedup Filter** | Redis-based idempotency check at both API and worker level |
| **External Providers** | SES, Twilio, APNs, FCM -- third-party delivery infrastructure |
| **Notification Log (Cassandra)** | Append-only log of every notification: status, timestamps, delivery receipts |

## Key Trade-offs

- **At-least-once vs exactly-once delivery:** Exactly-once is prohibitively expensive in a distributed system. At-least-once with application-level dedup is practical and achieves the same user experience
- **Render at ingestion vs render at delivery:** Rendering at ingestion freezes the content (template updates don't affect queued messages). Rendering at delivery uses the latest template but risks inconsistency if templates change during a campaign
- **Provider lock-in vs abstraction:** Abstracting over providers (SES, SendGrid, Mailgun) adds complexity but enables failover. Worth it for email (providers have outages); less critical for push (APNs/FCM are the only options)
- **Real-time vs batched delivery:** Real-time for transactional (password reset). Batched for marketing (more efficient, better deliverability). The system must support both modes

## What Fails First

**Third-party provider rate limits** are the first bottleneck. SES starts new accounts in a sandbox (200 emails/day) and only raises sending rate and daily quota after reputation is proven via support-ticket review. Twilio has per-second limits per phone number (typically 1 SMS/sec per long code, higher for toll-free/short-code/10DLC-registered numbers). APNs throttles connections — use HTTP/2 multiplexed persistent connections rather than opening a connection per message.

**Mitigation:**
- Pre-warm SES sending reputation gradually
- Use multiple Twilio numbers with round-robin
- Maintain persistent APNs/FCM connections (don't reconnect per message)
- Queue-based architecture naturally buffers bursts

**Secondary risk:** Kafka consumer lag during burst campaigns. If workers can't keep up, the queue grows and latency increases. Mitigation: auto-scale worker pods based on consumer lag metrics.

## v1 vs v2

### v1 (Ship in 2 weeks)
- Email only (most common channel)
- Single Kafka topic (no priority separation)
- Hardcoded templates in code
- SES as the sole provider
- Simple dedup with Redis idempotency keys
- PostgreSQL for notification log (simpler, fine at low scale)
- No scheduled sends

### v2 (Production-grade)
- All three channels: email, SMS, push
- Priority queues with separate consumer groups
- Template management UI with versioning and A/B testing
- Multi-provider with automatic failover (SES -> SendGrid for email)
- User preference management with GDPR-compliant opt-out tracking
- Scheduled notifications with timezone awareness
- Delivery analytics dashboard (open rates, bounce rates, click-through)
- Notification log in Cassandra for high-volume retention
- Webhook callbacks to producers on delivery/failure events
- Rate limiting per user to prevent notification fatigue (max 5 marketing notifications per day)
