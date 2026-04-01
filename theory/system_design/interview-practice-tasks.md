# System Design Interview Practice Tasks

Use this list to practice the kinds of architecture questions that come up in backend and senior engineering interviews. For each task, do more than talk through the idea:

1. Clarify functional and non-functional requirements.
2. Estimate scale: DAU, QPS, storage, bandwidth, peak traffic.
3. Draw a high-level architecture diagram.
4. Draw one sequence diagram for the main user flow.
5. Define data model and storage choices.
6. Explain scaling, consistency, failure handling, and trade-offs.

## What To Draw For Every Exercise

- Context diagram: clients, APIs, core services, storage, async components.
- Sequence diagram: one critical request path.
- Data model sketch: key entities, indexes, partition keys.
- Scaling view: caches, queues, replicas, shard boundaries, read/write split.
- Reliability view: retries, DLQ, circuit breaker, failover, disaster recovery.

## Core Interview Staples

These are common entry points and test whether you understand standard distributed systems building blocks.

1. Design a URL shortener.
2. Design a rate limiter.
3. Design a distributed cache.
4. Design a notification service for email, SMS, and push.
5. Design a file upload and image processing service.
6. Design a search autocomplete system.
7. Design an API gateway and request routing layer.
8. Design a feature flag service.
9. Design a webhook delivery platform with retries and DLQs.
10. Design a distributed configuration service.

## Product-Style Systems

These are typical "design X product" interview prompts. They test API design, storage decisions, and trade-offs around latency, consistency, and fan-out.

1. Design a chat or messaging system.
2. Design a news feed or timeline.
3. Design Instagram or a photo-sharing app.
4. Design YouTube-lite video upload and streaming.
5. Design Google Docs style collaborative editing.
6. Design a ticket booking platform.
7. Design a ride-hailing platform.
8. Design an e-commerce cart and checkout system.
9. Design a restaurant delivery platform.
10. Design a calendar scheduling system.

## Big-Scale Data And Streaming Systems

These are strong practice questions for more senior interviews because they force you to reason about ingestion, aggregation, ordering, partitioning, and real-time vs batch processing.

1. Design a real-time leaderboard.
2. Design a trending hashtags or top-K events system.
3. Design an ad click aggregation pipeline.
4. Design a metrics and logs ingestion platform.
5. Design a recommendation events pipeline.
6. Design a fraud detection event processing system.
7. Design a smart city sensor ingestion platform.
8. Design a CDC-based analytics platform.
9. Design a stream processing system for anomaly detection.
10. Design a large-scale audit logging platform.

## Reliability, Consistency, And Senior-Level Systems

These usually separate mid-level from senior-level answers. The interviewer will expect clearer reasoning about correctness, operational risk, and failure modes.

1. Design a payment processing system.
2. Design a reservation system with anti-double-booking guarantees.
3. Design a distributed lock service.
4. Design object storage similar to a simplified S3.
5. Design a multi-region data replication strategy.
6. Design a distributed denylist or blocklist service.
7. Design a secrets management service.
8. Design an identity and session management platform.
9. Design a service for idempotent API execution.
10. Design a global inventory management system.

## Infrastructure-Focused Practice

These are useful when the interviewer leans toward platform, cloud, or production engineering topics.

1. Design a CDN.
2. Design a load balancing system.
3. Design service discovery for microservices.
4. Design a container orchestration control plane at a high level.
5. Design a distributed job scheduler.
6. Design a schema migration platform for many services.
7. Design centralized observability for metrics, logs, and tracing.
8. Design a multi-tenant SaaS architecture.
9. Design a zero-downtime deployment platform.
10. Design a backup and disaster recovery strategy for critical services.

## Suggested Practice Order

If you want to ramp up in a structured way, use this order:

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

- Add exact latency and availability targets.
- Add peak traffic assumptions instead of average-only traffic.
- Force a multi-region requirement.
- Force GDPR or auditability requirements.
- Discuss what happens during partial failures.
- Explain what is synchronous vs asynchronous.
- Identify one consistency trade-off explicitly.
- Name the bottleneck that will fail first.
- Propose a v1 design and then scale it to v2.

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
