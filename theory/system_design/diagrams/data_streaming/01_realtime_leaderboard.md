# Real-Time Leaderboard — Architecture Design

## Requirements

### Functional
- Display top-K players (e.g., top 100) for a given game or contest in real time
- Support per-user rank lookup ("What is my rank?")
- Support multiple leaderboard scopes: global, regional, friends-only
- Handle score updates (incremental and absolute) from game servers
- Provide historical leaderboard snapshots (daily/weekly/seasonal)

### Non-Functional
- Sub-100ms read latency for top-K queries
- Score update propagation to leaderboard within 1-2 seconds
- At-least-once delivery for score events (idempotent updates)
- Support 100K+ concurrent players with bursty score submissions
- 99.9% availability — leaderboard must never go fully dark during a live event

## Scale Estimates
- **Events/sec:** 50K-200K score events/sec during peak (major tournament)
- **Storage:** ~10M active player scores per leaderboard, ~1KB per entry = 10GB hot data in Redis
- **Query volume:** 500K reads/sec for top-K (cached), 50K reads/sec for rank lookups
- **Growth rate:** ~100GB/month of raw score event history in cold storage

## Architecture Decisions

### Redis Sorted Sets as the Real-Time Serving Layer
Redis Sorted Sets provide O(log N) ZADD and O(log N + M) ZREVRANGE, making them ideal for ranked data. The critical insight is that ZRANK gives you any player's rank in O(log N) without scanning. The trade-off is memory — all scores must fit in a single Redis instance's memory for a given leaderboard (sharding sorted sets across nodes breaks global ranking). For leaderboards exceeding single-node memory, use Redis Cluster with a hash-tag strategy to pin each leaderboard to one shard.

### Kafka for Event Ingestion
Score events go through Kafka rather than directly to Redis because: (1) Kafka provides durability — if Redis goes down, events are not lost, (2) Kafka enables replay for reprocessing or backfilling new leaderboards, (3) Kafka decouples producers (game servers) from consumers (Flink, batch jobs). Partition by `game_id` to maintain ordering per game.

### Flink for Stream Processing Over Direct Kafka-to-Redis
A Flink job sits between Kafka and Redis to handle: deduplication (game servers may retry), validation (reject impossible scores), aggregation (combine multiple score components), and anti-cheat filtering. Without this layer, invalid data would pollute the leaderboard and require manual cleanup.

### WebSocket Push for Live Updates
Polling top-K every second from 100K clients would generate 100K QPS of redundant reads. Instead, use Redis Pub/Sub to notify a WebSocket server when rankings change, and push deltas to subscribed clients. This reduces read load by 10-100x for spectator-heavy scenarios.

## Pipeline Stages

1. **Event Sources:** Game servers and API gateways produce score events (player_id, game_id, score_delta, timestamp, event_id)
2. **Kafka Ingestion:** Events land in `score-events` topic, partitioned by game_id. Schema Registry enforces Avro schema for forward compatibility
3. **Stream Processing (Flink):** Consumes from Kafka, deduplicates by event_id (using a Flink keyed state with TTL), validates score ranges, applies anti-cheat heuristics, then emits validated score updates
4. **Real-Time Storage (Redis):** Flink writes to Redis Sorted Sets via ZADD. One sorted set per leaderboard scope (global, regional). Also publishes change notifications via Redis Pub/Sub
5. **Historical Storage (PostgreSQL):** Flink also writes to PostgreSQL for historical queries and audit trail
6. **Batch Rollup (Spark):** Nightly job reads from Kafka (or PostgreSQL) to compute daily/weekly/seasonal snapshots, stored in S3 as Parquet
7. **Serving Layer:** Leaderboard API reads from Redis (ZREVRANGE for top-K, ZRANK for position). WebSocket server subscribes to Redis Pub/Sub for live push

## Partitioning Strategy

- **Kafka:** Partition by `game_id` — ensures all events for a game are ordered and processed by the same Flink subtask
- **Redis:** One sorted set per `(game_id, scope)` pair. Use Redis Cluster hash tags `{game:123}:global` to co-locate all leaderboards for a game on the same shard
- **PostgreSQL:** Partition historical scores by date range for efficient archival and querying

