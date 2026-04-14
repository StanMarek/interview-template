# Design Patterns & Best Practices — Senior Engineer Interview Preparation

---

## 1. SOLID Principles in TypeScript

### S — Single Responsibility Principle

A module should have only one reason to change.

```typescript
// BAD — handles persistence and notification
class OrderService {
  async createOrder(dto: CreateOrderDto): Promise<Order> {
    const order = await db.query("INSERT INTO orders ...", [dto.customerId]);
    await sendgrid.send({ to: dto.email, subject: "Order confirmed" });
    return order;
  }
}

// GOOD — separated concerns, notification handled by event listener
class OrderService {
  constructor(private readonly repo: OrderRepository, private readonly eventBus: EventBus) {}
  async createOrder(dto: CreateOrderDto): Promise<Order> {
    const order = await this.repo.save({ customerId: dto.customerId, total: dto.total });
    this.eventBus.emit("order.created", { orderId: order.id, email: dto.email });
    return order;
  }
}
```

### O — Open/Closed Principle

Open for extension, closed for modification.

```typescript
// GOOD — extend by adding new strategy implementations
interface DiscountStrategy { readonly type: string; apply(total: number): number; }

class DiscountProcessor {
  private strategies: Map<string, DiscountStrategy>;
  constructor(strategies: DiscountStrategy[]) {
    this.strategies = new Map(strategies.map((s) => [s.type, s]));
  }
  apply(total: number, type: string): number {
    const s = this.strategies.get(type);
    if (!s) throw new Error(`Unknown discount: ${type}`);
    return s.apply(total);
  }
}
```

### L — Liskov Substitution — Structural Typing Implications

```typescript
// VIOLATION — ReadonlyRepo throws on save, breaking the contract
interface Repository<T> { findById(id: string): Promise<T | null>; save(entity: T): Promise<T>; }
class ReadonlyRepo<T> implements Repository<T> {
  async findById(id: string) { /* ... */ }
  async save(entity: T) { throw new Error("Read-only!"); } // LSP violation!
}

// FIX — segregate interfaces so subtypes fulfill their entire contract
interface ReadableRepo<T> { findById(id: string): Promise<T | null>; }
interface WritableRepo<T> extends ReadableRepo<T> { save(entity: T): Promise<T>; }
```

**Structural typing pitfall**: Any object matching the shape satisfies the type. This can hide behavioral LSP violations that only surface at runtime.

### I — Interface Segregation

```typescript
// BAD — fat interface
interface UserService { find(id: string): Promise<User>; create(dto: any): Promise<User>; sendEmail(id: string): Promise<void>; export(): Promise<Buffer>; }

// GOOD — small, focused interfaces composed via intersection types
interface UserReader { find(id: string): Promise<User>; }
interface UserWriter { create(dto: CreateUserDto): Promise<User>; }
type UserAdminService = UserReader & UserWriter;
```

### D — Dependency Inversion — Injection Patterns

```typescript
// Depend on abstraction, inject implementation — no framework needed
interface OrderRepository { findById(id: string): Promise<Order | null>; }
class OrderService { constructor(private readonly repo: OrderRepository) {} }

// Composition root wires everything at startup
function bootstrap(): OrderService {
  return new OrderService(new PrismaOrderRepository(new PrismaClient()));
}
```

---

## 2. Creational Patterns

### Factory Pattern

```typescript
// Simple factory — caller doesn't know the concrete type
function createLogger(ctx: string): Logger {
  return process.env.NODE_ENV === "production" ? new JsonLogger(ctx) : new ConsoleLogger(ctx);
}

// Abstract factory — families of related objects that must be consistent
interface InfraFactory { createDb(): DbConn; createCache(): CacheConn; createQueue(): QueueConn; }
class ProdInfra implements InfraFactory { createDb() { return new PgConn(); } /* ... */ }
class TestInfra implements InfraFactory { createDb() { return new SQLiteConn(":memory:"); } /* ... */ }
```

### Builder Pattern (Fluent API with Type Safety)

```typescript
// Step builder — compiler enforces required fields in order
interface NeedsTable { from(t: string): NeedsSelect; }
interface NeedsSelect { select(...cols: string[]): OptionalClauses; }
interface OptionalClauses {
  where(c: Record<string, unknown>): OptionalClauses;
  orderBy(col: string, dir?: "ASC"|"DESC"): OptionalClauses;
  limit(n: number): OptionalClauses;
  build(): QueryConfig;
}

// query().build() → ERROR: 'build' does not exist on 'NeedsTable'
// query().from("x").build() → ERROR: 'build' does not exist on 'NeedsSelect'
const q = query().from("orders").select("id","total").where({status:"pending"}).build(); // OK
```

