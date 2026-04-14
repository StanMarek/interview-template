# Instagram / Photo-Sharing App — Architecture Design

## Requirements

### Functional
- Upload photos with captions, filters, and tags
- Follow users and see their posts in a feed
- Like, comment on posts
- Stories (ephemeral 24-hour content)
- Explore/discover page with recommendations
- Search by hashtag, location, user
- Direct messaging (can reference chat system design)
- Push notifications for interactions

### Non-Functional
- Upload latency < 2s (perceived — show optimistic UI)
- Feed load < 300ms
- Image serving < 100ms via CDN
- 99.99% availability for reads
- Eventually consistent for likes/comments (counters can lag by seconds)
- Support 200M+ photo uploads/day

## Scale Estimates
- 1B DAU, 2B registered users
- 200M photos uploaded/day, average 3MB each -> 600TB/day raw
- With 4 resolutions per image: 2.4PB/day storage
- Feed reads: 20B/day (~250K QPS peak)
- Like/comment writes: 5B/day (~60K QPS peak)
- Total image storage: growing at ~800PB/year

## Architecture Decisions

### Direct-to-S3 Upload via Pre-Signed URLs
Client requests a pre-signed URL from the Upload Proxy, then uploads directly to S3. This avoids routing multi-megabyte image data through our application servers. The Upload Proxy only handles the lightweight URL generation (< 1KB request/response). After upload completes, S3 triggers an event that kicks off the image processing pipeline. This is critical at 200M uploads/day — routing images through app servers would require enormous bandwidth.

### Eager Image Resizing vs Lazy Resizing
We generate all required sizes (thumbnail 150x150, feed 640x640, full 1080x1080, original) at upload time rather than on-demand. Rationale: images are viewed far more than uploaded (a single popular image might be viewed 10M+ times). Paying the compute cost once at write time and caching the result in CDN is cheaper than resizing on every cache miss. The trade-off is higher storage cost and slower upload processing, but users don't wait — they see optimistic UI.

### Separate Counters from Content
Like counts and comment counts are stored in Redis (Counter Cache), not in the post row. Why: updating a counter in MySQL means row-level locking on the most frequently written rows (viral posts). Redis INCR is atomic, single-threaded, and handles millions of increments per second. We periodically flush Redis counters to MySQL for durability. If Redis loses data, we recount from the likes table — it's a derived value.

### Stories as a Separate Service
Stories have fundamentally different characteristics than posts: they expire after 24h, are viewed sequentially (not in a ranked feed), and have much higher view rates relative to creation. The Story Service uses a different storage pattern — TTL-based keys in Redis for active stories, no permanent archival. Keeping this separate prevents story traffic from impacting feed infrastructure.

## Component Breakdown

- **CDN**: Serves all images. Multi-tier: edge -> regional -> origin. Cache hit ratio should be > 95%. Uses consistent hashing for cache keys.
- **Upload Proxy**: Generates pre-signed S3 URLs, validates auth, enforces file size limits. Stateless.
- **API Gateway**: Routes to services, handles auth, rate limiting. GraphQL for flexible client queries.
- **Post Service**: CRUD for posts. Triggers fanout on create. Hydrates post data for feed.
- **Feed Service**: Pre-computed feed retrieval, merges with celebrity posts, applies ranking.
- **User Service**: Profile management, follow graph, blocking.
- **Search Service**: Hashtag search, user search, location search. Backed by Elasticsearch.
- **Story Service**: Create/retrieve/expire stories. Viewer tracking.
- **Notification Service**: Push notifications for likes, comments, follows, mentions.
- **Image Processor**: Resize to multiple formats, strip EXIF, apply filters, generate blurhash placeholder. GPU-accelerated.
- **Fanout Worker**: Pre-computes feeds (hybrid push/pull model like news feed).
- **Moderation ML**: NSFW detection, spam detection, content policy enforcement. Runs async on upload.

## Data Model

### Posts (MySQL, sharded by post_id)
- PK: post_id (snowflake)
- Columns: user_id, caption, location_id, created_at, image_urls (JSON array of size variants)
- Index: (user_id, created_at DESC) for profile grid

### Follows (MySQL, sharded by follower_id)
- PK: (follower_id, followee_id)
- Reverse index: (followee_id, follower_id) for "who follows me"

### Likes (MySQL, sharded by post_id)
- PK: (post_id, user_id) — prevents double-like
- Index: (user_id, created_at DESC) for "posts I've liked"

### Comments (MySQL, sharded by post_id)
- PK: comment_id
- Columns: post_id, user_id, text, parent_comment_id, created_at
- Index: (post_id, created_at)

### Feed (Redis sorted set)
- Key: feed:{userId}, Members: postId, Score: ranking_score

### Counters (Redis)
- Key: likes:{postId} -> integer
- Key: comments:{postId} -> integer

## Key Trade-offs

- **Storage cost vs compute cost**: Pre-generating 4 image sizes costs 4x storage but eliminates on-the-fly resizing for billions of views. At image storage scale, this is a business decision (~$20M/year in S3 costs).
- **CDN consistency**: After a user deletes a post, CDN edge caches may still serve the image for minutes. We accept this for the performance benefit; critical cases (legal/CSAM) use CDN purge APIs.
- **Counter accuracy vs performance**: Redis counters may slightly disagree with MySQL ground truth during failures. Users don't notice if a like count is off by 1-2 for a few minutes.
- **Explore feed personalization vs cold start**: New users have no interaction history for ML ranking. We fall back to popularity-based recommendations, which creates a rich-get-richer effect.

## What Fails First

**Image storage costs.** At 2.4PB/day (with all size variants), storage grows by ~800PB/year. Within 2-3 years, you're at exabyte scale. Solution: tiered storage — move images older than 90 days to S3 Glacier/Infrequent Access. Implement "intelligent tiering" based on access patterns. Serve old images from cheaper storage with slightly higher latency (users rarely view 3-year-old posts). Also consider more aggressive compression (WebP/AVIF) to reduce size by 30-50%.

## v1 vs v2

### v1 (MVP)
- Photo upload with 2 size variants (thumbnail + full)
- Chronological feed from followed users
- Like and comment
- Profile page with grid
- PostgreSQL for everything
- Single S3 bucket + CloudFront

### v2
- Stories with ephemeral 24h lifecycle
- ML-ranked feed with explore page
- 4 image size variants with WebP support
- Direct-to-S3 upload with pre-signed URLs
- Sharded MySQL + Redis caching layer
- Content moderation pipeline
- Hashtag and location search
- DM integration
- Video support (Reels)
