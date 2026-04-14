# Recommendation Events Pipeline — Architecture Design

## Requirements

### Functional
- Process user interaction events (views, clicks, purchases, searches, cart adds) in near real-time to update recommendation features
- Maintain real-time user profiles (recent interests, session context, engagement patterns)
- Feed a feature store used by the recommendation model at serving time
- Support model retraining pipeline with labeled training data (interaction events + outcomes)
- Track recommendation impressions and compute feedback metrics (CTR, conversion rate per model version)
- Support A/B testing of multiple recommendation models simultaneously

### Non-Functional
- Feature freshness: user profile features updated within 5 seconds of an interaction event
- Serving latency: recommendation API returns top-N items within 50ms (p99)
- Handle 200K user events/sec during peak shopping hours
- Training pipeline processes full day's events (batch) within 4 hours
- Feature consistency: online (serving) and offline (training) features must be computed from the same logic (avoid training-serving skew)

## Scale Estimates
- **Events/sec:** 200K user interaction events/sec peak, 50K sustained
- **Users:** 100M monthly active users, 10M daily active
- **Items:** 50M items in catalog, 1M added/updated daily
- **Feature store:** 10M user profiles * ~2KB features = ~20GB online store (Redis), ~500TB offline store (Hive/S3)
- **Model size:** ~500MB per model version, served in GPU-backed containers
- **Training data:** ~10B interaction events per month, ~50TB compressed Parquet

## Architecture Decisions

### Dual Feature Store (Online + Offline) With Shared Logic
The most insidious bug in recommendation systems is training-serving skew — when features computed during training differ from features computed at serving time. Our architecture uses a single Flink job to compute features that writes to both Redis (online, for serving) and Iceberg/S3 (offline, for training). Same code, same logic, same features. The alternative (separate training and serving feature pipelines) is cheaper to build but creates subtle divergences that silently degrade model quality.

**Feature store framework:** Rather than building this from scratch, use Feast (open-source) or Tecton (managed). Both provide feature definitions as code, point-in-time-correct offline feature retrieval for training (prevents label leakage), and a feature registry that serves as the contract between data scientists and engineers. This is the industry standard as of 2025.

### Session Windows for User Interest Modeling
Tumbling/sliding windows miss the concept of a "browsing session." A user who browses 20 items in 5 minutes, then goes idle for 2 hours, then comes back should have two separate session contexts. Flink's session windows (gap-based: session ends after 30 minutes of inactivity) naturally capture this. Features like "items viewed in current session," "session duration," and "session depth" are powerful signals for recommendations that fixed-time windows cannot provide.

### Kafka for Event Bus Over Direct API Calls
Recommendation pipelines have many consumers of the same events: real-time feature extraction, user profile updates, model training data collection, feedback loop analysis, A/B test metrics. Kafka's pub/sub model lets each consumer process events independently without coordinating with producers. Direct API calls from the event source to each consumer would create tight coupling and N^2 integration complexity.

### gRPC for Model Serving Over REST
At 200K QPS with a 50ms p99 latency target, the serving layer must minimize per-request overhead. gRPC's binary serialization (protobuf) is 3-10x more compact than JSON, and its HTTP/2 transport supports connection multiplexing and server-push. For a recommendation API returning a ranked list of 50 items with scores, the payload difference is significant. Also, gRPC's streaming support enables efficient pre-fetching of recommendations for paginated UIs.

## Pipeline Stages

1. **Event Sources:** User activity tracker (embedded in web/mobile apps) produces interaction events: `(user_id, item_id, event_type, timestamp, context)`. Catalog service produces item update events. Search service produces query events
2. **Kafka Ingestion:** `user-events` topic (partitioned by `user_id` for session locality), `catalog-changes` topic (partitioned by `item_id`), `reco-impressions` topic (partitioned by `user_id` for feedback correlation)
3. **Flink Feature Extraction:** Consumes `user-events`, computes real-time features per user: items viewed in last 1h/24h/7d (sliding window counters), category affinity vector (weighted by recency and interaction type), price range preference, session-level features (session window). Writes to Feature Store (Redis for online, S3 for offline)
4. **Flink User Profile Update:** Maintains a rolling user profile in DynamoDB: demographic segment, long-term preferences, engagement score. Uses Flink's async I/O for DynamoDB lookups/writes to avoid blocking
5. **Flink Session Aggregator:** Uses session windows (30-min gap) to compute per-session aggregates: browse depth, funnel stage, intent signal. These power "what to show next in this session" recommendations
6. **Batch Model Training (Spark):** Daily Spark job reads interaction events + features from S3, joins with outcome labels (did the user purchase within 24h?), trains/retrains the recommendation model (collaborative filtering + deep learning ranker). Publishes to Model Registry (MLflow)
7. **Feedback Loop Analysis (Spark):** Weekly Spark job joins `reco-impressions` with `user-events` to compute per-model-version metrics: CTR, conversion rate, diversity, novelty. Results feed into A/B testing framework for model selection decisions
8. **Serving:** Recommendation Service loads model from registry, fetches user features from Redis, runs inference, returns ranked items. A/B testing framework routes users to model variants based on experiment configuration

## Partitioning Strategy