### Singleton (Module-Level in Node.js)

```typescript
// db.ts — Node's module cache makes this a singleton automatically
import { Pool } from "pg";
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 20 });
export const query = <T>(sql: string, params?: unknown[]) => pool.query(sql, params).then(r => r.rows as T[]);
export const shutdown = () => pool.end();
```

Prefer DI over singletons. Use module-level singletons only for infrastructure (pools, loggers) and expose reset for tests.

### Prototype Pattern

`structuredClone(obj)` (Node 17+) for deep cloning. `Object.create(defaults)` for prototype delegation where reads fall through to the prototype and writes only affect the new object.

---

## 3. Structural Patterns

### Adapter

```typescript
// Translate third-party SDK to your domain interface
interface PaymentGateway { charge(amount: number, currency: string, token: string): Promise<PaymentResult>; }

class StripeAdapter implements PaymentGateway {
  constructor(private stripe: StripeSDK) {}
  async charge(amount: number, currency: string, token: string) {
    const intent = await this.stripe.paymentIntents.create({
      amount: Math.round(amount * 100), currency, payment_method: token, confirm: true,
    });
    return { transactionId: intent.id, status: intent.status === "succeeded" ? "success" : "failed" as const };
  }
}
```

### Decorator (Composition-Based)

```typescript
interface HttpClient { request<T>(config: RequestConfig): Promise<T>; }

// Each decorator wraps the inner client, adding one concern
class RetryClient implements HttpClient {
  constructor(private inner: HttpClient, private maxRetries = 3) {}
  async request<T>(cfg: RequestConfig): Promise<T> {
    for (let i = 0; i <= this.maxRetries; i++) {
      try { return await this.inner.request<T>(cfg); }
      catch (e) { if (i === this.maxRetries) throw e; await new Promise(r => setTimeout(r, 2**i*100)); }
    }
    throw new Error("unreachable");
  }
}

// Stack: logging wraps retry wraps the real client — order matters
const client = new LoggingClient(new RetryClient(new FetchClient(), 3), logger);
```

### Proxy (ES6 Proxy)

```typescript
// Lazy initialization — instantiate only on first access
function lazyProxy<T extends object>(factory: () => T): T {
  let inst: T | null = null;
  return new Proxy({} as T, {
    get(_, prop, recv) {
      if (!inst) inst = factory();
      const val = Reflect.get(inst, prop, recv);
      return typeof val === "function" ? val.bind(inst) : val;
    },
  });
}
```

### Facade

Hides coordination of multiple subsystems: `OrderFacade.placeOrder()` orchestrates inventory reservation, payment authorization/capture, shipping label creation, and notification — with compensating actions on failure.

### Composite

Leaf and composite share the same interface. A `PermissionGroup` (mode: "all" or "any") contains children that are either `SimplePermission` (leaf with predicate) or nested `PermissionGroup`. Calling `check(user)` recurses through the tree. Use for: permissions, file systems, org charts, UI component trees.

---

## 4. Behavioral Patterns

### Observer (EventEmitter, RxJS)

```typescript
// Type-safe EventEmitter
interface Events { "order.created": [order: Order]; "order.paid": [id: string, amount: number]; }

class TypedEmitter<T extends Record<string, any[]>> {
  private e = new EventEmitter();
  on<K extends keyof T & string>(ev: K, fn: (...args: T[K]) => void) { this.e.on(ev, fn as any); return this; }
  emit<K extends keyof T & string>(ev: K, ...args: T[K]) { return this.e.emit(ev, ...args); }
}

const bus = new TypedEmitter<Events>();
bus.on("order.paid", (id, amount) => { /* both params fully typed */ });
```

### Strategy

Interchangeable algorithms (compression, pricing, sorting) selected at runtime via a shared interface. Inject the strategy through the constructor or swap via a setter.

### Chain of Responsibility (Middleware)

```typescript
type Middleware<T> = (ctx: T, next: () => Promise<void>) => Promise<void>;

class Pipeline<T> {
  private stack: Middleware<T>[] = [];
  use(mw: Middleware<T>) { this.stack.push(mw); return this; }
  async execute(ctx: T) {
    let i = 0;
    const next = async (): Promise<void> => { if (i < this.stack.length) await this.stack[i++](ctx, next); };
    await next();
  }
}

// Auth → Rate Limit → Timing → Handler
```

