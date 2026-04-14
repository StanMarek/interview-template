# Chat / Messaging System — Architecture Design

## Requirements

### Functional
- 1:1 messaging with real-time delivery
- Group chats (up to 500 members)
- Online/offline presence indicators
- Read receipts and typing indicators
- Media sharing (images, files, voice messages)
- Message search and chat history
- Push notifications for offline users

### Non-Functional
- Message delivery latency < 100ms for online users
- 99.99% availability (messaging is mission-critical)
- Eventual consistency acceptable for read receipts; strong consistency for message ordering within a conversation
- Messages must never be lost (at-least-once delivery)
- End-to-end encryption for 1:1 chats

## Scale Estimates
- 500M DAU, 50B messages/day
- Average user sends 40 messages/day, receives 60
- Peak QPS: ~1M messages/sec
- Average message size: 200 bytes text, 200KB media
- Storage: ~10TB/day text, ~2PB/day media
- WebSocket connections: 500M concurrent

## Architecture Decisions

### Real-Time Transport Choice: WebSocket vs SSE vs Long-Polling
The transport matrix:
- **Long-polling**: simplest, works through every proxy, but reconnect overhead per message — only viable for v0/MVP.
- **Server-Sent Events (SSE)**: server-to-client only over HTTP/2 or HTTP/3, auto-reconnect built in, plays nicely with HTTP infrastructure (CDN, LB, WAF). Great for one-way pushes (notifications, presence broadcast) but the client must use a separate POST for sends, doubling connection state.
- **WebSocket**: bidirectional, low per-message overhead, full duplex. Standard choice for chat. Tradeoff: requires sticky routing and dedicated gateway tier.
- **WebTransport / HTTP/3 streams**: emerging option (datagrams + reliable streams over QUIC), better mobile reconnect characteristics; worth evaluating once browser support stabilizes.

We pick WebSockets for the core chat path. The critical design choice is **sticky sessions** — a user's WebSocket must connect to a specific gateway server, and we need a mapping of userId -> gatewayServer stored in Redis so the Chat Service knows where to route a message. When a gateway server dies, clients reconnect to a new one and re-register.

### Cassandra for Message Storage, Not MySQL
Messages are write-heavy (50B/day) and read patterns are sequential (load recent messages in a conversation). Cassandra's partition key = conversationId, clustering key = messageTimestamp gives us exactly the access pattern we need with linear scalability. MySQL would require sharding and still struggle with the write throughput. The trade-off: no cross-partition queries, so search requires a separate index (Elasticsearch).

### Redis Pub/Sub for Fan-Out in Group Chats
When User A sends to a 100-person group, the Chat Service publishes to a Redis channel for that group. Every WebSocket Gateway server with connected group members subscribes to that channel. This avoids the Chat Service needing to know which gateway each member is on. For very large groups (1000+), we switch to a pull model — members fetch new messages on-open instead of getting pushed every message.

### Kafka for Durability Before Persistence
Messages hit Kafka before being written to Cassandra. This decouples the real-time path (WebSocket delivery) from the persistence path. If Cassandra is slow, messages are still delivered in real-time and persisted eventually. Kafka also feeds the analytics pipeline and the search indexer.

### End-to-End Encryption (Signal Protocol)
For 1:1 and small-group chats we layer the **Signal Protocol** (X3DH for initial key agreement + Double Ratchet for forward secrecy and post-compromise security) on top of the transport. The server stores ciphertext, prekey bundles, and routing metadata only — it cannot read message content. Implications and tradeoffs:
- **Server-side search is dead.** With E2EE the indexer cannot read content; full-text search must run client-side over locally cached messages, or we fall back to encrypted searchable indexes (significant complexity, weak privacy guarantees).
- **Group chats use Sender Keys** (sender encrypts once with a shared symmetric key distributed via pairwise Signal sessions). For very large groups, evaluate **MLS (RFC 9420)** — better scaling for 1000+ members and continuous group key agreement.
- **Multi-device:** each device has its own identity key; the prekey bundle is per-device, so messages must be encrypted N times for N devices of the recipient (Sesame protocol manages device list churn).
- **History on new device:** because old messages are encrypted to the old device's key, a fresh device cannot retroactively read history without an out-of-band key transfer or device-to-device sync.

