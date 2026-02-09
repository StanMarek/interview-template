# Saga Pattern (Distributed Transactions)

## What It Is
A saga is a sequence of local transactions where each step has a **compensating transaction** (undo action) in case a later step fails. It replaces traditional distributed transactions (2PC) with eventual consistency.

## Why It Matters
In microservices, a business operation often spans multiple services, each with its own database. ACID transactions don't work across service boundaries. Sagas provide a way to maintain data consistency without distributed locks.

## Example: E-Commerce Order

### Happy Path
1. **Order Service**: Create order (status: PENDING)
2. **Payment Service**: Charge credit card
3. **Inventory Service**: Reserve items
4. **Shipping Service**: Schedule delivery
5. **Order Service**: Update order (status: CONFIRMED)

### Failure at Step 3 (Inventory unavailable)
1. ~~Order Service: Create order~~ ✓
2. ~~Payment Service: Charge credit card~~ ✓
3. **Inventory Service: Reserve items** ✗ FAILED
4. **Compensate Step 2**: Payment Service → Refund credit card
5. **Compensate Step 1**: Order Service → Cancel order (status: CANCELLED)

## Saga Coordination

### Choreography (Event-Driven)
Each service listens for events and decides locally what to do next. No central coordinator.

```
Order Created → [Payment Service listens] → Payment Charged → [Inventory Service listens] → Items Reserved → ...
                                           → Payment Failed → [Order Service listens] → Order Cancelled
```

- **Pros**: Loosely coupled, no SPOF, simple for small sagas (2-4 steps)
- **Cons**: Hard to track overall progress, cyclic dependencies possible, difficult to debug with many services

### Orchestration (Central Coordinator)
A saga orchestrator tells each service what to do and handles failures centrally.

```
[Saga Orchestrator]
  → Tell Payment: Charge card
  ← Payment: Success
  → Tell Inventory: Reserve items
  ← Inventory: Failed
  → Tell Payment: Refund (compensate)
  → Mark saga: FAILED
```

- **Pros**: Easy to understand, centralized logic, clear flow
- **Cons**: Orchestrator is a SPOF (must be reliable), risk of too much logic in orchestrator

## Design Considerations

### Idempotency
Every step AND every compensating action must be idempotent. Network retries can deliver the same message multiple times.

### Isolation
Sagas don't have isolation (unlike ACID). Intermediate states are visible to other transactions. Mitigation strategies:
- **Semantic locks**: Mark records as "processing" during the saga
- **Commutative updates**: Design operations so order doesn't matter
- **Pessimistic view**: Reread data before compensating
- **Version checks**: Use optimistic concurrency control

### Compensating Transactions
Not all operations are easily reversible:
- Sending an email → Can't unsend (but can send a follow-up correction)
- Charging a credit card → Issue a refund
- Shipping a package → Initiate a return

Design tip: Delay irreversible operations until the end of the saga when possible.

## Saga vs 2PC (Two-Phase Commit)

| Feature | Saga | 2PC |
|---------|------|-----|
| Consistency | Eventual | Strong |
| Availability | High | Low (blocking) |
| Latency | Lower per step | Higher (waiting for all participants) |
| Complexity | Compensating logic | Coordinator protocol |
| Failure handling | Compensating transactions | Abort and rollback |
| Scale | Works across services/networks | Same DB or tight coupling |

## Possible Interview Questions
1. "How do you handle transactions across multiple microservices?"
2. "Compare choreography vs orchestration for sagas."
3. "What happens if a compensating transaction fails?"
4. "How do you handle the 'isolation' problem in sagas?"
5. "Design an order processing system using the saga pattern."
