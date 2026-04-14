# NestJS & Express — Senior Engineer Interview Preparation

---

## 1. Express Fundamentals

### Middleware Pipeline Architecture

Express is a minimal web framework built on Node.js `http` module. Its core abstraction is the **middleware function** — a function with access to `req`, `res`, and `next`. Every incoming request flows through middleware in registration order.


```
  HTTP Request --> cors() --> helmet() --> json() --> morgan()
       |
       v
  Router Match (/api/users)
       |
       v
  authMW() --> validate() --> handler() --> res.json()
       |                                       |
       |                                 (if error)
       v                                       v
                                       Error MW (4 args)
                                               |
                                               v
                                          HTTP Response
```


### Middleware Ordering and `next()` Mechanics

```typescript
const app = express();

// Application-level — runs on EVERY request, order matters
app.use(express.json());                          // 1. Parse JSON body
app.use(express.urlencoded({ extended: true }));  // 2. Parse form data
app.use(cors({ origin: 'https://app.com' }));    // 3. CORS headers

// Custom middleware
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => console.log(`${req.method} ${req.path} ${Date.now() - start}ms`));
  next(); // MUST call next() or request hangs forever
});

// Router-level — scoped to path
const userRouter = express.Router();
userRouter.use(authenticate);
userRouter.get('/', listUsers);
userRouter.post('/', validate(createUserSchema), createUser);
app.use('/api/users', userRouter);

// 404 — MUST come after all routes
app.use((req, res) => res.status(404).json({ error: 'Not Found' }));

// Error handler — MUST be last, MUST have 4 params (Express checks Function.length)
app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
  console.error(err.stack);
  res.status(500).json({ error: 'Internal Server Error' });
});
```

**`next()` behaviors**: `next()` passes to next middleware; `next('route')` skips remaining handlers in current router; `next(err)` jumps to error middleware.

### Error-Handling Middleware

Express identifies error middleware by its **4-argument signature** `(err, req, res, next)` — it inspects `Function.length`. Async errors require explicit forwarding since Express does not catch rejected promises:

```typescript
// Async wrapper — catches rejected promises and forwards to error middleware
const asyncHandler = (fn: Function) =>
  (req: Request, res: Response, next: NextFunction) =>
    Promise.resolve(fn(req, res, next)).catch(next);

app.get('/api/users/:id', asyncHandler(async (req, res) => {
  const user = await userService.findById(req.params.id);
  if (!user) throw new AppError(404, 'User not found');
  res.json(user);
}));
```

