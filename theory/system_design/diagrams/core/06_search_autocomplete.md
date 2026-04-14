# Search Autocomplete System -- Architecture Design

## Requirements

### Functional
- As the user types, suggest top 5-10 completions matching the prefix
- Results ranked by popularity (query frequency), recency, and personalization
- Handle typos with fuzzy matching (optional, v2)
- Filter out offensive/inappropriate suggestions
- Support trending queries (recency-boosted scoring)
- Personalized suggestions based on user search history (optional)

### Non-Functional
- **Latency:** < 50ms end-to-end (user perceives delay > 100ms as sluggish)
- **Availability:** 99.99% -- autocomplete failure visibly degrades the search experience
- **Throughput:** 100K suggestion requests/sec (every keystroke triggers a request, though debounced)
- **Freshness:** New trending queries should appear in suggestions within 15-30 minutes

## Scale Estimates
- **Search queries/day:** 500M
- **Autocomplete requests/day:** 3B (avg ~6 keystrokes per search, with debouncing)
- **Unique query strings:** 100M (with long-tail distribution)
- **Trie size:** Top 10M queries by frequency. Each entry ~100 bytes avg = ~1 GB (fits in memory easily)
- **Query log volume:** 500M/day * 100 bytes = 50 GB/day

## Architecture Decisions

### Trie (Prefix Tree) as the Core Data Structure
A trie is the natural data structure for prefix-based lookup because:
- **O(L) lookup time** where L is the prefix length (typically 3-20 characters) -- effectively constant time
- Each trie node stores a list of top-K completions, pre-computed
- The entire trie for 10M queries fits in ~1-2 GB of RAM

**Why not Elasticsearch?** ES is excellent for full-text search but overkill for pure prefix matching. A trie query completes in microseconds; an ES prefix query takes milliseconds. For autocomplete's extreme latency requirements, in-memory trie wins.

**Hybrid approach:** Use the trie for prefix matching (fast path) and fall back to Elasticsearch for fuzzy matching when the trie returns < 5 results (slow path, handles typos).

### Pre-Computed Top-K at Each Trie Node
Rather than traversing the subtree at query time to find the best completions, we pre-compute the top-K results at each node during trie construction:

```
Node for "app" stores: ["apple music", "apple store", "applebee's", "apple tv", "application"]
```

This makes query-time O(1) after the prefix traversal -- just return the stored list. The trade-off is that building the trie is more expensive (O(N * L) where N is the number of queries), but building happens offline and infrequently (every 15-30 minutes).

### Offline Trie Rebuilding (Not Real-Time Updates)
The trie is rebuilt periodically (every 15-30 minutes) rather than updated in real-time. Why:
- **Immutable data structure:** A read-only trie has zero locking overhead. Concurrent reads from thousands of threads with no synchronization
- **Atomic swap:** Build the new trie in a background process. When ready, atomically swap the pointer. No partial updates, no inconsistency
- **Simplicity:** Real-time trie updates require careful concurrency control. Periodic rebuilds are dead simple
- **Staleness is acceptable:** A 15-minute delay for new suggestions is imperceptible to users

### CDN Caching for Popular Prefixes
The top ~10K prefixes (1-3 characters) account for the vast majority of autocomplete requests. Cache these at the CDN edge:
- `GET /suggest?q=a` -> Cache HIT (CDN edge), TTL 5 minutes
- `GET /suggest?q=app` -> Cache HIT (CDN edge), TTL 5 minutes
- `GET /suggest?q=apple mu` -> Cache MISS (long tail), hits origin

**Impact:** CDN absorbs 60-80% of all autocomplete traffic. At 100K req/sec, this reduces origin load to 20-40K req/sec.

**Personalization conflict:** CDN caching doesn't work with personalized results. Solution: use CDN for anonymous/logged-out users. For logged-in users, skip CDN and merge generic suggestions with personalized ones at the application layer.

