# API Design and Pagination

## What It Is
The contract between your service and its callers: protocol style, URL and method conventions, how to page through large result sets, how to make operations safely retryable, and how you evolve all of the above without breaking clients. Good API design is mostly about **surviving the next five years of change**.

## Why It Matters
APIs are the only thing external callers can see. Every decision you make here (error shape, pagination strategy, versioning) is nearly impossible to unship once clients are in the wild. Interviewers probe this because it reveals whether you've had to live with your own API decisions.

## REST vs RPC vs GraphQL At A Glance

| Style     | Shape                             | Strengths                                              | Weaknesses                                                |
|-----------|-----------------------------------|--------------------------------------------------------|-----------------------------------------------------------|
| REST      | Resource-oriented, HTTP verbs     | Cacheable, proxy-friendly, discoverable, universal     | Verbose for complex operations, n+1 fetches               |
| RPC (gRPC, Thrift) | Method calls over HTTP/2 | Strongly typed (protobuf), streaming, fast binary      | Harder to debug without tooling, less browser-friendly    |
| GraphQL   | Single endpoint, client picks fields | Exactly the data the client needs, one round trip    | Hard to cache at HTTP layer, query cost/complexity, auth per-field |

### When To Pick Which
- **REST** — public APIs, CRUD-heavy services, when HTTP caching matters, when clients are heterogeneous.
- **gRPC** — internal service-to-service, streaming (bidi or server-push), low latency and strong schema needed.
- **GraphQL** — rich client UIs with diverse data needs (mobile + web + partner dashboards) that share one backend; aggregator / BFF layer.

You can mix: gRPC internally, REST externally, GraphQL for BFF. This is common.

### Richardson Maturity Model
Richardson Maturity Model (Levels 0-3): 0=tunneling, 1=resources, 2=verbs+status codes, 3=hypermedia/HATEOAS. Most "REST" APIs are Level 2. HATEOAS is rare in practice.

## REST Resource Design Quick Reference

- Nouns for resources, verbs from HTTP: `GET /orders/123`, not `GET /getOrder?id=123`.
- Plural collections: `/orders`, `/orders/123/items`.
- Filtering/sorting/paging as query params: `GET /orders?status=paid&sort=-created_at&limit=50`.
- Sub-resources for contained entities: `POST /orders/123/refunds`.
- Actions that don't fit CRUD as explicit verbs: `POST /orders/123:cancel` (Google style) or `POST /orders/123/cancel`.
- Statelessness — no server-side session state required for a given request (use tokens, cursors, etags).

## Pagination

### Offset / Page Pagination
```
GET /orders?limit=50&offset=1000
GET /orders?page=21&per_page=50
```
- Under the hood: `SELECT ... LIMIT 50 OFFSET 1000`.
- DB scans and discards the first 1000 rows on every call — linear in offset.
- Results **shift** if rows are inserted/deleted between pages (user sees duplicates or skipped rows).
- Fine for small datasets, admin UIs, "jump to page N" requirements.

### Cursor / Keyset Pagination
```
GET /orders?limit=50&after=eyJpZCI6MTIzLCJ0cyI6MTcwMDAwMDAwMH0
# cursor is an opaque base64 of (created_at, id) of the last item
```
- SQL: `WHERE (created_at, id) < (:ts, :id) ORDER BY created_at DESC, id DESC LIMIT 50`.
- Constant time regardless of depth — uses the index directly.
- Stable under inserts/deletes — each page is "everything strictly after this cursor".
- Cannot jump to page N. Can't show "page 127 of 340". Forward (and optionally backward) only.

### Why Cursor Wins At Scale
- Latency stays flat at page 1 and page 10,000 — the DB never scans what it's going to throw away.
- Results don't shift under concurrent writes — a paginated crawl over live data actually terminates with no duplicates.
- The cursor is opaque — you can change the underlying sort/column later without a breaking API change.
- Deep `OFFSET` on large tables is one of the classic production-meltdown queries.

### Choosing Cursor Keys
- Must be a **total order** (no ties). Pair your sort column with a tiebreaker — typically `(created_at, id)` or `(score, id)`.
- Include everything the query needs to resume. If the sort is `ORDER BY score DESC, id DESC`, the cursor must encode both.
- Sign or encrypt cursors if they encode anything sensitive (or just stick to opaque IDs).

### Hybrid Strategies
- **Search results** — pre-compute a result set with a snapshot ID; cursor paginates within the snapshot.
- **Time-windowed feeds** — cursor on `created_at` plus a `since` / `until` bound.
- **Elasticsearch** — `search_after` is cursor pagination; avoid deep `from + size` for the same reasons.

## Idempotency

Every write endpoint on a public API should accept an `Idempotency-Key` header. Retries are a fact of life — mobile networks, load balancers, client libraries, humans clicking twice.

