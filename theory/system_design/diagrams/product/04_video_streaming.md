# YouTube-Lite Video Upload & Streaming — Architecture Design

## Requirements

### Functional
- Upload videos (up to 10GB, various formats)
- Automatic transcoding to multiple resolutions (240p to 4K)
- Adaptive bitrate streaming (HLS/DASH)
- Video search by title, description, tags
- View counts, likes, comments
- Subscription feed (new videos from channels you follow)
- Thumbnail generation (auto + custom)
- Creator analytics dashboard

### Non-Functional
- Upload-to-playback latency < 10 minutes for a 10-min video
- Start-to-play (time-to-first-byte) < 200ms via CDN
- Adaptive bitrate: seamless quality switching without rebuffering
- 99.99% streaming availability
- View counts must be accurate (no over-counting for ad revenue)
- Support 1M concurrent streams

## Scale Estimates
- 800M DAU watching, 5M uploading
- 500 hours of video uploaded per minute
- Average video: 10 minutes, 500MB raw -> 200MB after compression across all qualities
- Storage: ~50PB/year for encoded video
- Streaming bandwidth: average 5Mbps * 100M concurrent = 500 Tbps peak
- Metadata reads: 1M QPS (video pages, search, recommendations)

## Architecture Decisions

### HLS (HTTP Live Streaming) Over Custom Protocols
HLS splits videos into small chunks (2-10 second segments) served over standard HTTP. Benefits: works through firewalls/proxies, cacheable by any CDN, supported by all browsers/devices natively. The manifest file (.m3u8) lists available quality levels and segment URLs. The client dynamically switches quality based on measured bandwidth. Alternative (WebRTC) is for real-time — overkill for on-demand video. DASH is equivalent; we pick HLS for wider device support.

We package once in **CMAF** (Common Media Application Format) and serve the same fragmented MP4 segments to both HLS and DASH players via different manifests. This halves CDN storage and warming cost vs maintaining separate transport-stream HLS and ISOBMFF DASH segments. For live and near-live use cases (sports, gaming) we add **LL-HLS** (Low-Latency HLS): partial segments (~200ms CMAF chunks), HTTP/2 PUSH or preload hints, and `EXT-X-PRELOAD-HINT` to bring glass-to-glass latency from 30s (classic HLS) down to 2-5s — comparable to LL-DASH (CMAF chunked transfer encoding) without giving up CDN cacheability the way WebRTC would.

### Parallel Transcoding with DAG Pipeline
A single 10-minute 4K video needs transcoding to 6 resolutions, each with multiple audio tracks. Sequential processing would take hours. Instead, we split the raw video into segments, transcode each segment in parallel across a fleet of GPU workers, then stitch results. The pipeline is a DAG: split -> [parallel transcode per resolution] -> merge -> generate manifest. We use a workflow orchestrator (like Temporal) to manage this DAG with retries. This reduces a 60-minute job to ~3 minutes.

### Separate Raw and Encoded Storage
Raw uploads go to a "raw" S3 bucket (cold storage after processing). Encoded HLS chunks go to an "encoded" S3 bucket that backs the CDN origin. Separation reasons: (1) raw files are enormous and rarely accessed after transcoding — move to Glacier after 30 days; (2) encoded bucket has specific access patterns (sequential chunk reads) and CDN integration; (3) different lifecycle policies.

### Approximate View Counting with Reconciliation
Counting views accurately at YouTube scale is surprisingly hard. A naive "increment counter per request" over-counts (bots, refreshes, ad fraud). We use a two-phase approach: (1) Real-time approximate count in Redis for display (fast, eventual consistency); (2) Batch deduplication pipeline in Kafka -> ClickHouse that counts unique (userId, videoId, watchDuration > 30s) tuples. The batch count reconciles with Redis every few minutes. For ad revenue, only the batch-verified count matters.

## Component Breakdown