### Command (CQRS)

```typescript
interface Command<R = void> { readonly type: string; }
interface Handler<C extends Command<R>, R = void> { execute(cmd: C): Promise<R>; }

class CommandBus {
  private handlers = new Map<string, Handler<any,any>>();
  register(type: string, h: Handler<any,any>) { this.handlers.set(type, h); }
  dispatch<R>(cmd: Command<R>): Promise<R> {
    const h = this.handlers.get(cmd.type);
    if (!h) throw new Error(`No handler: ${cmd.type}`);
    return h.execute(cmd);
  }
}
```

### Iterator (Generators, Symbol.asyncIterator)

```typescript
// Async iterator for paginated APIs — lazy, memory-efficient
class PaginatedFetcher<T> implements AsyncIterable<T> {
  constructor(private fetchPage: (cursor?: string) => Promise<{items: T[]; next?: string}>) {}
  async *[Symbol.asyncIterator]() {
    let cursor: string | undefined;
    do { const p = await this.fetchPage(cursor); for (const i of p.items) yield i; cursor = p.next; } while (cursor);
  }
}
```

### State

Each state is a class implementing a shared interface (`pay`, `ship`, `cancel`). The context (`StatefulOrder`) delegates all actions to its current state object. Invalid transitions throw errors. State transitions replace the state object: `order.transitionTo(new PaidState())`. Each state class only allows valid next states — the compiler and runtime together enforce the state machine.

---

## 5. Functional Patterns in TypeScript

### Pure Functions and Immutability

```typescript
type DeepReadonly<T> = { readonly [P in keyof T]: T[P] extends object ? DeepReadonly<T[P]> : T[P]; };

// Pure: no side effects, same input → same output
function applyDiscount(items: readonly CartItem[], rate: number): readonly CartItem[] {
  return items.map(i => ({ ...i, price: i.price * (1 - rate) })); // new array, originals untouched
}
```

### Higher-Order Functions

```typescript
// Functions that take or return functions — cross-cutting concerns
function withRetry<A extends unknown[], R>(fn: (...a: A) => Promise<R>, max = 3): (...a: A) => Promise<R> {
  return async (...a) => {
    for (let i = 0; i <= max; i++) {
      try { return await fn(...a); }
      catch (e) { if (i === max) throw e; await new Promise(r => setTimeout(r, 2**i*100)); }
    }
    throw new Error("unreachable");
  };
}

// Compose: withTiming(withRetry(fetchUser, 3), "fetchUser")
```

### Currying and Partial Application

```typescript
const hasMinLength = (min: number) => (v: string) => v.length >= min;
const matches = (re: RegExp) => (v: string) => re.test(v);
const all = (...fns: Array<(v: string) => boolean>) => (v: string) => fns.every(f => f(v));

const validatePassword = all(hasMinLength(8), matches(/[A-Z]/), matches(/[0-9]/), matches(/[^A-Za-z0-9]/));
```

### Function Composition (pipe)

```typescript
function pipe<A,B>(f1:(a:A)=>B): (a:A)=>B;
function pipe<A,B,C>(f1:(a:A)=>B, f2:(b:B)=>C): (a:A)=>C;
function pipe(...fns: Array<(a:any)=>any>) { return (a:any) => fns.reduce((r,f) => f(r), a); }

const processUser = pipe(normalizeFields, parseAge, assignTier); // left-to-right
```

### Monads in Practice (Result/Either)

```typescript
type Result<T, E = Error> = { ok: true; value: T } | { ok: false; error: E };
const Ok = <T>(v: T): Result<T, never> => ({ ok: true, value: v });
const Err = <E>(e: E): Result<never, E> => ({ ok: false, error: e });

// Chain: each step returns early on failure — no try/catch, all failures typed
async function register(email: string, pw: string): Promise<Result<User, RegError>> {
  const e = validateEmail(email); if (!e.ok) return e;
  const p = validatePassword(pw); if (!p.ok) return p;
  return Ok(await userRepo.create({ email: e.value, hash: await hash(p.value) }));
}
```

Libraries: **neverthrow**, **ts-results** provide `map`, `flatMap`, `match` for railway-oriented programming.

### Discriminated Unions + Exhaustive Switch