```
POST /payments
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

Deep dive in [idempotency.md](idempotency.md). The API-design points:
- Idempotency key is **client-supplied** (UUID v4 is fine). Server-minted defeats the purpose.
- Document the TTL (Stripe: 24 h). After TTL the key may be reused.
- Document the scope — usually per-account, per-endpoint. Mixing endpoints with the same key is ambiguous.
- Return the **same response** (status + body) for a repeated key. Different request body with the same key should return `409` or `422`.

## Versioning Strategies

| Strategy              | Example                       | Pros                               | Cons                                                |
|-----------------------|-------------------------------|------------------------------------|-----------------------------------------------------|
| URI version           | `/v1/orders`, `/v2/orders`    | Visible, easy to route             | "v2" becomes a forever-maintained second code path  |
| Header version        | `Accept: application/vnd.api+json; version=2` | Clean URLs           | Invisible in logs, harder to cache                  |
| Date-based            | `Stripe-Version: 2024-10-01`  | Fine-grained, every breaking change gets a date | Many active versions to support           |
| Field-level evolution | Add fields, never remove      | No version at all if you're disciplined | Hard to remove anything; bloated responses      |

### Rules That Work
- **Additive changes are safe.** New optional fields, new endpoints, new enum values (only if clients handle unknown values gracefully — document this).
- **Breaking changes need a new version.** Renames, removed fields, changed types, stricter validation.
- **Deprecate, don't delete.** Announce, emit `Deprecation` and `Sunset` headers, keep the old contract working for a published window.
- **Don't reuse names with new meanings.** Renaming `status` from `paid` → `succeeded` is a breaking change even if the field is still called `status`.

## Error Contracts

Consistent, machine-parseable errors. HTTP status alone is not enough.

```json
{
  "error": {
    "type": "validation_error",
    "code": "inventory_insufficient",
    "message": "Only 2 units of SKU ABC-123 are available.",
    "param": "quantity",
    "request_id": "req_01HX...",
    "doc_url": "https://docs.example.com/errors/inventory_insufficient"
  }
}
```

### Guidelines
- Use HTTP status ranges correctly:
  - `4xx` — client's fault, don't retry as-is.
  - `5xx` — indicates server-side failure, but only idempotent operations (GET/PUT/DELETE, or POSTs with idempotency keys) are safe to retry. Blindly retrying POST without idempotency can double-apply. Retry specifically on 502/503/504; handle 500 case-by-case. Respect `Retry-After`.
  - `429` — rate limited, include `Retry-After` and `RateLimit-*` headers.
  - `409` — conflict (idempotency key mismatch, version conflict).
  - `412` — precondition failed (ETag / `If-Match` mismatch).
  - `422` — semantic validation error (syntactically fine, business-invalid).
- Always include a **stable machine code** (`inventory_insufficient`), separate from the human message (which you may localize or rephrase).
- Include a **request ID** clients can give support. Log it server-side.
- Never leak stack traces or internal errors.
- Standardizing on [RFC 7807 / 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) is a defensible choice.

## Retries and Safety

- Only auto-retry on `5xx`, `408`, `429`, and connection errors.
- **Exponential backoff + full jitter**, capped: `delay = random(0, base * 2^attempt)`, max ~30 s. See [rate-limiting.md](rate-limiting.md).
- Respect `Retry-After` when present.
- Every retried request should carry the same `Idempotency-Key`.
- Budget retries (max ~3-5) to avoid amplifying outages.

## Rate Limiting Headers

Make your limits legible so clients don't have to probe them.
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1700000000    # unix seconds or seconds-until
Retry-After: 30
```
Or the draft IETF spec: `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`.

## Other Design Decisions Worth Thinking About

- **Authentication** — OAuth 2.1 / OIDC for user auth, signed service tokens (mTLS, SPIFFE) for service-to-service. Don't invent auth.
- **Content negotiation** — JSON as the default. Offer a binary format (Protobuf, MessagePack) only if you measured a need.
- **Field naming** — pick `snake_case` **or** `camelCase` and enforce it with linting. Mixing is a red flag.
- **Time** — always ISO 8601 with timezone (`2025-01-14T08:30:00Z`). Never "seconds since epoch" as the on-wire default.
- **Money** — integer minor units + ISO 4217 currency (`"amount": 1299, "currency": "USD"`). Never float.
- **Nullable vs absent** — decide and document: `null` means "explicitly cleared", absent means "unchanged" (common in PATCH).
- **PATCH semantics** — JSON Merge Patch ([RFC 7396](https://www.rfc-editor.org/rfc/rfc7396)) is simple; JSON Patch ([RFC 6902](https://www.rfc-editor.org/rfc/rfc6902)) is expressive but heavier.

## Common Pitfalls and Red Flags
- Offset pagination over a multi-million-row table — perf cliff.
- `POST` that mutates and is not idempotent, with no idempotency key accepted.
- Every error is a `200 { "success": false }`. Now proxies, caches, and monitoring can't tell success from failure.
- Versioning via query param (`?v=2`) — hard to cache, hard to enforce, easy to forget.
- Renaming fields in a "minor" release.
- Returning different shapes from the same endpoint depending on a flag (inflates client branching).
- Leaking internal IDs (DB autoincrement), allowing enumeration of other users' data.
- Letting clients send unlimited `limit` — DoS risk. Cap server-side.

## Possible Interview Questions
1. Design a paginated API for a feed with 100M items. Offset or cursor? Why?
2. How do you version a public REST API? How do you handle breaking changes?
3. The mobile client times out on a payment call and retries. How does your API prevent double charges?
4. Design the error contract. What goes in it and what doesn't?
5. REST vs gRPC vs GraphQL for this system — pick one and justify.
6. A client complains that page 200 takes 5 seconds. What's happening, and how do you fix it without breaking them?
7. How do you let clients discover their rate limit without getting throttled?
8. Why can't offset pagination return a stable result set under concurrent writes?
9. Walk me through adding a required new field to an existing API.
10. Design cursors for a feed sorted by `(score desc, id desc)`. What goes in the cursor?

## Related
- [idempotency.md](idempotency.md) — the API-level side of safe retries.
- [rate-limiting.md](rate-limiting.md) — throttling, backoff, headers.
- [api-gateway.md](api-gateway.md) — where cross-cutting API concerns live.
- [caching.md](caching.md) — HTTP caching interactions with API design.