### Client-Side Debouncing and Prefetching
Reducing the number of requests is as important as making each request fast:
- **Debounce:** Wait 200ms after the last keystroke before sending a request. Cuts requests by ~60%
- **Local cache:** Cache previous responses. If the user typed "app" and got results, filter locally when they type "appl" -- no server request needed until the local cache can't satisfy the query
- **Abort previous:** If a new keystroke arrives before the previous response, abort the in-flight request

### Query Log Pipeline for Frequency Aggregation
Every search query (the one the user actually submits, not every keystroke) is logged to Kafka. A Flink streaming job aggregates frequencies using a tumbling window:
- Count query frequencies in 15-minute windows
- Apply exponential decay to older windows (trending queries rise, stale queries fade)
- Output: `{query_string, frequency_score, recency_score}` -> feeds trie builder

## Component Breakdown

| Component | Role |
|---|---|
| **Browser (Debounced Input)** | Debounces keystrokes, caches previous results, aborts stale requests |
| **CDN Edge Cache** | Caches popular prefix responses (short prefixes). TTL 5 minutes |
| **API Gateway** | Rate limiting (prevent abuse), request routing |
| **Suggestion Service** | Looks up prefix in in-memory trie, returns top-K completions |
| **Ranking/Scoring Service** | Applies personalization, recency boost, and filtering to trie candidates |
| **Trie / Prefix Index** | In-memory trie with pre-computed top-K at each node. Read-only, atomically replaced |
| **Elasticsearch** | Fallback for fuzzy matching. Handles typos and partial matches when trie has insufficient results |
| **Redis (Popular Queries)** | Stores real-time query frequency counters. Used by ranking service for trending boost |
| **Query Logger (Kafka)** | Logs every submitted search query for frequency aggregation |
| **Frequency Aggregator (Flink)** | Streaming job that counts query frequencies with time decay |
| **Trie Builder** | Batch job (every 15 min) that builds a new trie from aggregated frequencies and deploys it |

## Key Trade-offs

- **Freshness vs stability:** Rebuilding the trie every 15 minutes means trending queries appear with a delay. Rebuilding every minute adds infrastructure cost and risk (what if a bad build deploys?). 15 minutes is the sweet spot
- **Memory vs coverage:** Storing the top 10M queries covers 99%+ of autocomplete traffic. Storing 100M would cover more long-tail but costs 10x more memory per server. The long tail rarely appears in autocomplete anyway
- **Generic vs personalized:** Personalized suggestions are more relevant but cannot be CDN-cached, multiplying origin load. The compromise: top 5 generic + top 2 personalized = 7 suggestions
- **Trie vs sorted set (Redis ZRANGEBYLEX):** Redis sorted sets support prefix queries natively and scale horizontally. But each query is a network call (~1ms) vs in-memory trie lookup (~10us). At autocomplete latency requirements, in-memory wins

## What Fails First

**Memory pressure during trie rebuild.** During the atomic swap, both the old and new trie exist in memory simultaneously. If each is 2 GB, you need 4 GB free during the swap window. On machines with many other services, this can cause OOM.

**Mitigation:** Dedicate trie-serving instances with sufficient headroom. Use memory-mapped files for the trie so the OS can page out the old one immediately after swap.

**Secondary risk:** CDN cache stampede on TTL expiry. Popular prefixes expire simultaneously, causing a thundering herd on origin. Mitigation: jitter the TTL (5 min +/- random 30 sec).

## v1 vs v2

### v1 (Ship in 1 week)
- Sorted set in Redis (`ZRANGEBYLEX`) for prefix matching -- no custom trie
- Batch job (daily) to aggregate query frequencies from search logs
- No CDN caching (Redis handles the load at small scale)
- No personalization
- No fuzzy matching
- 200ms debounce on client

### v2 (Production-grade at 100K QPS)
- In-memory trie with pre-computed top-K on dedicated serving nodes
- CDN edge caching for top 10K prefixes
- Real-time frequency aggregation (Flink) with 15-minute trie rebuilds
- Personalized suggestions (user history merged with generic)
- Fuzzy matching via Elasticsearch fallback
- Offensive content filtering pipeline
- A/B testing framework for ranking algorithm improvements
- Multi-language support with language-specific trie instances
