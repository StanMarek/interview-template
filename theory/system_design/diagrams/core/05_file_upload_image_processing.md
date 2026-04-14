# File Upload & Image Processing Service -- Architecture Design

## Requirements

### Functional
- Upload images/files up to 100 MB via web and mobile clients
- Generate multiple image variants: thumbnail (150x150), medium (600x600), large (1200x1200)
- Convert to modern formats (WebP/AVIF) for bandwidth savings
- Virus/malware scanning before making files publicly accessible
- Serve processed images via CDN with cache-friendly URLs
- Support resumable uploads for large files on unreliable connections
- Metadata extraction (EXIF, dimensions, file type)

### Non-Functional
- **Upload latency:** < 2 seconds for a 5 MB image (excluding network transfer)
- **Processing latency:** All variants ready within 30 seconds of upload
- **Availability:** 99.9% for uploads; processing can be eventually consistent
- **Durability:** 99.999999999% (11 nines) -- S3's durability guarantee
- **Throughput:** 10K uploads/minute sustained

## Scale Estimates
- **Uploads/day:** 5M images
- **Average file size:** 3 MB
- **Raw storage/day:** 15 TB. Processed variants add ~2x = ~45 TB/day
- **Annual storage:** ~16 PB (with processed variants)
- **CDN bandwidth:** If each image is viewed 10 times avg at 200 KB (optimized) = 10B * 200 KB = 2 PB/month
- **Processing compute:** Each image needs ~500ms of CPU. 5M * 0.5s = 2.5M CPU-seconds/day

## Architecture Decisions

### Pre-Signed URL Upload (Client-Direct-to-S3)
The most critical decision: clients upload directly to S3, NOT through our API servers.

**Why not proxy through API servers?**
- A 10 MB upload through an API server ties up a thread/connection for several seconds
- At 10K uploads/minute, that's 10K concurrent long-lived connections
- The API server becomes a bandwidth bottleneck (all upload bytes flow through it)

**Pre-signed URL flow:**
1. Client calls `POST /upload/init` with filename, content-type, size
2. API server validates, creates a metadata record, generates a pre-signed S3 PUT URL (valid for 15 minutes)
3. Client uploads directly to S3 using the pre-signed URL
4. S3 emits an event notification on upload completion

**Benefits:** API servers stay lightweight (only JSON payloads). S3 handles the heavy data transfer with its massive bandwidth. The API server just orchestrates.

**Trade-off:** Slightly more complex client implementation (two-step process). But every major cloud file upload service uses this pattern because the alternative doesn't scale.

### Event-Driven Processing Pipeline
When a file lands in the raw S3 bucket, S3 triggers an event -> SQS queue -> processing workers. This is superior to synchronous processing because:

- **Upload response is instant:** The client gets a success response as soon as S3 confirms the upload. Processing happens in the background
- **Natural backpressure:** If processing is slow, the queue grows. Workers auto-scale based on queue depth
- **Retry for free:** If a worker fails mid-processing, the message returns to the queue after visibility timeout
- **Multiple processing steps can fan out:** One upload event triggers resize, thumbnail, format conversion, virus scan in parallel

### Separate Raw and Processed Buckets
Two S3 buckets, not one:
- **Raw bucket:** Private. Only accessible via pre-signed URLs. Contains unscanned, unprocessed originals
- **Processed bucket:** CDN origin. Contains processed, scanned, safe images

**Why separate?** Security. The raw bucket may contain malicious files, oversized images, or inappropriate content. Only files that pass all validation/scanning steps are written to the processed bucket and made available via CDN.

### CDN with Content-Addressable URLs
Image URLs are deterministic based on content and variant:
```
https://cdn.example.com/images/{image_id}/{variant}.{format}
e.g., https://cdn.example.com/images/abc123/thumb.webp
```

Benefits:
- **Infinitely cacheable:** Same URL always returns the same content (immutable). Set `Cache-Control: public, max-age=31536000`
- **Cache invalidation is unnecessary:** To update an image, upload a new one (new image_id). Old CDN caches expire naturally
- **CDN hit rate approaches 100%** for popular images

