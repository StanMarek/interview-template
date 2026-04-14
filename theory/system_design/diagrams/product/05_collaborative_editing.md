# Google Docs — Collaborative Editing Architecture Design

## Requirements

### Functional
- Create, edit, and delete rich-text documents
- Real-time collaborative editing (multiple cursors, live updates)
- Version history with ability to view/restore any previous state
- Comments and suggestions (threaded, resolvable)
- Sharing with granular permissions (view, comment, edit)
- Offline editing with sync on reconnect
- Export to PDF, DOCX, HTML
- Full-text search across user's documents

### Non-Functional
- Keystroke-to-screen latency for remote collaborators < 100ms (p90)
- Support 50+ simultaneous editors per document
- Zero data loss — every keystroke must be persisted
- Conflict resolution must be deterministic (all clients converge to same state)
- 99.99% availability
- Works across devices (web, mobile, tablet)

## Scale Estimates
- 100M DAU, 500M documents
- Average 2 active editors per document, peak 50
- Average document: 50KB, max 10MB
- Operations: ~10 ops/second per active user -> 1B ops/second globally at peak
- Concurrent editing sessions: 50M
- WebSocket connections: 100M

## Architecture Decisions

### Operational Transformation (OT) vs CRDT
This is THE core decision. **OT** requires a central server to sequence operations — each client sends operations to the server, which transforms them against concurrent operations and broadcasts the canonical result. **CRDTs** are decentralized — operations commute regardless of order, so no central sequencer is needed. Google Docs uses OT. We choose OT because:
1. OT produces smaller operation payloads (classic CRDTs carry causal metadata; modern CRDTs like Yjs and Automerge 2 have largely closed this gap with run-length encoding and document compaction)
2. OT is simpler to implement for rich text (bold, indent, tables) when you already have a central server
3. A central sequencer is acceptable since we already need a server for persistence
4. CRDTs shine for offline-first/P2P/local-first scenarios we don't prioritize in v1

The trade-off: OT requires server round-trips for every operation, making it sensitive to latency. CRDTs allow truly local-first editing without a sequencer.

**Modern context (2025-2026):** the industry has shifted notably toward CRDTs for new collaborative products — Linear, Figma's multiplayer, Notion's edit pipeline, and most VS Code Live Share-style tooling now use Yjs or a custom CRDT. If we were greenfielding this today and offline-first mattered, we would seriously consider Yjs with a y-websocket gateway and skip OT entirely. We stay with OT here for parity with the Google Docs reference implementation, but the choice is no longer obvious.

### Document-Sharded Collaboration Servers
Each active document is assigned to one Collaboration Engine instance (via consistent hashing on document_id). This instance holds the document's operation history in memory and sequences all operations. This is a single-writer pattern — it avoids distributed coordination for operation ordering. If the instance dies, another takes over and replays from the operation log. The constraint: all editors of a document connect to the same backend instance (via the WebSocket Gateway routing).

### Snapshot + Operation Log Persistence Model
We don't save the full document on every keystroke. Instead: (1) Every operation is appended to an operation log (fast, append-only); (2) Periodically (every 100 ops or 30 seconds), we compute a snapshot (the full document state) and save it to blob storage. To reconstruct any version: load nearest prior snapshot + replay subsequent operations. This bounds recovery time while keeping write throughput high. The operation log is our source of truth; snapshots are a performance optimization.

### Presence as an Ephemeral Channel
Cursor positions and selection ranges change on every keystroke but have no persistence value. We broadcast them via Redis Pub/Sub without writing to any durable store. If a presence update is lost, the next one (milliseconds later) corrects it. This keeps the critical path (operation persistence) clean of ephemeral noise.

## Component Breakdown