- **Video CDN**: Multi-tier CDN (edge PoPs globally, regional caches, origin S3). Handles 99%+ of streaming traffic. Pre-populates edge caches for trending videos.
- **Upload LB (L4)**: TCP load balancer for large file uploads. Supports chunked/resumable uploads (tus protocol).
- **Upload Service**: Handles resumable upload sessions. Stores raw video to S3. Triggers transcoding pipeline via message queue.
- **Transcoder**: GPU-accelerated workers (FFmpeg-based). Runs on spot instances for cost savings. Produces HLS segments + manifests.
- **Thumbnail Generator**: Extracts frames at key moments using scene detection. Generates multiple candidate thumbnails. Creator can choose or upload custom.
- **Video Metadata Service**: CRUD for video titles, descriptions, tags, categories. Serves video watch pages.
- **Search Service**: Full-text search on video metadata + auto-generated captions. Elasticsearch with relevance tuning.
- **Recommendation Service**: ML-based "up next" and homepage recommendations. Uses collaborative filtering + content features.
- **View Counter Worker**: Consumes view events from Kafka, deduplicates, updates Redis approximate count, writes to ClickHouse for accurate count.
- **Analytics Pipeline**: Processes watch-time, audience retention, traffic sources for creator dashboard. Kafka -> Flink -> ClickHouse.

## Data Model

### Videos (MySQL, sharded by video_id)
- PK: video_id (snowflake)
- Columns: channel_id, title, description, tags, duration_sec, status (processing/live/removed), upload_time, view_count, like_count
- Indexes: (channel_id, upload_time DESC), (status)

### Video Segments (metadata in MySQL)
- PK: (video_id, resolution, segment_index)
- Columns: s3_key, duration_ms, byte_size
- Used to construct HLS manifest

### Comments (MySQL or Cassandra, sharded by video_id)
- PK: comment_id
- Columns: video_id, user_id, text, parent_id, created_at, like_count

### Watch History (Cassandra)
- Partition key: user_id
- Clustering key: watched_at DESC
- Columns: video_id, watch_duration_sec, completed

### View Events (Kafka -> ClickHouse)
- video_id, user_id, session_id, watch_duration, timestamp, device_type, geo

## Key Trade-offs

- **Transcode all resolutions eagerly vs lazily**: We transcode all standard resolutions on upload. This costs ~6x compute per video but means every viewer gets instant playback. Lazy transcoding (transcode on first request for a resolution) saves compute for unpopular videos but causes first-viewer latency. Compromise: transcode 360p/720p immediately (covers 80% of views), transcode 1080p/4K lazily.
- **CDN cost vs quality**: Video streaming costs dominate (bandwidth is expensive). We use ABR to avoid sending 4K to someone on a 2G connection. We also limit CDN caching for long-tail videos — the top 1% of videos generate 50% of views.
- **View count accuracy vs real-time**: The 2-phase approach delays accurate counts by minutes. Creators see approximate real-time counts; revenue calculations use the accurate delayed count. This is a trust trade-off.

## What Fails First

**Transcoding queue depth during viral upload spikes.** If a major event causes 10x normal upload volume, the transcode queue backs up, and upload-to-playback latency balloons from 10 minutes to hours. Solutions: (1) Auto-scale GPU workers on spot instances; (2) Priority queuing — transcode popular creators' videos first; (3) Fast-track: make 360p available immediately while higher resolutions process; (4) Pre-warm GPU capacity based on predicted events (sports, elections).

## v1 vs v2

### v1 (MVP)
- Upload and transcode to 3 resolutions (360p, 720p, 1080p)
- Basic HLS streaming via CloudFront
- Simple search on titles
- View counts (approximate, no dedup)
- Comments with basic pagination
- Single transcode worker per video (sequential)

### v2
- Parallel DAG-based transcoding with 6+ resolutions
- CMAF packaging (single segment set serves both HLS and DASH manifests)
- AV1 / HEVC encodes alongside H.264 for 30-50% bandwidth savings on supporting clients
- Resumable uploads (tus protocol)
- ML-based recommendations and personalized homepage
- Accurate view counting with fraud detection
- Creator analytics dashboard (retention curves, traffic sources)
- Auto-generated captions (speech-to-text)
- Live streaming support (RTMP/SRT ingest -> LL-HLS / LL-DASH output, 2-5s glass-to-glass)
- DRM (Widevine / FairPlay / PlayReady) via CMAF Common Encryption (CENC)
- HTTP/3 + QUIC at the edge for faster startup and better mobile-network resilience
- Content moderation (copyright detection via fingerprinting)
