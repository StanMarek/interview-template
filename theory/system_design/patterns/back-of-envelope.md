# Back-of-the-Envelope Estimation

## What It Is
Quick, approximate calculations to evaluate whether a system design is feasible. Interviewers use these to test your ability to think quantitatively about scale.

## The Process
1. **Clarify assumptions** (DAU, read:write ratio, data size per record)
2. **Calculate QPS** (queries per second)
3. **Calculate storage** (total data over time)
4. **Calculate bandwidth** (data transfer per second)
5. **Determine infrastructure needs** (servers, DB size, cache size)

## Essential Formulas

### QPS (Queries Per Second)
```
Average QPS = DAU × avg_queries_per_user / 86,400
Peak QPS = Average QPS × 2 to 5 (depends on traffic pattern)
```

### Storage
```
Daily storage = daily_writes × avg_size_per_write
Yearly storage = daily_storage × 365
5-year storage = yearly_storage × 5 (common planning horizon)
```

### Bandwidth
```
Incoming bandwidth = write_QPS × avg_write_size
Outgoing bandwidth = read_QPS × avg_response_size
```

### Servers
```
If one server handles ~1000 QPS (typical web server):
Servers needed = peak_QPS / 1000
Add 2-3x for redundancy
```

## Numbers You Must Know

### Time
| Unit | Seconds |
|------|---------|
| 1 day | 86,400 (~10^5) |
| 1 month | ~2.5 × 10^6 |
| 1 year | ~3.15 × 10^7 |

### Data Size
| Unit | Bytes |
|------|-------|
| 1 char (ASCII) | 1 byte |
| 1 char (UTF-8) | 1-4 bytes |
| UUID | 16 bytes (128 bits) |
| Timestamp | 8 bytes |
| Integer (64-bit) | 8 bytes |
| Typical tweet/short text | ~300 bytes |
| Typical JSON API response | ~1-5 KB |
| Typical web page | ~2-5 MB |
| Typical photo (compressed) | ~200 KB - 2 MB |
| Typical video (1 min, 720p) | ~50-100 MB |

### Scale References
| Service | DAU | QPS |
|---------|-----|-----|
| Small startup | 10K | ~1 QPS |
| Medium app | 1M | ~100 QPS |
| Large app | 100M | ~10K QPS |
| Twitter scale | 300M | ~300K read QPS |
| Google Search | 1B+ | ~70K QPS |

### Latency Numbers (Updated — modern NVMe SSDs are ~10× faster than Jeff Dean's 2012 numbers)
| Operation | Time |
|-----------|------|
| L1 cache ref | 0.5 ns |
| L2 cache ref | 7 ns |
| Main memory ref (DRAM) | 100 ns |
| NVMe SSD random read (2025) | ~10-100 μs |
| SATA SSD random read (classic) | ~100-150 μs |
| HDD seek | ~5-10 ms |
| Network: same datacenter RTT | ~0.5 ms |
| Network: same continent RTT | ~10-30 ms |
| Network: cross-continent RTT (e.g. CA → Europe) | ~100-150 ms |
| TLS handshake (TLS 1.3 0-RTT) | ~0-1 RTT |
| TLS handshake (TLS 1.2) | ~2 RTTs |
| Read 1 MB sequentially from DRAM | ~100-250 μs |
| Read 1 MB sequentially from NVMe SSD | ~200-500 μs |
| Read 1 MB sequentially from HDD | ~10-20 ms |
| Gzip compression of 1 KB | ~3 μs |
| UUID generation | ~100 ns |

### System Capacity Rules of Thumb (2025-2026)
| Component | Typical Capacity |
|-----------|-----------------|
| Single web server (Node/Go/Java) | 5K-50K QPS (depends on workload) |
| Single Redis instance | 100K-1M QPS (simple ops) |
| Single MySQL/Postgres instance | 5K-20K QPS (depends on query complexity, SSD) |
| Single Kafka broker | 100K-1M msgs/sec (with batching + compression) |
| Single ElasticSearch node | 1K-10K QPS (depends on query type) |
| Single machine memory (cloud VM) | 128 GB - 2 TB |
| Single machine disk | 2-32 TB NVMe SSD |
| Network bandwidth (datacenter) | 10-100 Gbps |

## Worked Example: URL Shortener

**Assumptions**: 100M DAU, 1 URL shortened per user/day, 100:1 read:write ratio

**Write QPS**: 100M / 86400 ≈ 1,200 QPS. Peak ≈ 3,600 QPS.

**Read QPS**: 1,200 × 100 = 120,000 QPS. Peak ≈ 360,000 QPS.

**Storage**: Each record: short_url (7 bytes) + long_url (100 bytes avg) + metadata (50 bytes) ≈ 160 bytes.
Daily: 100M × 160 = 16 GB/day. 5 years: 16 × 365 × 5 ≈ 29 TB.

**Bandwidth**: Read: 360K QPS × 160 bytes = 57.6 MB/s. Manageable.

**Cache**: 20% of URLs generate 80% of traffic (Pareto). Cache the top 20%:
Daily reads = 100M × 100 = 10B. Cache 20% of unique URLs: 100M × 0.2 = 20M entries × 160 bytes ≈ 3.2 GB. Fits in one Redis instance.

## Common Estimation Mistakes
- Forgetting to account for replication (2-3× storage for replicas)
- Using average QPS instead of peak QPS for capacity planning
- Not considering metadata and index overhead (typically 20-50% on top of raw data)
- Forgetting that images/videos dominate storage and bandwidth
- Not distinguishing read vs write QPS (very different scaling strategies)

## Possible Interview Questions
1. "Estimate the storage needed for a service like Instagram for 5 years."
2. "How many servers do we need to handle 1M concurrent users?"
3. "Estimate the QPS for a chat application with 50M DAU."
4. "How much cache memory do we need for the top 20% of queries?"
5. "Estimate the bandwidth needed to serve a video streaming platform."