- **Web/Mobile Editor**: Rich text editor (ProseMirror/Slate.js). Applies operations optimistically to local state, sends to server, rebases on server response. Handles offline queuing.
- **WebSocket Gateway**: Routes clients to the correct Collaboration Engine instance for their document. Handles connection lifecycle, heartbeats, reconnection.
- **Collaboration Engine**: The brain. Receives operations from clients, applies OT, sequences them, broadcasts transformed operations back. One document per instance. Holds document state in memory for fast transformation.
- **Presence Service**: Broadcasts cursor positions, selection ranges, and active user list. Fire-and-forget via Redis Pub/Sub. No persistence.
- **OT/CRDT Engine**: The transformation logic. Takes two concurrent operations and produces transformed versions that maintain document consistency. Pluggable: can swap OT for CRDT.
- **Document Service**: CRUD for document metadata (title, owner, shared_with, created_at). Also handles document listing, search, and trash.
- **Auth & Permissions**: Fine-grained ACL: owner, editor, commenter, viewer. Link sharing with configurable access level. Checks permissions on every operation.
- **Comment Service**: Threaded comments anchored to document ranges. Suggestions (proposed edits that the owner can accept/reject).
- **Export Service**: Converts internal document representation to PDF/DOCX/HTML. Runs as an async worker (PDF generation can take seconds for large docs).
- **Operation Log**: Append-only log of all operations per document. Could be Kafka topic per document, or a database with append-only semantics.
- **Snapshot Store (S3)**: Periodic full document snapshots for fast loading and version history.

## Data Model

### Documents (PostgreSQL)
- PK: doc_id
- Columns: owner_id, title, created_at, updated_at, is_deleted
- Index: (owner_id, updated_at DESC)

### Permissions (PostgreSQL)
- PK: (doc_id, user_id)
- Columns: role (owner/editor/commenter/viewer), granted_at, granted_by

### Operations (append-only, Cassandra or Kafka)
- Partition key: doc_id
- Clustering key: sequence_number (monotonically increasing per doc)
- Columns: user_id, operation_type, operation_data (JSON), timestamp

### Snapshots (S3)
- Key: `snapshots/{doc_id}/{sequence_number}.json`
- Contains: full document state at that sequence number

### Comments (PostgreSQL)
- PK: comment_id
- Columns: doc_id, user_id, text, anchor_start, anchor_end, parent_id, resolved, created_at

## Key Trade-offs

- **OT (centralized) vs CRDT (decentralized)**: OT is simpler and produces better results for rich text but requires a single sequencer per document. If that sequencer is slow or down, editing stalls. CRDTs never stall but have larger payloads and more complex merge semantics.
- **Memory vs disk for active documents**: Keeping active documents in memory on the Collaboration Engine gives sub-millisecond transformation but limits the number of concurrent documents per server. At 50M concurrent sessions, need ~50K servers (assuming 1000 active docs per server).
- **Snapshot frequency**: More frequent snapshots = faster document load times but more storage and compute. Less frequent = cheaper but slower cold starts. 100-op intervals balance well.
- **Rich text representation**: A tree-based model (like ProseMirror) handles nested structures (tables, lists) better but is harder to do OT on than flat character sequences. This determines the complexity of the OT engine.

## What Fails First

**Collaboration Engine memory pressure.** Each active document holds its full state + recent operation history in memory. A 10MB document with 50 active editors generating 500 ops/second consumes significant RAM. When popular documents spike (a company-wide planning doc during all-hands), a single Collaboration Engine instance can run out of memory. Solution: implement document hibernation (evict inactive docs to disk), cap max document size, and implement operation batching to reduce per-op overhead.

## v1 vs v2

### v1 (MVP)
- Plain text editing only (no rich text)
- OT with a single collaboration server (no sharding)
- 5 concurrent editors max
- Basic version history (snapshot every 5 minutes)
- Share via link (view or edit)
- PostgreSQL for operations and document state
- No offline support

### v2
- Rich text with tables, images, headings
- Document-sharded Collaboration Engine
- 50+ concurrent editors
- Full operation log with granular version history
- Offline editing with operation queuing and sync
- Comments and suggestions
- Export to PDF/DOCX
- Full-text search across all user documents
- Presence indicators (cursors, selection)
- Undo/redo per user (not globally)
