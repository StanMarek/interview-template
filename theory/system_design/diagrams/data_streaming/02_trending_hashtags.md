# Trending Hashtags / Top-K Events System — Architecture Design

## Requirements

### Functional
- Compute the top-K trending hashtags (e.g., top 50) within configurable time windows (5 min, 1 hour, 24 hours)
- Support "trending" as a velocity metric (rate of increase), not just absolute count
- Regional trending (per-country, per-city) in addition to global
- Content moderation integration — block blacklisted or sensitive hashtags from appearing
- Historical trending data for analytics ("What was trending last Tuesday?")

### Non-Functional
- Top-K list refresh latency under 30 seconds (users see fresh trends within half a minute)
- Handle 500K+ hashtag events/sec at peak (viral event, global platform)
- Approximate counts are acceptable — 5% error tolerance for ranking
- Graceful degradation: if streaming pipeline lags, serve slightly stale trends rather than nothing
- Ordering guarantee: events for the same hashtag should be processed in approximate order

## Scale Estimates
- **Events/sec:** 500K hashtag mention events/sec peak, 100K sustained
- **Unique hashtags/day:** ~50M distinct hashtags
- **Top-K result size:** 50-100 hashtags per scope (global + ~200 regional = ~10K total top-K entries)
- **Storage growth:** ~500GB/day raw events, ~5GB/day aggregated time-series
- **Query volume:** Top-K API called ~100K times/sec (heavily cached, TTL 15-30s)

## Architecture Decisions

### Count-Min Sketch Over Exact Counting
With 50M distinct hashtags per day, maintaining exact counts per window requires enormous state. A Count-Min Sketch (CMS) provides approximate frequency counts in O(1) time and fixed memory (~100KB per sketch). The trade-off is potential over-counting (never under-counting), which is acceptable for trending — a slightly inflated count for a genuinely popular hashtag doesn't change its trending status. The key insight: we only need the *relative ranking* to be correct, not the absolute counts.

### Exponential Decay Scoring for "Trending" vs "Popular"
Trending means *acceleration*, not magnitude. A hashtag with 1M lifetime mentions but flat growth is not trending. One with 10K mentions but doubling every 5 minutes is. Use exponential time-decay scoring: `score = sum(count_i * e^(-lambda * age_i))` where `lambda` controls how quickly old counts lose influence. This naturally promotes velocity over volume. The decay function runs in Flink as a custom aggregate.

### Tumbling + Sliding Windows Combination
Use 1-minute tumbling windows for counting (each event falls into exactly one window, simple checkpointing), then a sliding window of the last N tumbling windows for trend computation. This avoids the state explosion of pure sliding windows while giving smooth trend updates. Flink's window triggers emit updated top-K every 15-30 seconds.

### Redis for Serving Over Direct Flink Queries
Flink is a processing engine, not a query engine. Exposing Flink's state via queryable state is fragile and couples the API to the processing topology. Instead, Flink writes computed top-K lists to Redis, and the API reads from Redis. This decouples serving from processing and lets us handle API failures independently.

## Pipeline Stages

1. **Event Sources:** Post service emits hashtag extraction events when users create posts. Engagement service emits events when users click/share trending topics. Search service emits query events containing hashtag searches
2. **Kafka Ingestion:** Two topics — `hashtag-events` (partitioned by hashtag for co-located counting) and `engagement-events` (partitioned by event type). Schema Registry enforces Avro with backward compatibility
3. **Count-Min Sketch (Flink):** First Flink job maintains a CMS per 1-minute tumbling window. Each event increments the sketch. At window close, emits (hashtag, approximate_count, window_timestamp) for all hashtags exceeding a minimum threshold
4. **Top-K Aggregator (Flink):** Second Flink job receives windowed counts, maintains a min-heap of size K per scope (global, per-region). On each window, merges new counts and emits updated top-K list
5. **Exponential Decay Scorer:** Applies time-decay to the raw counts, weighting recent windows higher. This transforms "popular" into "trending." Writes scored top-K to Redis and time-series data to Cassandra
6. **Batch Recompute (Spark):** Hourly Spark job recomputes "ground truth" top-K from raw Kafka events (full replay), reconciling any drift from the approximate streaming counts. Updates Cassandra and S3
7. **Serving:** Trending API reads from Redis (< 1ms). Content moderation service filters blacklisted hashtags before returning. Explore page frontend polls every 30s