```typescript
type Payment =
  | { status: "pending"; createdAt: Date }
  | { status: "succeeded"; txId: string; amount: number }
  | { status: "failed"; reason: string; retryable: boolean };

function assertNever(v: never): never { throw new Error(`Unhandled: ${JSON.stringify(v)}`); }

function describe(p: Payment): string {
  switch (p.status) {
    case "pending":   return `Initiated ${p.createdAt.toISOString()}`;
    case "succeeded": return `${p.txId}: $${p.amount}`;
    case "failed":    return `${p.reason}${p.retryable ? " (retryable)" : ""}`;
    default:          return assertNever(p); // compile error if a case is missing
  }
}
```

---

## 6. Node.js-Specific Patterns

### Module Pattern (IIFE to ESM)

IIFE modules (`const M = (() => { ... })()`) → CommonJS (`module.exports`) → ESM (`export`). In ESM, module scope is private by default. Every export is explicit.

### Middleware Pattern

The fundamental Node pattern. Express = linear chain. Koa = onion model (each middleware controls upstream AND downstream via `await next()`). A `compose(middlewares)` function creates a dispatch chain where each middleware calls `next()` to proceed; calling `next()` twice throws. This is the core of Koa's `koa-compose`.

### Repository Pattern

Domain entity has zero database concerns. Repository interface (port) defined by domain. Concrete implementation handles ORM mapping and persistence.

### Unit of Work

Coordinates multiple repos in one transaction. `uow.orders.save(...)`, `uow.payments.save(...)`, then `uow.commit()`. On failure, `uow.rollback()`.

### Event Sourcing Basics

Store events (facts), derive state. `BankAccount.fromHistory(events)` replays events to reconstruct balance. Benefits: audit trail, temporal queries, replay. Cost: eventual consistency, storage, projection management.

### Plugin/Extension Pattern

Application exposes a hook registry. Plugins implement a `register(app)` method that adds hooks (`beforeRequest`, `afterResponse`, etc.). The app calls `executeHook("beforeRequest", req)` at the appropriate lifecycle points. Plugins can also implement `destroy()` for cleanup on shutdown. See: Fastify plugin system, Hapi, webpack.

### Dependency Injection Without a Framework

Constructor injection + a composition root. Use cases depend only on interfaces. The composition root (`container.ts`) is the single file that imports concrete implementations and wires them together:

```typescript
export function createContainer(cfg: Config) {
  const db = new Pool({ connectionString: cfg.dbUrl });
  const repo = new PgUserRepo(db);
  const mailer = new SendGridMailer(cfg.sgKey);
  return { registerUser: new RegisterUser(repo, mailer, new BcryptHasher(12)), shutdown: () => db.end() };
}
```

---

## 7. Error Handling Strategies

### Exception-Based vs Result-Based

| Aspect | Exceptions | Result Types |
|--------|-----------|--------------|
| Control flow | Non-local jumps | Explicit in return type |
| Type safety | `catch(e: unknown)` | `Result<T, E>` — typed |
| Composability | try/catch nests poorly | `map`/`flatMap` chain |
| Use when | Infrastructure failures, bugs | Validation, business rules |

**Rule**: Throw for bugs and catastrophes. Return `Result` for expected domain failures.

### Custom Error Hierarchies + Error Middleware

```typescript
abstract class AppError extends Error {
  abstract readonly statusCode: number;
  abstract readonly code: string;
  readonly isOperational = true;
  constructor(msg: string, public readonly cause?: Error) { super(msg); this.name = this.constructor.name; }
  toJSON() { return { code: this.code, message: this.message }; }
}

class NotFoundError extends AppError { readonly statusCode = 404; readonly code = "NOT_FOUND"; }
class ValidationError extends AppError { readonly statusCode = 400; readonly code = "VALIDATION_ERROR"; }
class ExternalServiceError extends AppError { readonly statusCode = 502; readonly code = "EXTERNAL_SERVICE_ERROR"; }

// Error middleware: operational errors → structured JSON; programmer bugs → generic 500
function errorHandler(err: Error, req: Request, res: Response, _next: NextFunction) {
  if (err instanceof AppError && err.isOperational) return res.status(err.statusCode).json(err.toJSON());
  logger.fatal("Unexpected", { error: err, requestId: req.id });
  res.status(500).json({ code: "INTERNAL_ERROR", message: "Unexpected error" });
}
```

### Error Boundaries