### Virus Scanning Before Public Access
Files are quarantined in the raw bucket until scanned. The virus scanner is a separate worker (not inline with upload) because:
- Scanning takes 1-5 seconds per file -- too slow for synchronous upload
- Scanner software (ClamAV) needs regular signature updates
- False positives should be routed to a review queue, not immediately rejected

## Component Breakdown

| Component | Role |
|---|---|
| **Web/Mobile Clients** | Upload files and display processed images |
| **Upload API** | Validates upload request, creates metadata record, returns pre-signed S3 URL |
| **CDN (CloudFront)** | Caches and serves processed images globally. Origin is the processed S3 bucket |
| **S3 Raw Bucket** | Stores original uploads. Private. Triggers S3 events on new objects |
| **S3 Processed Bucket** | Stores resized, optimized, scanned images. CDN origin |
| **Metadata DB (PostgreSQL)** | Stores file metadata: id, user, original filename, content type, dimensions, variants, processing status |
| **S3 Event Queue (SQS)** | Receives S3 new-object events, delivers to processing workers |
| **Resize Worker** | Generates size variants (thumb, medium, large) using ImageMagick or libvips |
| **Thumbnail Generator** | Creates smart-cropped thumbnails with face detection for profile photos |
| **Virus Scanner** | Scans raw files with ClamAV. Quarantines or approves |
| **WebP Converter** | Converts JPEG/PNG to WebP/AVIF for bandwidth savings (30-50% smaller) |
| **Dead Letter Queue** | Captures permanently failed processing jobs for manual investigation |

## Key Trade-offs

- **Eager vs lazy processing:** Eager (process all variants on upload) wastes compute for rarely-viewed images. Lazy (process on first request) causes slow first-view. **Decision:** Eager for common variants (thumb + medium), lazy for large/exotic formats
- **Storage cost vs compute cost:** Storing pre-computed variants costs more storage. Computing on-demand (via Lambda@Edge or imgproxy) costs more compute but less storage. At our scale, pre-computed wins because CDN caching amortizes the storage cost
- **WebP vs AVIF:** WebP has universal browser support. AVIF is 20% smaller but slower to encode and lacks Safari < 16 support. **Serve WebP by default, AVIF via Accept header negotiation**
- **Resumable uploads:** Using S3 multipart upload with tus.io protocol adds client complexity but is essential for mobile on flaky connections. Not needed for v1 if images are < 10 MB

## What Fails First

**Processing worker throughput** is the first bottleneck. Image processing is CPU-intensive. At 5M images/day, each needing ~4 variants * 500ms = 2 seconds of CPU:
- Total: 10M CPU-seconds/day = ~116 CPU-days
- Need ~120 processing workers (assuming 1 vCPU each, 80% utilization)

**Mitigation:** Use spot/preemptible instances (processing is idempotent and retryable). Use libvips instead of ImageMagick (4-8x faster, lower memory). Auto-scale based on SQS queue depth.

**Secondary risk:** S3 storage costs grow linearly and never stop. At 16 PB/year, even at $0.023/GB/month, that's $4.4M/year. Mitigation: lifecycle policies to move old images to S3 Glacier, delete unviewed images after 1 year.

## v1 vs v2

### v1 (Ship in 2 weeks)
- Pre-signed URL upload to S3
- Single processing Lambda triggered by S3 event (resize + thumbnail)
- WebP conversion only
- No virus scanning (trust-but-verify with periodic batch scans)
- CloudFront CDN for serving
- PostgreSQL for metadata
- Max file size 10 MB

### v2 (Production-grade)
- Resumable multipart uploads with tus.io protocol
- Full processing pipeline: resize, thumbnail, virus scan, format conversion, EXIF stripping
- Smart cropping with face detection
- On-the-fly image transformation via imgproxy for rare variants
- Content moderation (NSFW detection) via ML model
- Image deduplication via perceptual hashing (save storage for duplicate uploads)
- Multi-region upload endpoints (upload to nearest S3 region, replicate)
- S3 lifecycle policies: Standard -> IA -> Glacier
- Max file size 100 MB with resumable uploads
