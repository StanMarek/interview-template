# Object / Blob Storage

## What It Is
Object storage stores data as objects (blobs) in a flat namespace, each identified by a unique key. Unlike file systems (hierarchical) or block storage (fixed-size blocks), object storage is designed for massive scale, durability, and HTTP access.

## Key Characteristics
- **Flat namespace**: No directories. Keys like `images/2024/photo.jpg` are just strings (the `/` is convention, not a real folder)
- **Immutable objects**: Write once, read many. Updates create new versions (or overwrite).
- **HTTP API**: PUT/GET/DELETE via REST. No filesystem mounting.
- **Massive scale**: Virtually unlimited storage. Exabytes across providers.
- **High durability**: 99.999999999% (11 nines) durability. Data replicated across multiple facilities.
- **Eventual consistency**: Historically; most major providers now offer strong read-after-write consistency. **S3 achieved strong read-after-write consistency for all operations (GET, PUT, LIST) in December 2020** — no longer eventual for new objects. Prior folklore about "upload+immediate-list" caveats is outdated.

## Storage Classes / Tiers

| Tier | Access Pattern | Cost | Example |
|------|---------------|------|---------|
| **Standard/Hot** | Frequent access | Highest storage, lowest retrieval | S3 Standard, GCS Standard |
| **Infrequent Access** | Monthly access | Lower storage, per-retrieval fee | S3 IA, GCS Nearline |
| **Archive** | Yearly access | Cheapest storage, high retrieval cost & latency | S3 Glacier, GCS Archive |

**Lifecycle policies** automatically transition objects between tiers based on age or access patterns.

### Glacier Tiers
**S3 Glacier Instant Retrieval** (ms access, cheaper than Standard-IA for rarely-accessed data), **S3 Glacier Flexible Retrieval** (1min-12hr retrieval), **S3 Glacier Deep Archive** (12hr retrieval, lowest cost).

### S3 Select
**S3 Select** — push-down SQL filtering on CSV/JSON/Parquet in S3. Avoids transferring full objects. Replaced by more flexible Athena/Iceberg for many use cases.

### GCS vs Azure Blob
**GCS** storage classes: Standard, Nearline (30d), Coldline (90d), Archive (365d). **Azure Blob** tiers: Hot, Cool, Cold, Archive. Similar pricing model to S3 IA/Glacier tiers.

## Common Use Cases
- **Static asset storage**: Images, videos, CSS, JS served via CDN
- **Data lake**: Raw data for analytics (Parquet, JSON, CSV files)
- **Backups & archives**: Database backups, compliance archives
- **User-generated content**: Uploaded files, documents, media
- **ML training data**: Large datasets for model training
- **Log storage**: Application and access logs

## Pre-Signed URLs
Generate a temporary URL that grants time-limited access to a private object without exposing credentials. The URL contains embedded authentication.
- **Upload**: Client gets a pre-signed PUT URL, uploads directly to object storage (bypasses your server)
- **Download**: Client gets a pre-signed GET URL, downloads directly

This pattern offloads bandwidth from your servers and is essential for handling large file uploads.

## Multipart Upload
For large files (>100MB), split into parts, upload in parallel, then combine. Supports resume on failure (only re-upload failed parts).

## Versioning
Keep every version of every object. Useful for audit trails, accidental deletion recovery. Increases storage costs.

## Architecture for File Upload Systems

```
Client → API Server (auth, validation) → Generate pre-signed URL → Return URL to client
Client → Direct upload to Object Storage via pre-signed URL
Object Storage → Event trigger (S3 notification, GCS Pub/Sub) → Processing pipeline (resize, transcode)
Client → CDN → Object Storage (for reads)
```

## Possible Interview Questions
1. "Design a file storage service like Dropbox/Google Drive."
2. "How would you handle image uploads for a social media platform at scale?"
3. "What are pre-signed URLs and why are they important?"
4. "How would you migrate 50TB of data between storage tiers?"
5. "How does object storage achieve 11 nines of durability?"
6. "Compare object storage vs block storage vs file storage."