Higher-order function that wraps async operations with configurable fallback, error callback, or rethrow behavior. Use `{ fallback: [] }` for non-critical operations (recommendations) and `{ rethrow: true }` for critical ones (payments) where you still want to log metrics on failure.

### When to Throw vs Return

- **Throw**: programmer errors, assertions, infrastructure failures, framework boundaries (Express middleware expects thrown errors)
- **Return Result**: validation, business rules, "not found", composable library functions
- If the caller is expected to handle it, return it. If it is a bug, throw it.

---

## 8. Clean Architecture in Node.js/TypeScript

### Hexagonal Architecture (Ports & Adapters)

```
         ┌────────────────────────────────────────────┐
         │  DRIVING ADAPTERS (HTTP, CLI, GraphQL)      │
         │  ┌────────────────────────────────────────┐ │
         │  │  APPLICATION (Use Cases / Services)     │ │
         │  │  ┌────────────────────────────────────┐ │ │
         │  │  │  DOMAIN (Entities, Value Objects,  │ │ │
         │  │  │  Domain Services, Events)          │ │ │
         │  │  └────────────────────────────────────┘ │ │
         │  │  PORTS (interfaces defined by domain)   │ │
         │  └────────────────────────────────────────┘ │
         │  DRIVEN ADAPTERS (Postgres, Redis, SQS)     │
         └────────────────────────────────────────────┘
```

Domain defines ports. External systems implement them as adapters. Domain never imports infrastructure.

### DDD Concepts

**Value Objects** — immutable, compared by value, encapsulate validation. `Money.of(10, "USD").add(Money.of(5, "USD"))` returns a new Money. Prevents negative amounts, enforces same-currency operations. Other examples: `EmailAddress`, `OrderId`.

**Entities** — identity-based (compared by ID, not value). Mutable state with invariant enforcement. `Order.submit()` validates transition rules before changing state.

**Aggregates** — consistency boundaries. The aggregate root collects domain events during mutations. After persistence, events are dispatched: `order.pullDomainEvents()`.

```typescript
abstract class AggregateRoot {
  private events: DomainEvent[] = [];
  protected addEvent(e: DomainEvent) { this.events.push(e); }
  pullEvents() { const e = [...this.events]; this.events = []; return e; }
}

class Order extends AggregateRoot {
  submit() {
    if (this.status !== "draft") throw new Error("Invalid transition");
    this.status = "submitted";
    this.addEvent({ type: "OrderSubmitted", aggregateId: this.id, timestamp: new Date() });
  }
}
```

### Project Structure

```
src/
├── domain/           # Zero external dependencies
│   ├── entities/     # Business logic (Order, User)
│   ├── value-objects/ # Money, EmailAddress
│   ├── events/       # OrderCreated, OrderShipped
│   ├── errors/       # Domain-specific errors
│   ├── ports/        # Interfaces for adapters
│   └── services/     # Stateless domain logic
├── application/      # Use cases, orchestration
│   ├── commands/     # PlaceOrder, CancelOrder
│   ├── queries/      # GetOrder, ListOrders
│   └── event-handlers/
├── infrastructure/   # All external concerns
│   ├── persistence/  # PrismaOrderRepository
│   ├── messaging/    # SQS/Kafka adapters
│   ├── http/         # Controllers, middleware
│   └── config/
├── container.ts      # Composition root
└── main.ts
```

### The Dependency Rule

**Dependencies point inward only**: Infrastructure -> Application -> Domain. Domain depends on NOTHING external.

```typescript
// VIOLATION
import { PrismaClient } from "@prisma/client"; // in domain/ → NEVER

// CORRECT: infrastructure implements domain ports
// infrastructure/persistence/prisma-order.repo.ts
import { OrderRepoPort } from "../../domain/ports/order-repo.port"; // OK
import { PrismaClient } from "@prisma/client"; // OK in infrastructure
```

### Hexagonal vs Onion vs Clean Architecture

| Aspect | Hexagonal | Onion | Clean Architecture |
|--------|-----------|-------|--------------------|
| Core idea | Ports & Adapters | Concentric layers | Dependency rule + use cases |
| Key distinction | Driving vs driven ports | Strict rings | Explicit use case layer |
| In practice | Same idea, different vocabulary — pick one, be consistent |

**Senior insight**: Do not over-architect. A CRUD service does not need hexagonal architecture. Graduate when: multiple entry points share logic, you need to swap infrastructure, or domain logic warrants isolated testing.
