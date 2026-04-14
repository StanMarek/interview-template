# Object Storage (Simplified S3) -- Architecture Design

## Requirements

### Functional
- PUT/GET/DELETE objects identified by bucket + key
- Support objects from 1 byte to 5 TB
- Multipart upload for large objects
- Object versioning (optional per-bucket)
- Lifecycle policies (auto-delete, transition to cold storage)
- Pre-signed URLs for direct upload/download
- Bucket-level access control (ACLs and IAM policies)
- List objects by prefix (for directory-like browsing)

### Non-Functional
- **Durability:** 99.999999999% (11 nines) -- losing data is unacceptable
- **Availability:** 99.99% for reads, 99.9% for writes
- **Consistency:** Read-after-write consistency for PUTs, eventual consistency for DELETEs and LIST
- **Throughput:** Handle millions of requests per second across all buckets
- **Scalability:** Store exabytes of data across thousands of storage nodes

## Scale Estimates
- 1M PUT requests/second, 10M GET requests/second
- 100 PB total stored data
- Average object size: 1 MB (but with huge variance -- 50% of objects are < 1KB, 1% are > 1GB)
- 10,000+ storage nodes across 3 availability zones
- Metadata: ~500 bytes per object, so ~500TB for 1 trillion objects

## Architecture Decisions

### Decision 1: Separation of Metadata and Data Planes

The system is split into two independent planes: the metadata plane (which tracks what objects exist and where their chunks are stored) and the data plane (which actually stores the bytes). These operate independently and scale independently.

**Why this matters:** Metadata operations (key lookup, listing) have fundamentally different access patterns than data operations (streaming large objects). Metadata is small, hot, and needs strong consistency. Data is large, warm/cold, and can tolerate eventual consistency for some operations. Combining them in a single system would force a compromise on both.

**Trade-off:** Two planes means two failure domains. A metadata failure makes all objects inaccessible even if the data is intact. But this is the standard approach (used by S3, GCS, and Azure Blob Storage) because the alternative -- co-locating metadata with data -- creates hot spots and makes rebalancing extremely difficult.

### Decision 2: Erasure Coding Over Simple Replication

For durability, we use Reed-Solomon erasure coding rather than simple 3-way replication. A typical configuration is (10, 4): the object is split into 10 data chunks and 4 parity chunks, stored across 14 different storage nodes.

**Why erasure coding:** 3-way replication stores 3 copies, using 3x the raw storage. With (10,4) erasure coding, we use only 1.4x the raw storage while tolerating up to 4 simultaneous node failures. For a system storing 100 PB, this difference (3x vs 1.4x) is hundreds of petabytes of saved disk space -- tens of millions of dollars in hardware.

**Trade-off:** Erasure coding is more CPU-intensive than replication (encoding/decoding requires matrix multiplication). It also increases read latency slightly because reading an object requires fetching chunks from multiple nodes. For small, frequently accessed objects, we might use replication instead. The key senior-level insight: the right durability strategy depends on the object's access pattern and size.

### Decision 3: Consistent Hashing for Data Placement

The placement service uses consistent hashing with virtual nodes to determine which storage nodes hold which chunks. When nodes are added or removed, only a minimal number of chunks need to be rebalanced.

**Why consistent hashing:** With naive hash-based placement (hash(key) % N), adding a node changes the mapping for nearly all keys, requiring a massive data migration. Consistent hashing limits rebalancing to ~1/N of the data. For a 100 PB system, this is the difference between moving 100 PB (days of migration) and moving ~1 PB (hours).

**Trade-off:** Consistent hashing can create imbalanced load if virtual nodes aren't tuned properly. We use a large number of virtual nodes per physical node (~1000) to ensure statistical uniformity.

### Decision 4: Write-Path -- Write to Multiple AZs Before Acknowledging

A PUT is acknowledged only after the object's chunks are durably stored in at least 2 availability zones. This ensures that a single AZ failure cannot cause data loss.

**Why this is non-negotiable:** S3's durability guarantee (11 nines) means losing less than 1 object out of 100 billion per year. This is only achievable if no single failure event (disk, node, rack, AZ) can destroy all copies. Writing to 2+ AZs before acknowledging ensures that even if an entire AZ burns down, the data is safe.

**Trade-off:** Cross-AZ writes add latency (~1-5ms per AZ hop). For write-heavy workloads, this is noticeable. But durability is the primary value proposition of object storage -- customers would never forgive data loss.

## Consistency Model

**Read-after-write consistency for new objects (PUTs).** When a PUT returns success, a subsequent GET for that key is guaranteed to return the new object. This is achieved by writing metadata to the primary partition of the metadata store before returning success.

**Eventual consistency for DELETEs.** When a DELETE returns success, the object may still be readable for a short window (seconds) because: (a) CDN/edge caches may still serve it, and (b) read replicas of the metadata store may not have propagated the deletion yet. The actual data chunks are garbage collected asynchronously.

**Eventual consistency for LIST.** Listing objects by prefix reads from metadata replicas, which may be slightly behind the primary. A just-PUT object might not appear in LIST results immediately.