Server-side features that depend on plaintext (server-side search, content moderation, server-side translation) are unavailable in E2EE chats — this is a deliberate product tradeoff, not a bug.

## Component Breakdown

- **Web/Mobile/API Clients**: Handle local message queue, optimistic UI, reconnection with exponential backoff
- **Load Balancer**: L4 (TCP) for WebSocket traffic — L7 would break the persistent connection
- **API Gateway**: Handles REST calls for history, search, user management. Rate limiting per user.
- **WebSocket Gateway**: Manages persistent connections. Each server holds ~100K connections. Heartbeat every 30s. Registers connections in Redis.
- **Auth Service**: JWT token validation, session management
- **Chat Service**: Core message routing. Looks up recipient gateway, publishes to Redis Pub/Sub for group fan-out
- **Presence Service**: Heartbeat-based online detection. Uses Redis with TTL keys (userId -> lastSeen). Publishes presence changes to subscribers.
- **Group Service**: Group CRUD, membership management, permission checks
- **Notification Service**: Checks if recipient is online (via Presence). If offline, queues push notification with de-duplication (don't send 50 notifications for 50 group messages in 1 minute — batch them).
- **Redis Sessions**: userId -> gatewayServerId mapping
- **Redis Pub/Sub**: Group message fan-out, presence change broadcasting
- **Kafka**: Durable message log, feeds persistence workers and analytics
- **Cassandra**: Message storage partitioned by conversationId
- **PostgreSQL**: User profiles, contacts, group metadata (relational data with low write volume)
- **S3 + CDN**: Media storage with signed URLs for access control
- **Elasticsearch**: Full-text message search

## Data Model

### Messages (Cassandra)
- Partition key: `conversation_id`
- Clustering key: `message_id` (TimeUUID for ordering)
- Columns: sender_id, content, content_type, created_at, status

### Conversations (Cassandra)
- Partition key: `user_id`
- Clustering key: `last_message_at DESC`
- Columns: conversation_id, last_message_preview, unread_count

### Users (PostgreSQL)
- PK: user_id
- Indexes: phone_number (unique), username (unique)

### Group Members (PostgreSQL)
- Composite PK: (group_id, user_id)
- Columns: role, joined_at

## Key Trade-offs

- **Availability over consistency**: A message might be delivered twice (at-least-once) rather than not at all. Clients de-duplicate using message_id.
- **Cassandra over relational DB**: We gain write throughput and horizontal scaling but lose ad-hoc queries and transactions. Search requires a separate system.
- **Fan-out on write for small groups, fan-out on read for large groups**: Small groups (< 500) push to all members immediately. Large channels use a pull model to avoid thundering herd.
- **Redis Pub/Sub over direct routing**: Simpler architecture but Redis Pub/Sub has no persistence — if a gateway misses a message, it's gone. We compensate with Kafka-backed catch-up.

## What Fails First

**WebSocket Gateway connection limits.** Each gateway server can hold ~100K concurrent WebSocket connections (limited by file descriptors and memory for per-connection state). At 500M concurrent users, you need 5,000 gateway servers. The connection registry in Redis becomes the next bottleneck — millions of writes per second as users connect/disconnect. Solution: shard Redis by userId hash, and batch registry updates.

## v1 vs v2

### v1 (MVP)
- 1:1 messaging only via WebSocket
- Text messages only (no media)
- Simple online/offline presence (no "last seen at")
- PostgreSQL for messages (good enough at low scale)
- No search
- No read receipts

### v2
- Group chats with fan-out via Redis Pub/Sub
- Media sharing via S3 with CDN
- Migrate messages to Cassandra
- Add Elasticsearch for search
- Read receipts and typing indicators
- Push notifications for offline users
- End-to-end encryption (Signal Protocol)
- Message reactions and threading
