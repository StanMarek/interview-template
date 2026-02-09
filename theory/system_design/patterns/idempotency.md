# Idempotency

## What It Is
An operation is idempotent if performing it multiple times has the same effect as performing it once. In distributed systems, network failures and retries mean the same request can be delivered multiple times. Idempotency ensures this doesn't cause duplicate side effects.

## Why It Matters
Networks are unreliable. A client sends a payment request, the server processes it, but the ACK is lost. The client retries. Without idempotency, the user is charged twice.

## Naturally Idempotent Operations
- `SET balance = 100` — same result no matter how many times executed
- `DELETE FROM orders WHERE id = 123` — deleting an already-deleted row is a no-op
- `PUT /users/123 { name: "Alice" }` — replaces the resource, same result each time
- Reading data (GET requests)

## NOT Naturally Idempotent
- `balance += 10` — each execution adds 10 more
- `INSERT INTO orders ...` — each execution creates a new row
- `POST /payments` — each execution charges again
- Sending an email or notification

## Making Operations Idempotent

### Idempotency Key
The client generates a unique key (UUID) for each logical operation and sends it with the request. The server tracks which keys have been processed.

```
POST /payments
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
{ amount: 100, currency: "USD" }
```

Server logic:
1. Check if `idempotency_key` exists in the deduplication table
2. If yes → return the stored response (don't re-execute)
3. If no → process the request, store `(key, response)` in the deduplication table, return response

### Database Unique Constraints
Use a unique constraint to prevent duplicate inserts:
```sql
INSERT INTO payments (idempotency_key, amount, user_id)
VALUES ('550e...', 100, 42)
ON CONFLICT (idempotency_key) DO NOTHING;
```

### Conditional Writes (Optimistic Concurrency)
Only apply the write if the current state matches expectations:
```sql
UPDATE accounts SET balance = balance - 100, version = 5
WHERE id = 42 AND version = 4;
-- Only succeeds if version is still 4. Retries with stale version are no-ops.
```

## Deduplication Table Design

```sql
CREATE TABLE idempotency_keys (
    key         VARCHAR(255) PRIMARY KEY,
    response    JSONB,
    created_at  TIMESTAMP DEFAULT NOW(),
    expires_at  TIMESTAMP  -- Clean up old keys
);
```

**Key considerations**:
- Set a TTL (e.g., 24-72 hours) and clean up expired keys
- The entire operation (check key + execute + store result) should be atomic (within a transaction)
- Store the response so retries get the same result without re-executing

## Idempotency in Message Queues
At-least-once delivery means consumers may process the same message multiple times. Solutions:
- **Message ID deduplication**: Track processed message IDs
- **Idempotent consumer pattern**: Design the consumer's operation to be idempotent
- **Exactly-once processing**: Supported by some frameworks (Kafka transactions + consumer offsets in the same transaction)

## HTTP Method Idempotency
| Method | Idempotent | Safe |
|--------|-----------|------|
| GET | Yes | Yes |
| PUT | Yes | No |
| DELETE | Yes | No |
| HEAD | Yes | Yes |
| POST | **No** | No |
| PATCH | **No** | No |

POST and PATCH need explicit idempotency mechanisms (idempotency keys).

## Possible Interview Questions
1. "How do you prevent a user from being charged twice if the payment request is retried?"
2. "Design an idempotent payment API."
3. "How do you handle idempotency in an event-driven system?"
4. "What is an idempotency key and how would you implement it?"
5. "How long should you keep idempotency keys before expiring them?"