**Why not strong consistency for everything:** Strong consistency for LIST would require all list operations to hit the primary metadata partition, which would create a massive bottleneck. S3 itself moved to strong consistency for GET/PUT/DELETE in 2020 but LIST can still have lag. The insight: consistency requirements should match the operation's semantics.

## Failure Modes

### Storage node failure
With (10,4) erasure coding, the system can tolerate up to 4 node failures without data loss. The repair worker detects under-replicated chunks and re-encodes them on healthy nodes. The repair must complete before more nodes fail -- this is the "durability race."

### Metadata database failure
The metadata DB (Vitess/CockroachDB) is replicated across AZs. Leader failure triggers automatic election. During failover (~seconds), writes are unavailable but reads continue from replicas. No metadata is lost because of synchronous replication.

### AZ failure
Objects are stored across multiple AZs. A single AZ failure reduces redundancy but doesn't cause data loss. The repair worker starts re-replicating affected chunks to healthy AZs immediately. During this window, the system has reduced durability (fewer parity chunks available).

### Bit rot (silent data corruption)
Checksums are computed on write and verified on every read. Background scrubbing periodically reads and verifies all stored chunks. If a chunk fails checksum verification, it's reconstructed from the remaining chunks and the corrupted copy is replaced.

### Metadata-data inconsistency
The metadata says an object exists, but its chunks are missing (or vice versa). The GC worker reconciles metadata with actual stored chunks. Orphaned chunks (data without metadata) are garbage collected. Missing chunks (metadata without data) trigger a repair or the object is marked as lost.

## Component Breakdown

| Component | Purpose |
|-----------|---------|
| **Client SDK** | Handles multipart upload, retry logic, checksum verification |
| **Load Balancer** | Routes requests, terminates TLS, enforces rate limits |
| **API Server** | Stateless request handler, orchestrates metadata + data operations |
| **Pre-signed URL Generator** | Creates time-limited, authenticated URLs for direct upload/download |
| **CDN** | Edge caching for frequently accessed objects |
| **Metadata DB** | Stores bucket configs, object keys, chunk locations, ACLs |
| **Bucket Index** | Maps object keys to chunk IDs and storage node locations |
| **Placement Service** | Consistent hashing to determine which nodes store which chunks |
| **Versioning Manager** | Tracks object versions, handles version deletion |
| **Storage Nodes** | Physical machines with local disks that store data chunks |
| **Erasure Coding** | Reed-Solomon encoding/decoding for durability |
| **Chunk Store** | Local filesystem on storage nodes, manages chunk files |
| **Checksum Validator** | Computes and verifies checksums on write and read |
| **GC Worker** | Garbage collects orphaned chunks and deleted object data |
| **Repair Worker** | Detects and re-replicates under-replicated chunks |
| **Compaction Worker** | Consolidates small objects into larger files for disk efficiency |
| **Monitoring** | Disk utilization, network throughput, error rates, repair lag |
| **Audit Log** | Records all access for compliance and security |

## Key Trade-offs

### Durability vs. Cost
Erasure coding (1.4x overhead) is cheaper than replication (3x) but requires more CPU. For cold data, erasure coding is clearly better. For hot data, replication provides faster reads (any single replica serves the full object).

### Consistency vs. Performance
Read-after-write for GET/PUT requires checking the primary metadata node. This adds latency compared to reading from the nearest replica. We accept this for correctness on individual object operations.

### Small Object vs. Large Object Optimization
Small objects (<64KB) are stored inline in the metadata DB to avoid the overhead of chunk management. Large objects use multipart upload and erasure coding. This dual-path adds complexity but is necessary because the access patterns are fundamentally different.

## What Fails First

**The metadata database becomes the bottleneck.** Every object operation requires a metadata lookup. With 10M GET requests/second and 1M PUT requests/second, the metadata DB handles 11M queries/second. Even with sharding, this is enormous.

**Mitigation:** Aggressive caching of metadata (key-to-chunk mappings change rarely). Shard metadata by bucket + key prefix. Use an in-memory cache tier (like Memcached) in front of the metadata DB. For LIST operations, use a separate read-optimized index.

## v1 vs v2

### v1 (Ship first)
- Single region, 3 AZs
- Simple 3-way replication (no erasure coding)
- Fixed-size objects only (no multipart upload)
- No versioning
- Basic ACLs (public/private/authenticated)
- Metadata in PostgreSQL with key-based sharding
- No lifecycle policies
- No CDN integration

### v2 (Scale and harden)
- Multi-region with cross-region replication
- Erasure coding with configurable durability levels
- Multipart upload for objects up to 5 TB
- Object versioning with version lifecycle
- IAM-based access control with bucket policies
- Pre-signed URLs with time-limited access
- Lifecycle policies (transition to cold storage, auto-expire)
- CDN integration for edge caching
- Server-side encryption (SSE-S3, SSE-KMS, SSE-C)
- Event notifications on object operations (like S3 events to Lambda)
- Object lock (WORM compliance for regulatory requirements)