| Aspect | Application-Level | Router-Level |
|--------|-------------------|--------------|
| Scope | All routes | Routes within the router |
| Registration | `app.use(fn)` | `router.use(fn)` |
| Use case | Logging, CORS, body parsing | Auth for /api/*, admin for /admin/* |

---

## 2. NestJS Architecture

### Module System

NestJS enforces modular architecture. Every app has one **root module** and zero or more **feature modules**.

```typescript
@Module({
  imports: [ConfigModule.forRoot({ isGlobal: true }), DatabaseModule, UsersModule, OrdersModule],
})
export class AppModule {}

@Module({
  imports: [DatabaseModule],
  controllers: [UsersController],
  providers: [UsersService, UsersRepository],
  exports: [UsersService],  // Available to importing modules
})
export class UsersModule {}

@Global()  // Available everywhere without explicit import
@Module({ providers: [CacheService], exports: [CacheService] })
export class CacheModule {}
```

| Type | Decorator | Behavior |
|------|-----------|----------|
| Feature | `@Module()` | Encapsulates a domain; must be imported |
| Shared | `@Module()` + `exports` | Re-exports providers |
| Global | `@Global()` + `@Module()` | Providers available app-wide |
| Dynamic | `static forRoot()` | Configurable at import time |

### Controllers, Services, Repositories

```typescript
@Controller('users')
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get(':id')
  findOne(@Param('id', ParseIntPipe) id: number) { return this.usersService.findOne(id); }

  @Post() @HttpCode(201)
  create(@Body(ValidationPipe) dto: CreateUserDto) { return this.usersService.create(dto); }
}

@Injectable()
export class UsersService {
  constructor(private readonly usersRepo: UsersRepository) {}
  async findOne(id: number) {
    const user = await this.usersRepo.findById(id);
    if (!user) throw new NotFoundException(`User #${id} not found`);
    return user;
  }
}
```

### NestJS Request Lifecycle

```
  Incoming Request
       │
       ▼
  ┌──────────────┐   app.use() / consumer.apply()
  │  Middleware   │
  └──────┬───────┘
         ▼
  ┌──────────────┐   @UseGuards() — CanActivate
  │   Guards      │   Returns boolean — proceed or reject
  └──────┬───────┘
         ▼
  ┌──────────────┐   @UseInterceptors() — BEFORE handler
  │ Interceptors  │   RxJS-based: logging, timers, request transform
  └──────┬───────┘
         ▼
  ┌──────────────┐   @UsePipes() — PipeTransform
  │    Pipes      │   Validation + transformation per parameter
  └──────┬───────┘
         ▼
  ┌──────────────┐
  │ Route Handler │   Controller method → service call
  └──────┬───────┘
         ▼
  ┌──────────────┐   @UseInterceptors() — AFTER handler
  │ Interceptors  │   Response mapping, caching, headers
  └──────┬───────┘
         ▼
  ┌──────────────┐   @UseFilters() — ExceptionFilter
  │  Exception    │   Catches exceptions from any prior stage
  │   Filters     │
  └──────┬───────┘
         ▼
    HTTP Response
```

### Dynamic Modules: `forRoot()` / `forRootAsync()`

Dynamic modules allow configuration at import time. `forRoot()` accepts options directly; `forRootAsync()` accepts a factory with injected dependencies (e.g., `ConfigService`):

```typescript
@Module({})
export class CacheModule {
  static forRoot(options: CacheOptions): DynamicModule {
    return {
      module: CacheModule, global: options.isGlobal ?? false,
      providers: [{ provide: 'CACHE_OPTIONS', useValue: options }, CacheService],
      exports: [CacheService],
    };
  }

  static forRootAsync(options: CacheAsyncOptions): DynamicModule {
    return {
      module: CacheModule, imports: options.imports || [],
      providers: [{ provide: 'CACHE_OPTIONS', useFactory: options.useFactory, inject: options.inject }],
      exports: [CacheService],
    };
  }
}
```

---

## 3. Dependency Injection in NestJS

### How DI Works Under the Hood

NestJS uses **reflection metadata** (`reflect-metadata` + TypeScript `emitDecoratorMetadata`) to build a dependency graph at startup:

1. TypeScript compiler emits type metadata via `Reflect.defineMetadata('design:paramtypes', ...)`
2. `@Injectable()` marks classes as DI-managed providers
3. At bootstrap, NestJS reads constructor parameter types, builds a DAG, topologically sorts, and instantiates in order
4. Singleton by default — instantiated once and cached

**Interface limitation**: TypeScript interfaces are erased at runtime. Use injection tokens:

```typescript
const USERS_REPO = Symbol('USERS_REPO');

@Module({
  providers: [{ provide: USERS_REPO, useClass: TypeOrmUsersRepository }],
})
export class UsersModule {}

@Injectable()
export class UsersService {
  constructor(@Inject(USERS_REPO) private readonly repo: IUsersRepository) {}
}
```

### Custom Providers

```typescript
@Module({
  providers: [
    { provide: UsersService, useClass: UsersService },     // useClass (shorthand: just UsersService)
    { provide: 'API_KEY', useValue: process.env.API_KEY }, // useValue — static/mock
    { provide: 'DB', useFactory: async (c: ConfigService) => // useFactory — dynamic
        new DataSource({ host: c.get('DB_HOST') }).initialize(), inject: [ConfigService] },
    { provide: 'AliasedLogger', useExisting: LoggerService }, // useExisting — alias
  ],
})
```

### Injection Scopes

| Scope | Behavior | Performance Impact |
|-------|----------|--------------------|
| DEFAULT (singleton) | One instance for app lifetime | Best |
| REQUEST | New instance per HTTP request | Moderate |
| TRANSIENT | New instance per injection point | Highest |

```typescript
@Injectable({ scope: Scope.REQUEST })
export class RequestContextService {
  private tenantId: string;
  setTenant(id: string) { this.tenantId = id; }
  getTenant(): string { return this.tenantId; }
}
```

**Scope bubble-up**: If `ServiceA` (singleton) depends on `ServiceB` (request-scoped), then `ServiceA` is silently promoted to request-scoped. This can cause dramatic performance degradation.

### Circular Dependencies and Optional Injection

```typescript
// Circular: UsersService <-> OrdersService — use forwardRef()
@Injectable()
export class UsersService {
  constructor(
    @Inject(forwardRef(() => OrdersService)) private readonly ordersService: OrdersService,
  ) {}
}

// Optional dependency — no error if not registered
@Injectable()
export class NotificationService {
  constructor(@Optional() @Inject('SMS_PROVIDER') private readonly sms?: SmsProvider) {}
}
```

### Custom Decorators

```typescript
// Parameter decorator — extracts user from request
export const CurrentUser = createParamDecorator(
  (data: keyof User | undefined, ctx: ExecutionContext) => {
    const user = ctx.switchToHttp().getRequest().user;
    return data ? user?.[data] : user;
  },
);

// Composed decorator — bundles guards + metadata + swagger
export const Auth = (...roles: Role[]) => applyDecorators(
  UseGuards(JwtAuthGuard, RolesGuard), SetMetadata('roles', roles), ApiBearerAuth(),
);
```

---

## 4. Guards, Interceptors, Pipes, Filters

### Guards — `CanActivate`

Guards return boolean — `true` proceeds, `false`/exception rejects. Use `Reflector` to read metadata set by decorators.

```typescript
@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(private jwtService: JwtService, private reflector: Reflector) {}
  async canActivate(context: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>('isPublic', [
      context.getHandler(), context.getClass(),
    ]);
    if (isPublic) return true;
    const request = context.switchToHttp().getRequest();
    const [type, token] = request.headers.authorization?.split(' ') ?? [];
    if (type !== 'Bearer' || !token) throw new UnauthorizedException();
    request.user = await this.jwtService.verifyAsync(token);
    return true;
  }
}

// RolesGuard reads @SetMetadata('roles', [...]) via Reflector
// Returns roles.some(role => user.roles?.includes(role))
```

### Interceptors — `NestInterceptor`

Interceptors wrap handler execution using RxJS. Common uses: logging, response transformation, caching, timeouts.

```typescript
@Injectable()
export class LoggingInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const { method, url } = context.switchToHttp().getRequest();
    const start = Date.now();
    return next.handle().pipe(
      tap(() => console.log(`${method} ${url} — ${Date.now() - start}ms`)),
    );
  }
}

// Response wrapping — map() transforms data after handler
// Timeout — timeout(5000) + catchError for RequestTimeoutException
// Caching — check cache before next.handle(), tap() to store after
```

### Pipes — `PipeTransform`

```typescript
// Built-in pipes: ParseIntPipe, ParseBoolPipe, ParseUUIDPipe, DefaultValuePipe, ValidationPipe

// class-validator DTO
export class CreateUserDto {
  @IsString() @MinLength(2) @MaxLength(50) name: string;
  @IsEmail() email: string;
  @IsString() @MinLength(8) @Matches(/^(?=.*[A-Z])(?=.*[0-9])/) password: string;
  @IsOptional() @IsEnum(Role) role?: Role;
}

// Global validation pipe
app.useGlobalPipes(new ValidationPipe({
  whitelist: true,            // Strip unknown properties
  forbidNonWhitelisted: true, // Throw on unknown properties
  transform: true,            // Auto-transform to DTO instances
}));
```

### Exception Filters — `ExceptionFilter`

`@Catch()` with no argument catches all exceptions. `@Catch(HttpException)` catches only HTTP exceptions. Use `host.switchToHttp()` to access request/response. Can also `@Catch(TypeORMError)` for database-specific error mapping (e.g., unique constraint → 409).

### Execution Order and Binding Levels

```
Global MW → Module MW → Global Guards → Controller Guards → Route Guards →
Global Interceptors (pre) → Controller Interceptors (pre) → Route Interceptors (pre) →
Global Pipes → Controller Pipes → Route Pipes → Parameter Pipes →
Route Handler →
Route Interceptors (post) → Controller Interceptors (post) → Global Interceptors (post) →
Route Filters → Controller Filters → Global Filters
```

**Binding**: Global (`app.useGlobal*()` or `APP_GUARD`/`APP_PIPE`/etc. provider tokens), Controller (`@Use*()` on class), Route (`@Use*()` on method).

---

## 5. NestJS Microservices

### Transport Layers

| Transport | Protocol | Best For |
|-----------|----------|----------|
| TCP | Custom binary | Internal services, low latency |
| Redis | Pub/Sub | Simple event broadcasting |
| NATS | Text-based | Cloud-native, auto-discovery |
| Kafka | Binary | High-throughput event streaming |
| gRPC | HTTP/2 + Protobuf | Cross-language, strongly typed |
| RabbitMQ | AMQP | Complex routing, guaranteed delivery |

### Message Patterns: Request-Response vs Event-Based

```typescript
// REQUEST-RESPONSE — client.send() returns Observable, consumer returns value
@Post()
create(@Body() dto: CreateOrderDto): Observable<OrderResponse> {
  return this.client.send('orders.create', dto); // Waits for response
}

@MessagePattern('orders.create') // Consumer — return value sent back to caller
handleCreate(@Payload() dto: CreateOrderDto): Promise<OrderResponse> {
  return this.ordersService.create(dto);
}

// EVENT-BASED — client.emit() is fire-and-forget
this.client.emit('order.created', { orderId, userId, total });

@EventPattern('order.created') // Consumer — no return value
async handle(@Payload() data: OrderCreatedEvent): Promise<void> {
  await this.notifications.sendConfirmation(data);
}
```

### Hybrid Applications

Serve HTTP and microservice transports simultaneously via `app.connectMicroservice()`. Call `startAllMicroservices()` then `listen()` to run both HTTP and message consumers in the same process.

### Exception Handling in Microservices

HTTP exceptions do not work in microservice context — use `RpcException`:

```typescript
@MessagePattern('orders.find')
async findOrder(@Payload() data: { id: string }): Promise<Order> {
  const order = await this.ordersService.findById(data.id);
  if (!order) throw new RpcException({ status: 404, message: `Order ${data.id} not found` });
  return order;
}
```

---

## 6. Fastify as Alternative

### Fastify vs Express

| Metric | Express | Fastify |
|--------|---------|---------|
| Requests/sec (hello world) | ~15,000 | ~77,000 |
| JSON serialization | `JSON.stringify` at runtime | Compiled via `fast-json-stringify` |
| Routing | Linear regex matching | Radix tree (`find-my-way`) |
| Validation | External (Joi/Yup) | Built-in JSON Schema (ajv) |
| Plugin system | None (middleware) | Encapsulated plugin tree |
| Ecosystem | Massive (10+ years) | Growing rapidly |

**Why Fastify is faster**: Schemas are compiled at startup into optimized serialization functions that skip key enumeration and type checking at runtime.

### Schema-Based Validation and Plugins

Schemas serve triple duty: **validation**, **serialization** (response schema strips unlisted fields — prevents data leakage), and **documentation**.

```typescript
app.post('/users', {
  schema: {
    body: { type: 'object', required: ['name', 'email'],
      properties: { name: { type: 'string' }, email: { type: 'string', format: 'email' } } },
    response: { 201: { type: 'object',
      properties: { id: { type: 'string' }, name: { type: 'string' } } } }, // password stripped
  }
}, async (request, reply) => {
  reply.code(201).send(await createUser(request.body));
});
```

**Plugin encapsulation**: By default, decorators are scoped to the plugin and its children. Wrapping with `fastify-plugin` (`fp()`) breaks encapsulation, exposing decorators to the parent scope.

**Hooks lifecycle**: `onRequest` -> `preParsing` -> `preValidation` -> `preHandler` -> Handler -> `preSerialization` -> `onSend` -> `onResponse` (with `onError` on throw).

### Using Fastify with NestJS

```typescript
const app = await NestFactory.create<NestFastifyApplication>(
  AppModule,
  new FastifyAdapter({ logger: true, trustProxy: true }),
);
// IMPORTANT: Fastify binds to localhost by default
await app.listen(3000, '0.0.0.0');
```

**Caveats**: Express middleware (passport, multer) needs Fastify equivalents; `@Res()` API differs; some NestJS community packages assume Express.

---

## 7. API Design Best Practices

### REST Maturity Model (Richardson)

| Level | Name | Example |
|-------|------|---------|
| 0 | Swamp of POX | `POST /api` with action in body |
| 1 | Resources | `POST /users`, `POST /orders` |
| 2 | HTTP Verbs | `GET /users`, `POST /users`, `DELETE /users/1` |
| 3 | HATEOAS | Responses include `_links` to related actions |

### GraphQL with NestJS

Two approaches: **code-first** (TypeScript decorators generate schema) and **schema-first** (.graphql files generate types).

```typescript
// Code-first approach
@ObjectType()
export class User {
  @Field(() => ID) id: string;
  @Field() name: string;
  @Field(() => [Order]) orders: Order[];
}

@Resolver(() => User)
export class UsersResolver {
  @Query(() => User) user(@Args('id', { type: () => ID }) id: string) {
    return this.usersService.findOne(id);
  }

  @ResolveField(() => [Order]) // N+1 solution: DataLoader batches into single query
  orders(@Parent() user: User, @Context('ordersLoader') loader: DataLoader<string, Order[]>) {
    return loader.load(user.id);
  }
}
```

### Versioning and Rate Limiting

**Versioning** — three strategies: URI (`/v1/users`), Header (`X-API-Version: 2`), Media Type (`Accept: application/json;v=2`). Use `app.enableVersioning({ type: VersioningType.URI })` and `@Controller({ path: 'users', version: '1' })`.

**Rate limiting** (`@nestjs/throttler`) — multi-tier throttling as global guard. Override per route with `@Throttle()`, exempt with `@SkipThrottle()`. For distributed systems, use `ThrottlerStorageRedisService`.

### Health Checks and Graceful Shutdown

```typescript
// @nestjs/terminus — Kubernetes-style health endpoints
@Controller('health')
export class HealthController {
  constructor(private health: HealthCheckService, private db: TypeOrmHealthIndicator) {}

  @Get() @HealthCheck()
  check() { return this.health.check([() => this.db.pingCheck('database')]); }

  @Get('liveness') @HealthCheck()
  liveness() { return this.health.check([]); }

  @Get('readiness') @HealthCheck()
  readiness() { return this.health.check([() => this.db.pingCheck('db')]); }
}

// Graceful shutdown — app.enableShutdownHooks() listens SIGTERM/SIGINT
// Implement BeforeApplicationShutdown to drain connections before exit
```

### CQRS and Event Sourcing with NestJS

`@nestjs/cqrs` separates read and write models via CommandBus, QueryBus, and EventBus:

```typescript
// COMMAND (write) — handler validates, persists, publishes events
@CommandHandler(CreateOrderCommand)
export class CreateOrderHandler implements ICommandHandler<CreateOrderCommand> {
  async execute(cmd: CreateOrderCommand): Promise<string> {
    const order = Order.create(cmd.userId, cmd.items);
    await this.orderRepo.save(order);
    this.eventBus.publish(new OrderCreatedEvent(order.id, order.total));
    return order.id;
  }
}

// QUERY (read) — reads from denormalized read model
@QueryHandler(GetOrderQuery)
export class GetOrderHandler implements IQueryHandler<GetOrderQuery> {
  async execute(query: GetOrderQuery) { return this.readRepo.findById(query.orderId); }
}

// EVENT — updates read models, triggers side effects
@EventsHandler(OrderCreatedEvent)
export class OrderCreatedHandler implements IEventHandler<OrderCreatedEvent> {
  async handle(event: OrderCreatedEvent) {
    await this.readRepo.project(event);
    await this.notifications.send(event);
  }
}

// SAGA — event-to-command orchestration
@Saga()
orderCreated = (events$: Observable<any>) => events$.pipe(
  ofType(OrderCreatedEvent),
  map((e) => new ProcessPaymentCommand(e.orderId, e.total)),
);

// Controller dispatches via buses
@Post() create(@Body() dto: CreateOrderDto) {
  return this.commandBus.execute(new CreateOrderCommand(dto.userId, dto.items));
}
```

**Event Sourcing**: Store events instead of state; reconstruct via replay. Append-only event store enables audit trails, temporal queries, and multiple read projections (Postgres, Elastic, etc.).

**When to use**: High read-to-write ratio, audit/compliance, complex domains, temporal queries. **When NOT to**: Simple CRUD, small teams, unacceptable eventual consistency.

---

## 8. HTTP & API Fundamentals (interview-critical)

### HTTP Semantics Refresher

**Safe methods** do not modify server state: `GET`, `HEAD`, `OPTIONS`, `TRACE`. **Idempotent methods** produce the same server state whether called once or N times: `GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE` (and `TRACE`). `POST` and `PATCH` are **neither safe nor idempotent** by default — two identical `POST /orders` calls create two orders unless the server enforces idempotency (see below).

| Method | Safe | Idempotent | Typical semantics |
|--------|------|------------|-------------------|
| GET | yes | yes | Read resource; cacheable |
| HEAD | yes | yes | Same as GET but no body |
| PUT | no | yes | Replace entire resource at URI (client provides ID) |
| DELETE | no | yes | Remove resource (second call may 404 — still idempotent in effect) |
| POST | no | no | Create subordinate resource; server assigns ID |
| PATCH | no | no* | Partial update; only idempotent if payload is absolute (not `{counter: {$inc: 1}}`) |

**Status code families and common misuses**:

| Range | Meaning | Misuse |
|-------|---------|--------|
| 2xx | Success | Returning `200 { error: "..." }` — hides failures from monitoring; clients must parse body to detect errors. Always use 4xx/5xx on failure. |
| 3xx | Redirect | Using `302` when the redirect is permanent (`301`/`308`); `307`/`308` preserve method and body, `301`/`302` historically did not. |
| 4xx | Client error | `400` catch-all when `422 Unprocessable Entity` (valid JSON, semantic failure) or `409 Conflict` (state collision) is more precise. |
| 5xx | Server error | `500` when upstream timed out — prefer `504 Gateway Timeout` or `503 Service Unavailable` with `Retry-After`. |

**404 vs 410 vs 409**:
- `404 Not Found` — resource does not exist (or caller lacks permission to know).
- `410 Gone` — resource existed, was intentionally removed, and will not return. Tells search engines / caches to purge.
- `409 Conflict` — request conflicts with current server state (optimistic-locking version mismatch, duplicate unique key, trying to delete a non-empty bucket).

Other frequently confused pairs: `401 Unauthorized` (not authenticated) vs `403 Forbidden` (authenticated but not allowed); `429 Too Many Requests` (rate-limited, include `Retry-After`); `412 Precondition Failed` (conditional request failed — `If-Match` mismatch).

### Express 4 vs Express 5 Async Error Handling

**Express 4**: the router catches synchronous throws but **ignores rejected promises**. An `async` handler that rejects hangs the request until the socket times out (the error goes to `unhandledRejection`, not your error middleware).

```typescript
// EXPRESS 4 — BROKEN: rejection never reaches the error handler
app.get('/users/:id', async (req, res) => {
  const user = await userRepo.findById(req.params.id); // throws
  res.json(user);
});

// EXPRESS 4 — FIX A: manual try/catch (verbose)
app.get('/users/:id', async (req, res, next) => {
  try {
    const user = await userRepo.findById(req.params.id);
    res.json(user);
  } catch (err) { next(err); }
});

// EXPRESS 4 — FIX B: wrapper (most common in practice)
const asyncHandler =
  <T extends Request>(fn: (req: T, res: Response, next: NextFunction) => Promise<unknown>) =>
  (req: T, res: Response, next: NextFunction) =>
    Promise.resolve(fn(req, res, next)).catch(next);

app.get('/users/:id', asyncHandler(async (req, res) => {
  res.json(await userRepo.findById(req.params.id));
}));
// Or the `express-async-handler` / `express-async-errors` packages.
```

**Express 5** (GA 2024): any handler or middleware that returns a rejected `Promise` (including `async` functions that throw) is automatically forwarded to error-handling middleware — equivalent to `.catch(next)` being injected for you. Confirmed from the [Express 5 error-handling guide](https://expressjs.com/en/guide/error-handling.html): *"Starting with Express 5, route handlers and middleware that return a Promise will call next(value) automatically when they reject or throw an error."*

```typescript
// EXPRESS 5 — WORKS: rejection auto-forwarded to error middleware
app.get('/users/:id', async (req, res) => {
  const user = await userRepo.findById(req.params.id); // throws? -> next(err)
  if (!user) throw new HttpError(404, 'User not found');
  res.json(user);
});

app.use((err: Error, req: Request, res: Response, _next: NextFunction) => {
  const status = err instanceof HttpError ? err.status : 500;
  res.status(status).json({ type: 'about:blank', title: err.message, status });
});
```

Other Express 5 breaking changes worth mentioning: dropped Node < 18, path-to-regexp v8 (**new named-wildcard syntax: `*splat` / `{*splat}`, not the old `:name(.*)`** — regex-in-path and unnamed `*` are removed), `res.redirect('back')` removed, `req.param()` removed. Example: migrate `/files/:name(.*)` → `/files/*splat`, then read via `req.params.splat`.

### Idempotency Keys

An **idempotency key** is a client-supplied opaque token (usually UUID v4) sent with non-idempotent requests (`POST`/`PATCH`) so the server can deduplicate retries. Stripe popularised the `Idempotency-Key` header convention.

**When**: any write whose retry could cause duplicate side effects — payments, order creation, outbound emails, SMS, webhook handlers.

**Protocol**:
1. Client generates a key, sends `Idempotency-Key: <uuid>` with the request.
2. Server atomically inserts `(key, request_fingerprint, status=IN_PROGRESS)` into an idempotency store.
3. If insert collides on key with same fingerprint and `status=DONE`, return the stored response.
4. If collides with same fingerprint and `status=IN_PROGRESS`, return `409 Conflict` (or block briefly).
5. If collides with a **different** fingerprint (same key, different body), return `422 Unprocessable Entity` — key reuse with mismatched payload.
6. On completion, store `{status, headers, body}` against the key with a TTL (typically 24h).

```typescript
// Express 5 middleware — Redis-backed idempotency store
type IdemRecord =
  | { state: 'IN_PROGRESS'; fingerprint: string }
  | { state: 'DONE'; fingerprint: string; statusCode: number; body: unknown; headers: Record<string, string> };

export const idempotency = (redis: Redis, ttlSec = 86_400): RequestHandler =>
  async (req, res, next) => {
    if (!['POST', 'PATCH'].includes(req.method)) return next();
    const key = req.header('Idempotency-Key');
    if (!key) return next(); // optional: require it -> 400

    const fingerprint = crypto
      .createHash('sha256')
      .update(req.method + req.originalUrl + JSON.stringify(req.body ?? {}))
      .digest('hex');
    const redisKey = `idem:${req.user?.id ?? 'anon'}:${key}`;

    // SET NX so the first request wins the lock
    const pending: IdemRecord = { state: 'IN_PROGRESS', fingerprint };
    const locked = await redis.set(redisKey, JSON.stringify(pending), 'EX', ttlSec, 'NX');
    if (!locked) {
      const stored = JSON.parse((await redis.get(redisKey))!) as IdemRecord;
      if (stored.fingerprint !== fingerprint) {
        return res.status(422).json({ title: 'Idempotency-Key reused with different payload' });
      }
      if (stored.state === 'IN_PROGRESS') {
        return res.status(409).json({ title: 'Request in progress, retry shortly' });
      }
      // stored.state === 'DONE' — replay the captured response
      Object.entries(stored.headers).forEach(([h, v]) => res.setHeader(h, v));
      return res.status(stored.statusCode).json(stored.body);
    }

    // Capture response to store on finish
    const origJson = res.json.bind(res);
    res.json = (body: unknown) => {
      const done: IdemRecord = {
        state: 'DONE', fingerprint,
        statusCode: res.statusCode, body,
        headers: { 'content-type': 'application/json' },
      };
      void redis.set(redisKey, JSON.stringify(done), 'EX', ttlSec);
      return origJson(body);
    };
    next();
  };
```

**Conflict handling nuances**: store the idempotency record in the **same transaction** as the business write (or via transactional outbox) — otherwise a crash between "wrote order" and "wrote idem record" leaves the next retry creating a duplicate. See `theory/system_design/patterns/idempotency.md` and `theory/system_design/patterns/outbox-pattern.md` for the distributed-systems perspective.

### Retries, Timeouts, and Backoff

**Why timeouts are non-negotiable**: without them a slow upstream holds your event-loop worker / socket indefinitely, producing cascading failure ("threadpool exhaustion" in Node terms: all sockets pinned, new requests queue, LB health checks fail, instance is killed, load shifts, repeat). Every outbound call needs **connect**, **headers**, and **body** timeouts.

**Retry only idempotent operations** (or non-idempotent ones guarded by an idempotency key). Never retry `500` responses that report a domain error — those will fail again. Retry on network errors, `408`, `425`, `429`, `502`, `503`, `504`.

**Exponential backoff with full jitter** avoids thundering-herd synchronisation on recovery:

```typescript
// delay = random(0, min(cap, base * 2^attempt))  -- AWS "full jitter"
async function retry<T>(fn: () => Promise<T>, opts = { max: 5, baseMs: 100, capMs: 10_000 }): Promise<T> {
  let lastErr: unknown;
  for (let attempt = 0; attempt < opts.max; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastErr = err;
      if (!isRetryable(err) || attempt === opts.max - 1) throw err;
      const exp = Math.min(opts.capMs, opts.baseMs * 2 ** attempt);
      const delay = Math.random() * exp; // full jitter
      await new Promise((r) => setTimeout(r, delay));
    }
  }
  throw lastErr;
}

// Respect Retry-After on 429/503
function retryAfterMs(err: HttpError): number | null {
  const h = err.headers?.['retry-after'];
  if (!h) return null;
  const n = Number(h);
  return Number.isFinite(n) ? n * 1000 : Math.max(0, new Date(h).getTime() - Date.now());
}
```

**Budgets**: set a total deadline per inbound request (propagated as `X-Request-Deadline` or gRPC deadline). Each retry must fit inside the remaining budget — otherwise you amplify load on a struggling upstream while the client has already given up.

**Circuit breakers** prevent retry storms when the upstream is clearly unhealthy (open -> half-open -> closed). Deep coverage lives in `theory/system_design/patterns/circuit-breaker.md`; in Node, `opossum` is the usual library. For server-side request shedding see `theory/system_design/patterns/rate-limiting.md`.

### Proxy Awareness

Production Node almost always sits behind L7 proxies (ALB, Nginx, Envoy, Cloudflare). These terminate TLS and rewrite the source address, so `req.socket.remoteAddress` is the **proxy's** IP, not the client's. Trust is opt-in because a naive app would let any attacker spoof `X-Forwarded-For`.

```typescript
// Express — tell it how many proxy hops to trust
app.set('trust proxy', 1);                 // exactly one hop (typical ALB/ELB)
app.set('trust proxy', 'loopback, 10.0.0.0/8');  // subnet list
app.set('trust proxy', (ip, _hop) => ip === '10.0.0.1'); // custom predicate

app.get('/whoami', (req, res) => {
  res.json({
    ip: req.ip,             // leftmost untrusted X-Forwarded-For entry
    ips: req.ips,           // full trusted chain
    proto: req.protocol,    // https if XFP header present and trusted
    host: req.hostname,     // X-Forwarded-Host when trusted
  });
});
```

**Forwarded headers**:
- `X-Forwarded-For: client, proxy1, proxy2` (legacy but universal) — leftmost is the originating client; only trust entries from proxies you control.
- `X-Forwarded-Proto: https` — needed so `req.secure`, cookie `secure` flag, and HSTS logic work when TLS is terminated at the LB.
- `X-Forwarded-Host`, `X-Forwarded-Port`.
- RFC 7239 `Forwarded: for=1.2.3.4;proto=https;host=api.example.com` — standardised replacement.
- Cloudflare: `CF-Connecting-IP`; GCP: `X-Client-IP`; AWS ALB: also `X-Forwarded-For`.

**Consequences of forgetting `trust proxy`**: rate limiters bucket every user under the LB IP; audit logs show the LB; `express-session` with `cookie.secure=true` refuses to set cookies because `req.secure === false`. Fastify exposes the same concept via `trustProxy`; NestJS: `app.set('trust proxy', 1)` on the underlying adapter, or `new FastifyAdapter({ trustProxy: true })`.

### Cache Validators

HTTP caching has two dimensions: **freshness** (can I use the cached copy without asking?) and **validation** (the cached copy expired — is it still current?).

**`Cache-Control`** drives freshness. Common directives:

| Directive | Meaning |
|-----------|---------|
| `public` / `private` | Cacheable by shared caches (CDN) vs only browser |
| `max-age=<s>` | Freshness lifetime in seconds |
| `s-maxage=<s>` | Overrides `max-age` for shared caches only |
| `no-cache` | Store it, but revalidate on every use |
| `no-store` | Do not store at all (auth tokens, PII) |
| `must-revalidate` | After staleness, cache MUST revalidate, not serve stale |
| `stale-while-revalidate=<s>` | Serve stale while refreshing in background |
| `immutable` | Content never changes (hashed asset URLs) |

**`ETag`** is an opaque content fingerprint ("strong" = byte-exact, `W/"..."` = weak/semantic equivalence). **`Last-Modified`** is a second-precision timestamp. Clients echo them back as `If-None-Match` / `If-Modified-Since`; if the server agrees, it replies `304 Not Modified` with an empty body.

Express auto-generates weak ETags for `res.send()` bodies; you can also set them explicitly. **Validator choice matters**: `If-None-Match` (cache revalidation on GET) allows weak comparison, but `If-Match` (optimistic concurrency on PUT/PATCH/DELETE) requires **strong** comparison per [RFC 9110 §8.8.3.2](https://www.rfc-editor.org/rfc/rfc9110#section-8.8.3.2) — weak ETags are legal on reads but must not be used to gate writes:

```typescript
app.get('/articles/:id', async (req, res) => {
  const article = await articles.findById(req.params.id);
  if (!article) throw new HttpError(404, 'Not found');

  // Strong ETag: derived from a value that uniquely identifies the byte-exact
  // representation (row version + content hash). This is what we will compare
  // against If-Match on writes, so it MUST be strong.
  const strongEtag = `"${article.version}-${article.contentHash}"`;
  const lastMod = article.updatedAt.toUTCString();

  // Conditional GET short-circuit (weak comparison is fine here)
  if (req.header('if-none-match') === strongEtag ||
      req.header('if-modified-since') === lastMod) {
    return res.status(304).end();
  }

  res.set({
    'ETag': strongEtag,
    'Last-Modified': lastMod,
    'Cache-Control': 'private, max-age=60, stale-while-revalidate=300',
  });
  res.json(article);
});

// Disable Express's auto-etag (it emits weak ETags) if you manage strong ones yourself
app.set('etag', false);
```

Writes use the same header for **optimistic concurrency**: `PUT /articles/42` with `If-Match: "v7-<hash>"` — server returns `412 Precondition Failed` if the version no longer matches, preventing lost updates. If your upstream only exposes weak ETags (e.g. gzipped representations), you must materialise a separate strong resource identifier (a version column, row `xmin`, or a content hash) to use for `If-Match`. Never send `W/"..."` with `If-Match`.

### API Error Contracts (RFC 7807)

`application/problem+json` gives every error a machine-readable shape. Required fields are `type` (URI identifying the problem kind) and `title`; `status`, `detail`, `instance` are common; extension members are encouraged.

```typescript
// Consistent envelope
interface Problem {
  type: string;          // 'https://api.example.com/problems/validation'
  title: string;         // 'Your request parameters didn't validate.'
  status: number;        // 422
  detail?: string;       // human-readable specifics
  instance?: string;     // URI of THIS occurrence, usually the request path
  traceId?: string;      // correlation ID (W3C traceparent or X-Request-ID)
  errors?: Array<{ path: string; message: string }>; // extension
}

// Express 5 error middleware
app.use((err: unknown, req: Request, res: Response, _next: NextFunction) => {
  const traceId = (req as any).traceId;
  const base = { instance: req.originalUrl, traceId };

  if (err instanceof ZodError) {
    return res.status(422).type('application/problem+json').json({
      ...base, type: 'https://api.example.com/problems/validation',
      title: 'Validation failed', status: 422,
      errors: err.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
    } satisfies Problem);
  }
  if (err instanceof HttpError) {
    return res.status(err.status).type('application/problem+json').json({
      ...base, type: err.type ?? 'about:blank',
      title: err.message, status: err.status,
    } satisfies Problem);
  }
  logger.error({ err, traceId }, 'unhandled'); // log stack with traceId
  res.status(500).type('application/problem+json').json({
    ...base, type: 'about:blank', title: 'Internal Server Error', status: 500,
  } satisfies Problem);
});
```

**Correlation IDs**: accept inbound `X-Request-ID` / `traceparent`, generate one if absent, attach to the async-local-storage context (`AsyncLocalStorage` or `cls-hooked`), include in every log line, and echo back on the response. This is what lets on-call pivot from a user complaint to the exact log trail across services.

### OpenAPI / Contract-First Design

**Schema-first** (write OpenAPI YAML first, generate server stubs + client SDKs) vs **code-first** (decorate handlers/DTOs, generate OpenAPI at build time).

| Aspect | Schema-first | Code-first |
|--------|--------------|------------|
| Source of truth | `.yaml` file committed to repo | TypeScript types / decorators |
| Language neutrality | High — same spec drives any backend | Tied to the implementation language |
| Drift risk | Server must be kept in sync with spec | Low — generated from actual types |
| Review ergonomics | API diff visible as YAML diff in PR | Diffs spread across many files |
| Tooling | `openapi-generator`, Stoplight, Redocly | NestJS Swagger, `zod-to-openapi`, `@fastify/swagger` |

In TypeScript-heavy Node shops, the practical sweet spot is **validator-first**: define runtime schemas with `zod` or `typebox`, and *generate* OpenAPI from them — you get request validation, static types, and the spec from one declaration.

```typescript
// zod + @asteasolutions/zod-to-openapi
import { z } from 'zod';
import { extendZodWithOpenApi, OpenAPIRegistry } from '@asteasolutions/zod-to-openapi';
extendZodWithOpenApi(z);

const registry = new OpenAPIRegistry();

const CreateUser = z.object({
  email: z.string().email().openapi({ example: 'a@b.com' }),
  name: z.string().min(2),
}).openapi('CreateUser');

const User = CreateUser.extend({ id: z.string().uuid() }).openapi('User');

registry.registerPath({
  method: 'post', path: '/users',
  request: { body: { content: { 'application/json': { schema: CreateUser } } } },
  responses: {
    201: { description: 'Created', content: { 'application/json': { schema: User } } },
    422: { description: 'Validation failed' },
  },
});

// Same schema powers the handler at runtime
app.post('/users', async (req, res) => {
  const dto = CreateUser.parse(req.body);       // throws ZodError -> 422
  res.status(201).json(await users.create(dto));
});
```

`typebox` produces JSON Schema directly (fastify-native, zero-cost validation via AJV compilation). Fastify + typebox is the canonical high-perf contract-first setup.

**Contract testing**: consumers verify their expectations against the provider's spec in CI. Options: `dredd` / `schemathesis` (fuzz the spec against a running server), `pact` (consumer-driven contracts for microservices), or simply assert that handler responses `safeParse` against the response schema in integration tests. Catch spec drift *before* deployment, not in production.

### NestJS Specifics

The Nest request lifecycle maps cleanly onto the topics above:

| Concern | NestJS construct |
|---------|------------------|
| Idempotency key check | Global **middleware** or **interceptor** (needs Redis injection, so interceptor is easier) |
| Per-request validation | **Pipes** (`ValidationPipe` + `class-validator`, or `ZodValidationPipe`) |
| ETag / 304 short-circuit | **Interceptor** that inspects `If-None-Match` and returns `throw new HttpException(..., 304)` or `res.status(304).end()` |
| Timeouts on outbound calls | `HttpModule` with `timeout`/`maxRedirects`; wrap in `timeout(ms)` RxJS operator on `HttpService` calls |
| RFC 7807 error shape | Global **exception filter** (`@Catch()`) producing `application/problem+json` |
| Correlation ID propagation | Middleware that sets `AsyncLocalStorage` context; custom logger reads it |
| OpenAPI generation | `@nestjs/swagger` (code-first via `@ApiProperty`, `@ApiResponse`) or `nestjs-zod` + `zod-to-openapi` |
| Trust proxy | `app.set('trust proxy', 1)` on the underlying Express/Fastify instance |
| Retries / circuit breakers | Custom interceptor around `HttpService`, or `nestjs-circuit-breaker` / `opossum` |

```typescript
// Idempotency as a Nest interceptor
@Injectable()
export class IdempotencyInterceptor implements NestInterceptor {
  constructor(@Inject('REDIS') private readonly redis: Redis) {}
  async intercept(ctx: ExecutionContext, next: CallHandler): Promise<Observable<unknown>> {
    const req = ctx.switchToHttp().getRequest<Request>();
    if (!['POST', 'PATCH'].includes(req.method)) return next.handle();
    const key = req.header('idempotency-key');
    if (!key) return next.handle();
    // ... same logic as Express middleware above, but return `of(stored)` on cache hit
    return next.handle();
  }
}

// RFC 7807 filter
@Catch()
export class ProblemFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const res = host.switchToHttp().getResponse<Response>();
    const req = host.switchToHttp().getRequest<Request>();
    const status = exception instanceof HttpException ? exception.getStatus() : 500;
    res.status(status).type('application/problem+json').json({
      type: 'about:blank',
      title: exception instanceof Error ? exception.message : 'Unknown error',
      status, instance: req.originalUrl,
      traceId: (req as any).traceId,
    });
  }
}
```

### Oral Interview Questions

1. **Which HTTP methods are idempotent, and why does it matter for retries?**
   `GET`, `HEAD`, `OPTIONS`, `PUT`, `DELETE`, `TRACE`. Idempotent calls can be safely retried by clients, proxies, and load balancers without duplicating side effects — that's what makes automatic retry-with-backoff viable. `POST`/`PATCH` need idempotency keys to become retry-safe.

2. **What's the difference between 401 and 403?**
   `401 Unauthorized` means the request lacks valid authentication — the client should authenticate and retry. `403 Forbidden` means the caller is authenticated but not authorised; authenticating again will not help.

3. **In Express 4, why does an `async` route handler that throws "hang" the request? How does Express 5 change that?**
   Express 4's router only catches synchronous throws; rejected promises bypass it entirely and surface as `unhandledRejection`, so `next(err)` is never called and the client waits until its socket times out. Express 5 auto-forwards rejected promises to error middleware, eliminating the need for `asyncHandler` wrappers.

4. **When do you return 409 vs 422?**
   `409 Conflict` for state conflicts (version mismatch, duplicate unique key, resource already exists). `422 Unprocessable Entity` for semantically invalid payloads that parsed fine (bad business-rule values). Use `400` only for malformed syntax the server couldn't parse.

5. **What is an idempotency key and how would you implement it?**
   A client-supplied opaque token on non-idempotent writes. Server stores `(key, request fingerprint, response)` atomically with TTL; retries with the same key return the stored response, retries with a mismatched payload return `422`, and in-flight duplicates return `409`. The idem record must be written in the same transaction as the business effect.

6. **Why is setting HTTP timeouts a correctness concern, not just performance?**
   Without timeouts a slow upstream pins sockets and event-loop work indefinitely, cascading into health-check failures and pod restarts across the fleet. Timeouts bound the blast radius and make retries + circuit breakers possible.

7. **Why exponential backoff *with jitter*?**
   Plain exponential backoff synchronises retries across clients (all fire at `t=1s`, `t=2s`, ...), hammering the recovering service in waves. Random jitter spreads retries uniformly across the window and breaks the synchronisation.

8. **What does `app.set('trust proxy', 1)` actually do?**
   It tells Express to trust one upstream proxy hop, causing `req.ip`, `req.ips`, `req.protocol`, and `req.secure` to be derived from `X-Forwarded-For` / `X-Forwarded-Proto` instead of the TCP socket. Without it, rate limiters and audit logs attribute every request to the load balancer's IP.

9. **ETag vs Last-Modified — when would you pick one?**
   `Last-Modified` is second-granular and cheap when you already have a timestamp; it's too coarse if the resource can change multiple times per second. `ETag` is an arbitrary fingerprint — use it for sub-second changes, computed payloads, or when you also want optimistic concurrency via `If-Match` on writes.

10. **What is RFC 7807 and why use it?**
    A standard `application/problem+json` envelope (`type`, `title`, `status`, `detail`, `instance`) for HTTP error responses. Consistent shape means clients, gateways, and observability tools parse errors uniformly; extension members carry domain-specific details like field-level validation errors or trace IDs.

### Hands-On Drills

1. **Implement an idempotency middleware** in Express 5 backed by Redis: accept `Idempotency-Key` on `POST`/`PATCH`, deduplicate retries, return `409` for in-flight duplicates, `422` for payload mismatches, and replay the stored response (status + headers + body) on hits. Cover it with Vitest tests that simulate double-submit and payload-swap scenarios.

2. **Add proper ETag + conditional-GET support** to `GET /articles/:id`: generate a weak ETag from `(version, updatedAt)`, short-circuit with `304` on matching `If-None-Match` or `If-Modified-Since`, and add `PUT /articles/:id` that requires `If-Match` and returns `412 Precondition Failed` on version drift.

3. **Build a resilient outbound HTTP client** on top of `undici` or `axios`: per-request connect/response/total deadlines, exponential backoff with full jitter, retry only on retryable status codes + network errors, honour `Retry-After`, and expose metrics (attempts, success, latency). Wire in `opossum` for circuit breaking and integrate with an `AsyncLocalStorage`-based correlation-ID propagator.

4. **Convert an existing Express handler set to contract-first with zod**: write `zod` schemas for request/response, swap `JSON.parse` + ad-hoc checks for `schema.parse`, generate OpenAPI via `@asteasolutions/zod-to-openapi`, serve Swagger UI, and add a CI step that runs `schemathesis` against the generated spec.

5. **Write a global NestJS exception filter** that produces RFC 7807 responses: map `ZodError` -> `422` with field-level `errors`, `HttpException` -> status + title, everything else -> `500` with a redacted title; attach `traceId` from `AsyncLocalStorage`; verify with e2e tests that every error path returns `application/problem+json` with the required fields.

---