Why `game_id` and not `player_id`: leaderboard operations are game-scoped (top-K within a game), so co-locating all players of a game enables efficient ranking. Player-based partitioning would scatter a game's scores across shards, making top-K impossible without scatter-gather.

## Failure Handling

- **Consumer (Flink) failure:** Flink checkpoints to S3 (or any DFS). On failure, it restarts from the last checkpoint and replays Kafka from the saved offset. ZADD is idempotent (same score for same member is a no-op), so replays are safe. Flink 2.0 (2025) introduced the ForSt disaggregated state backend that reads/writes RocksDB state directly from/to object storage, decoupling compute from state size — relevant if the leaderboard state grows large
- **Redis failure:** Redis Cluster (gossip-based) or Sentinel (for non-clustered mode) handles failover — not both. Worst case, rebuild the sorted set from PostgreSQL (which has the full history). This takes minutes, so a warm standby Redis replica is preferred. Note: since 2024, Valkey is a common drop-in for Redis after the license change
- **DLQ strategy:** Events that fail validation or cause processing errors go to a `score-events-dlq` topic. An alerting job monitors DLQ depth. Engineers can inspect, fix, and replay DLQ events back to the main topic
- **Replay capabilities:** Full replay from Kafka (retention 7 days) or from PostgreSQL (indefinite). Useful for recalculating leaderboards after discovering a cheating ring

## Key Trade-offs

- **Latency vs accuracy:** Redis gives instant top-K but might briefly show a stale rank during Flink processing lag. Batch rollups provide "official" snapshots that are fully consistent but delayed
- **At-least-once vs exactly-once:** We use at-least-once delivery with idempotent writes (ZADD is naturally idempotent for the same score). True exactly-once would require Kafka transactions + Flink's two-phase commit, adding latency and complexity for no practical benefit here
- **Memory vs computation:** Storing pre-computed sorted sets in Redis trades memory for read speed. An alternative (computing ranks on-the-fly from a database) saves memory but cannot hit sub-100ms latency at scale
- **Single sorted set vs sharded:** A single sorted set per leaderboard gives correct global ranking but limits to ~100M entries per Redis instance. Sharding breaks global ordering and requires merge logic

## What Fails First

**Redis memory pressure** is the first bottleneck. As leaderboards grow (more games, more players, more scopes), Redis memory consumption grows linearly. At ~100M entries per sorted set (~6GB), a single Redis instance starts struggling with ZADD latency due to memory fragmentation. Solutions: (1) TTL inactive players out of hot leaderboards, (2) tier leaderboards — only top 10K in Redis, rest in PostgreSQL with on-demand rank calculation, (3) compress player IDs using integer mappings instead of UUIDs.

Second bottleneck: **WebSocket connection limits**. A single server can hold ~50K WebSocket connections. For a million concurrent spectators, you need 20+ WebSocket servers behind a load balancer with sticky sessions, plus a fan-out layer from Redis Pub/Sub.

## v1 vs v2

### v1 — Ship in 2 weeks
- Single Kafka topic, single Flink job, single Redis instance
- Global leaderboard only (no regional/friends scopes)
- REST API polling (no WebSocket push)
- No batch rollup — historical leaderboard via PostgreSQL queries
- No anti-cheat filtering in the stream processor
- DLQ with manual inspection

### v2 — Scale and polish
- Multiple leaderboard scopes (regional, friends, seasonal)
- WebSocket push for live spectator updates
- Anti-cheat heuristic filtering in Flink (score velocity checks, statistical anomaly detection)
- Batch rollup pipeline for official daily/weekly snapshots
- Redis Cluster for horizontal scaling
- Tiered storage: hot (Redis) / warm (PostgreSQL) / cold (S3 Parquet)
- Schema evolution via Schema Registry with compatibility checks
- Grafana dashboards for pipeline health (lag, throughput, DLQ depth)