## Partitioning Strategy

- **Kafka `hashtag-events`:** Partition by `hashtag` — ensures all events for the same hashtag land on the same partition, enabling accurate per-hashtag counting in Flink without shuffle
- **Kafka `engagement-events`:** Partition by `event_type` — different engagement types (click, share, search) may be processed by different consumer groups
- **Flink parallelism:** Key by `hashtag` for counting, then key by `region` for top-K aggregation. This creates a two-stage pipeline: parallel counting (high parallelism, matching Kafka partitions) into fan-in top-K (one operator per region)
- **Redis:** Key per `(scope, time_granularity)` — e.g., `trending:global:5m`, `trending:US:1h`. Small number of keys (< 10K), each containing a sorted set of ~50-100 entries

## Failure Handling

- **Flink CMS job failure:** Checkpoints every 30 seconds to S3. On recovery, replays from last checkpoint offset. Because CMS is an additive data structure, replaying events produces correct counts (though some events may be double-counted, which is within the 5% error tolerance)
- **Flink Top-K job failure:** Recovers from checkpoint. Stale top-K in Redis remains serveable. API returns cached data with a "last_updated" timestamp so clients know freshness
- **DLQ strategy:** Malformed events (bad schema, missing hashtag field) go to DLQ. Volume monitored via Prometheus. Team reviews daily. DLQ events do NOT block processing — they are dropped from the count
- **Kafka retention:** 48-hour retention for replay. Beyond that, Cassandra has aggregated time-series for recomputation

## Key Trade-offs

- **Approximate vs exact:** Count-Min Sketch introduces ~2-5% over-counting. This is an excellent trade-off: exact counting would require maintaining state for 50M distinct hashtags (many GB), while CMS uses ~100KB. For top-K ranking, approximate counts are sufficient because the truly trending hashtags dominate by orders of magnitude
- **Freshness vs cost:** Updating top-K every 5 seconds requires more Flink resources (more frequent window triggers, more Redis writes). Every 30 seconds is a good balance — users don't notice 30-second staleness in trending topics
- **Global vs regional accuracy:** Computing per-region trends requires either (a) separate CMS per region (more memory but accurate) or (b) a single global CMS with post-hoc regional filtering (less memory but requires geo-tagged events). We use (a) because region-specific trends matter more than saving memory
- **Velocity vs volume:** The decay function has a tunable `lambda` parameter. Too aggressive = volatile trends that appear and disappear quickly. Too gentle = stale trends that linger. Tuning this is a product decision, not an engineering one

## What Fails First

**Flink state size** hits the wall first. As the number of unique hashtags per window grows (viral events can create millions of ephemeral hashtags), the CMS must be sized to maintain accuracy. If the sketch is too small, hash collisions cause high-count hashtags to "bleed" into unrelated hashtags, polluting the top-K. The solution is adaptive sketch sizing: monitor the estimated number of unique elements (via HyperLogLog) and resize the CMS when the error bound exceeds the threshold.

Second failure point: **hot partitions in Kafka**. A single viral hashtag (e.g., during a global event) can create a hot partition. Since we partition by hashtag, one Flink subtask processes disproportionate load. Solutions: (1) use a two-phase aggregation (random partition first, then merge by hashtag), or (2) split hot keys across multiple virtual partitions with a suffix.

## v1 vs v2

### v1 — Ship in 2 weeks
- Global trending only (no regional scopes)
- Exact counting with HashMap state in Flink (works at moderate scale)
- Simple tumbling window (5-minute) with absolute count ranking (no decay)
- Manual blacklist for content moderation (static config file)
- No batch reconciliation — trust the streaming counts
- Single Flink job (counting + top-K in one pipeline)

### v2 — Scale and sophistication
- Regional trending with geo-tagged events and per-region CMS
- Count-Min Sketch for memory-efficient approximate counting
- Exponential decay scoring for velocity-based trending
- Content moderation ML model integrated as a Flink side-output filter
- Batch reconciliation via hourly Spark jobs
- Separate Flink jobs for counting vs top-K (independent scaling)
- Cassandra time-series for historical "what was trending" queries
- A/B testing framework for decay parameters
- Anti-manipulation detection (coordinated hashtag campaigns)
