# CQRS & Event Sourcing

## CQRS (Command Query Responsibility Segregation)

### What It Is
Separate the read model (queries) from the write model (commands). Instead of one model that handles both reads and writes, you have two optimized models.

### Why It Matters
Read and write patterns are often fundamentally different. Reads need denormalized, pre-computed views. Writes need normalized, consistent storage. Optimizing for both in one model leads to compromises.

### Architecture

```
                    ┌──────────────┐
Write (Command) ──→ │ Write Model  │ ──→ Event/Change ──→ ┌──────────────┐
                    │ (Normalized) │                       │  Read Model  │ ←── Read (Query)
                    └──────────────┘                       │(Denormalized)│
                                                          └──────────────┘
```

- **Command side**: Validates and processes writes. Optimized for consistency and business rules.
- **Query side**: Serves reads from pre-computed, denormalized views. Optimized for read performance.
- **Sync mechanism**: The write model publishes events/changes that update the read model. This introduces eventual consistency.

### When to Use CQRS
- Read and write workloads have very different scaling needs (e.g., 100:1 read:write ratio)
- Complex domain logic on writes but simple flat reads
- Need different data models for different read use cases
- Need to scale reads and writes independently

### When NOT to Use
- Simple CRUD applications
- Read and write patterns are similar
- Team is small and added complexity isn't justified
- Strong consistency is required on reads immediately after writes

## Event Sourcing

### What It Is
Instead of storing current state, store a sequence of **events** (immutable facts) that represent every state change. The current state is derived by replaying events.

### Example
Traditional: `Account { balance: 150 }`

Event-sourced:
```
Event 1: AccountCreated { initial_balance: 0 }
Event 2: MoneyDeposited { amount: 200 }
Event 3: MoneyWithdrawn { amount: 50 }
→ Derived state: balance = 0 + 200 - 50 = 150
```

### Why Event Sourcing
- **Complete audit trail**: Every change is recorded. Can answer "what was the state at time T?"
- **Temporal queries**: Reconstruct state at any point in time
- **Event replay**: Rebuild read models, fix bugs by replaying with corrected logic
- **Debugging**: Understand exactly what happened and in what order
- **Decoupling**: New consumers can subscribe to the event stream and build their own views

### Aggregate
**Aggregate** — the consistency boundary. Each aggregate instance is a single-writer unit; events within an aggregate are totally ordered. Cross-aggregate consistency is eventual.

### Projections
Read-side **projections** (aka materialized views, read models) subscribe to the event stream and build query-optimized shapes. Rebuild by replaying from offset 0.

### Event Versioning
Strategies: (1) weak schema — ignore unknown fields; (2) upcasting — lazy transformation on read; (3) copy-transform — rewrite events in new topic on migration.

### Challenges
- **Event schema evolution**: Events are immutable, but their schema needs to change. Use upcasting (transform old events to new schema on read).
- **Replay performance**: Replaying millions of events is slow. Use **snapshots** — periodically save the derived state so replay starts from the latest snapshot.
- **Eventual consistency**: Read models are updated asynchronously. Users may see stale data.
- **Complexity**: Harder to understand, debug, and implement than CRUD.

## CQRS + Event Sourcing Together

```
Command → Validate → Store Event → Event Store (append-only log)
                                        │
                                  Event Published
                                        │
                              ┌─────────┼─────────┐
                              ▼         ▼         ▼
                         Read Model  Analytics  Notifications
                         (Materialized View)
```

The event store IS the write model. Read models are materialized views built from events. This is the most powerful combination but also the most complex.

## Snapshots
For long event streams, rebuilding state by replaying all events is slow. Snapshots periodically save the current derived state. On read: load snapshot + replay only events after the snapshot.

## Possible Interview Questions
1. "Explain CQRS. When would you use it vs a standard CRUD approach?"
2. "How does event sourcing differ from traditional database storage?"
3. "How do you handle schema changes in an event-sourced system?"
4. "Your event store has 100 million events. How do you make reads fast?"
5. "A user writes a comment but can't see it immediately. How do you solve this with CQRS?"
6. "Design a banking system using event sourcing."