- **Kafka `user-events`:** Partition by `user_id` — essential for session windows (all events from one user must go to the same Flink subtask for correct session aggregation)
- **Kafka `catalog-changes`:** Partition by `item_id` — enables efficient item feature updates without cross-partition coordination
- **Redis Feature Store:** Hash-based sharding on `user_id`. Each user's feature vector is stored as a Redis Hash (`user:{id}:features`) with individual feature fields. This allows atomic partial updates (only update changed features) and efficient bulk reads
- **DynamoDB User Profiles:** Partition key = `user_id`, sort key = `profile_version` for time-travel debugging
- **S3 Training Data:** Partitioned by `date/event_type` for efficient Spark scans. Separate partition for labeled examples (interaction + outcome pairs)

## Failure Handling

- **Flink feature extraction failure:** Checkpoints to S3 every 30 seconds. Features are idempotent writes (same user_id + timestamp = same features), so replaying from checkpoint is safe. During recovery (~30 seconds), features become stale but the recommendation service continues serving with slightly outdated features. This graceful degradation is acceptable — a 30-second-old user profile still generates relevant recommendations
- **Redis Feature Store failure:** Redis Cluster with 3 replicas per shard. On failover (~5 seconds), the recommendation service retries. If Redis is fully down, fall back to a static "popular items" recommendation (degraded but available). Never block the user-facing request waiting for feature store recovery
- **Model serving failure:** Multiple replica pods behind a load balancer. Circuit breaker pattern: if one model version crashes, route all traffic to the stable fallback version. Shadow deployments for new models: run inference on production traffic but don't serve results until validated
- **DLQ:** Events failing schema validation go to DLQ. Common cause: new event types added without schema evolution. Critical: DLQ events must NOT be silently dropped because each event represents a user interaction that contributes to training data. DLQ is replayed after fixing the schema issue
- **Training data corruption:** Feature store writes include a `version` field. If a bug produces incorrect features, increment the version and recompute from raw events. The training pipeline only uses features from the latest validated version

## Key Trade-offs

- **Feature freshness vs computation cost:** Updating user features on every single event (real-time) is expensive. Alternatives: micro-batch (every 5 seconds) or event-triggered with deduplication. We use event-triggered for high-signal events (purchases, add-to-cart) and micro-batch for low-signal events (page views) — a tiered freshness strategy
- **Model complexity vs serving latency:** A deep neural network ranker gives better recommendations but has 20ms inference time. A simpler two-tower model has 2ms inference but lower quality. Compromise: use the fast model for initial candidate retrieval (top 500 from 50M items), then the complex model for re-ranking (top 50 from 500). This two-stage approach keeps p99 under 50ms
- **Online learning vs batch retraining:** Online learning (updating model weights on each event) provides freshest recommendations but is unstable (one viral item can bias the model). Batch retraining (daily) is stable but slow to adapt. We use batch retraining for the base model + online feature updates for personalization context. The model is stable; the features are fresh
- **Exploration vs exploitation:** Always showing the highest-predicted items creates a feedback loop (popular items get shown more, get clicked more, become even more popular). A/B testing with epsilon-greedy exploration (10% random items) ensures diverse data collection for model improvement

## What Fails First

**Feature Store read latency** under load is the first bottleneck. At 200K QPS, each recommendation request reads ~2KB of user features + ~500 bytes per candidate item features. If the candidate pool is 500 items, that's ~250KB of feature reads per request. With Redis pipelining this is manageable, but during a Redis node failure + rebalancing, latency spikes to 100ms+ and the 50ms p99 SLA breaks.

Solution: (1) cache popular item features in the recommendation service's local memory (item features change infrequently), (2) pre-compute user-item feature crosses in the feature extraction pipeline rather than at serving time, (3) use Redis read replicas for feature reads (writes go to primary, reads spread across replicas).

Second failure: **training-serving skew** from feature pipeline bugs. If the Flink feature extraction job has a bug that produces incorrect features for a subset of users, the online store gets corrupted features while the offline store accumulates a mix of correct and incorrect features. The model trains on mixed data and underperforms in production. Prevention: feature validation checks comparing online and offline feature distributions (daily Spark job), with automatic alerts when distributions diverge.

## v1 vs v2

### v1 — Ship in 4 weeks
- Simple user features: category view counts, last N items viewed (no session windows)
- Single Flink job: feature extraction + profile update
- Redis-only feature store (no offline store — training pipeline reads from Kafka directly)
- Single model version, no A/B testing
- Batch retraining weekly (manual trigger)
- Popular items fallback when features are unavailable
- No feedback loop analysis

### v2 — ML-production grade
- Session window features with sophisticated interest modeling
- Dual feature store (Redis online + S3/Hive offline) with shared logic
- Multiple parallel Flink jobs for independent scaling
- A/B testing framework with automated model selection
- Daily batch retraining with automated pipeline (Airflow/Prefect)
- Feedback loop analysis for model quality monitoring
- Two-stage ranking (fast retrieval + complex re-ranking)
- Exploration strategy (epsilon-greedy) with configurable exploration rate
- Feature validation pipeline detecting training-serving skew
- Shadow deployment for safe model rollouts
