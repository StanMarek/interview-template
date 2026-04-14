# News Feed / Timeline System — Architecture Design

## Requirements

### Functional
- Users can publish posts (text, images, links, videos)
- Users see a personalized feed of posts from people they follow
- Feed is ranked by relevance (not purely chronological)
- Like, comment, share interactions
- Pagination (infinite scroll)
- Follow/unfollow users

### Non-Functional
- Feed generation latency < 200ms (p99)
- New posts appear in followers' feeds within 5 seconds
- 99.99% read availability (feed is the core product)
- Read-heavy: 100:1 read-to-write ratio
- Eventually consistent (a few seconds delay for new posts is acceptable)

## Scale Estimates
- 500M DAU, 1B total users
- Average user follows 200 people
- 1M new posts/day by active creators
- Feed reads: 10B/day (each user refreshes feed 20 times)
- Read QPS: ~120K peak, Write QPS: ~12 peak post creation, but fanout creates ~2.4B feed writes/day
- Storage: 1TB/day for post metadata, 10TB/day media

## Architecture Decisions

### Hybrid Push/Pull Fan-Out Model
The fundamental decision in feed design. **Fan-out on write** (push model) pre-computes feeds at post time — when a user posts, we write the post ID to every follower's feed cache. This makes reads O(1) but writes are expensive for users with millions of followers. **Fan-out on read** (pull model) computes the feed at read time by fetching recent posts from all followed users. This makes writes O(1) but reads are expensive.

The hybrid approach: push for normal users (< 10K followers), pull for celebrities (> 10K followers). When a user reads their feed, we merge the pre-computed feed (from push) with real-time fetched posts from celebrities they follow. This caps the write amplification while keeping reads fast.

### Separate Read and Write Paths
The write path (Post Service -> Fanout Worker -> Feed Cache) and read path (Feed Service -> Feed Cache + Ranking) are independent. This lets us scale them differently — we need far more read capacity. It also means a write-path failure doesn't block reads; users still see their existing feed.

### Feed Cache as Redis Sorted Sets
Each user's feed is a Redis sorted set: member = postId, score = timestamp (or ranking score). This gives us O(log N) insertion during fanout and O(log N + K) retrieval for pagination. We cap each feed at 800 entries — nobody scrolls past 800 posts. This bounds our Redis memory to ~500M users * 800 * 8 bytes = ~3.2TB, which is manageable across a Redis cluster.

### Ranking as a Separate Service
Ranking is the most ML-intensive component and changes frequently. Isolating it lets the ML team deploy new models without touching the feed infrastructure. The ranking service scores posts based on: affinity (how often you interact with the author), recency, engagement velocity, content type preference. It re-ranks the candidate set from the feed cache.

## Component Breakdown

- **CDN**: Serves static assets, profile images, post media. Cache-Control headers for immutable media.
- **Load Balancer + API Gateway**: Route to write or read path based on endpoint. Rate limit post creation.
- **Post Service**: Validates and persists new posts. Triggers fanout asynchronously via Kafka.
- **Fanout Worker**: Consumes post events from Kafka. For each post, fetches the author's follower list from Social Graph, writes postId to each follower's feed cache. For celebrity posts, skips — they'll be pulled at read time.
- **Social Graph Service**: Manages follow relationships. Backed by Neo4j or adjacency list in MySQL. Cached heavily (follower lists change infrequently).
- **Feed Service**: On read request, fetches pre-computed feed from Redis, fetches celebrity posts, merges, sends to Ranking Service, returns paginated result.
- **Ranking Service**: ML model serving. Takes candidate posts, user features, and context (time of day, device). Returns scored and ordered list.
- **User Service**: Profile data, settings, activity history.
- **Feed Cache (Redis)**: Sorted sets per user. The hot path for reads.
- **Post Cache (Redis)**: Full post objects cached by postId. Avoids DB lookup for hydrating feed items.
- **Kafka**: Decouples post creation from fanout. Also feeds analytics pipeline.

## Data Model

### Posts (MySQL, sharded by postId)
- PK: post_id (snowflake ID)
- Columns: author_id, content, media_urls, created_at, like_count, comment_count
- Index: (author_id, created_at DESC) for profile timeline

### Follows (MySQL, sharded by follower_id)
- PK: (follower_id, followee_id)
- Index: (followee_id) for "get all followers of X" during fanout

### Feed (Redis sorted set)
- Key: `feed:{userId}`
- Members: postId, Score: timestamp or ranking score
- TTL: 7 days for inactive users

### Engagement (Kafka -> Analytics DB)
- post_id, user_id, action (view/like/comment/share), timestamp

## Key Trade-offs

- **Freshness vs latency**: Pre-computed feeds are fast to read but stale by seconds. Hybrid model adds complexity but balances both.
- **Memory vs compute**: Storing pre-computed feeds in Redis costs ~3TB RAM. The alternative (compute at read time) costs massive CPU. At 100:1 read:write, pre-computation wins.
- **Relevance vs simplicity**: Chronological feeds are simple but less engaging. Ranked feeds increase engagement metrics but require ML infrastructure and are harder to debug/explain.
- **Celebrity fan-out**: The hybrid model introduces code complexity (merge logic, two code paths) to avoid pathological O(millions) writes per celebrity post.

## What Fails First

**Fanout Worker throughput for viral posts.** When a user with 10M followers (just under the celebrity threshold, or if thresholds are misconfigured) posts, the fanout worker must write to 10M Redis keys. At 100K writes/sec per worker, that's 100 seconds of fan-out for a single post. Solution: raise the celebrity threshold dynamically based on system load, and add more fanout workers with Kafka partitioning by author_id.

## v1 vs v2

### v1 (MVP)
- Pure push model (fan-out on write for all users)
- Chronological feed (no ranking)
- Single Redis instance for feed cache
- Text-only posts
- Simple follow/unfollow

### v2
- Hybrid push/pull for celebrity handling
- ML-based ranking service
- Media posts with S3 + CDN
- Engagement tracking and analytics pipeline
- Real-time feed updates via SSE/long-polling
- Content moderation pipeline
- "Explore" / discovery feed using collaborative filtering
