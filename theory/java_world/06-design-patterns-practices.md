# Design Patterns & Engineering Practices — Senior Engineer Interview Preparation

---

## 1. SOLID Principles

### S — Single Responsibility Principle

A class should have only one reason to change.

```java
// BAD — handles business logic, persistence, and notification
public class OrderService {
    public void createOrder(Order order) {
        validate(order);
        calculateTax(order);
        entityManager.persist(order);        // persistence concern
        emailService.sendConfirmation(order); // notification concern
    }
}

// GOOD — separated concerns
public class OrderService {
    private final OrderRepository repository;
    private final ApplicationEventPublisher events;

    public void createOrder(Order order) {
        validate(order);
        calculateTax(order);
        repository.save(order);
        events.publishEvent(new OrderCreatedEvent(order)); // let listeners handle side effects
    }
}
```

### O — Open/Closed Principle

Open for extension, closed for modification. Use abstraction and polymorphism.

```java
// BAD — modify this class every time a new payment type is added
public class PaymentProcessor {
    public void process(Payment payment) {
        if (payment.getType() == CREDIT_CARD) { ... }
        else if (payment.getType() == PAYPAL) { ... }
        else if (payment.getType() == CRYPTO) { ... } // new addition = modification
    }
}

// GOOD — extend by adding new implementations
public interface PaymentHandler {
    boolean supports(PaymentType type);
    void process(Payment payment);
}

@Component
public class PaymentProcessor {
    private final List<PaymentHandler> handlers;

    public void process(Payment payment) {
        handlers.stream()
            .filter(h -> h.supports(payment.getType()))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentException(payment.getType()))
            .process(payment);
    }
}
```

### L — Liskov Substitution Principle

Subtypes must be substitutable for their base types without altering correctness.

```java
// VIOLATION — Square changes Rectangle's behavior
class Rectangle {
    void setWidth(int w) { this.width = w; }
    void setHeight(int h) { this.height = h; }
    int area() { return width * height; }
}

class Square extends Rectangle {
    @Override void setWidth(int w) { this.width = w; this.height = w; } // breaks contract
}

// Rectangle r = new Square(); r.setWidth(5); r.setHeight(3);
// Expected area: 15. Actual: 9. LSP violated.

// MODERN FIX — sealed hierarchy with records makes substitutability compiler-checked
public sealed interface Shape permits Rectangle, Square, Circle {
    double area();
}
public record Rectangle(double w, double h) implements Shape {
    public double area() { return w * h; }
}
public record Square(double side) implements Shape {
    public double area() { return side * side; }
}
public record Circle(double r) implements Shape {
    public double area() { return Math.PI * r * r; }
}
```

### I — Interface Segregation Principle

Don't force clients to depend on methods they don't use.

```java
// BAD — one fat interface
public interface UserService {
    User findById(Long id);
    List<User> findAll();
    void createUser(User user);
    void deleteUser(Long id);
    void sendWelcomeEmail(User user);
    void generateReport();
}

// GOOD — segregated
public interface UserReader { User findById(Long id); List<User> findAll(); }
public interface UserWriter { void createUser(User user); void deleteUser(Long id); }
```

### D — Dependency Inversion Principle

High-level modules should not depend on low-level modules. Both should depend on abstractions.

```java
// BAD — direct dependency on implementation
public class OrderService {
    private MySQLOrderRepository repo = new MySQLOrderRepository(); // tightly coupled
}

// GOOD — depend on abstraction
public class OrderService {
    private final OrderRepository repo; // interface

    public OrderService(OrderRepository repo) { // injected
        this.repo = repo;
    }
}
```

---

## 2. Essential Design Patterns

### Creational

**Builder** — complex object construction:
```java
Order order = Order.builder()
    .customerId(customerId)
    .item(new OrderItem("SKU-1", 2))
    .item(new OrderItem("SKU-2", 1))
    .shippingAddress(address)
    .build(); // validates all required fields
```

**Factory Method / Abstract Factory** — delegate instantiation:
```java
public interface NotificationFactory {
    Notification create(NotificationType type);
}

// Spring: use a Map of strategy beans
@Component
public class NotificationFactory {
    private final Map<NotificationType, NotificationSender> senders;

    public NotificationFactory(List<NotificationSender> senderList) {
        this.senders = senderList.stream()
            .collect(Collectors.toMap(NotificationSender::getType, Function.identity()));
    }
}
```

**Singleton** — in Spring, beans are singleton-scoped by default. Avoid implementing manually; let the container manage it. When you truly must hand-roll one (no DI container, library code), **use an `enum`**:
```java
// Effective Java item 3 — the simplest, serialization-safe, reflection-proof singleton
public enum ConnectionPool {
    INSTANCE;

    private final HikariDataSource ds = buildDataSource();
    public Connection borrow() throws SQLException { return ds.getConnection(); }
}

// AVOID the classic double-checked locking boilerplate unless you have a very specific
// reason — enum gets it right for free and survives deserialization without holes.
```

| Singleton approach | Thread-safe? | Serialization-safe? | Reflection-safe? |
|--------------------|:-:|:-:|:-:|
| Eager static field | Yes | No (writes new instance) | No |
| Synchronized getInstance | Yes (slow) | No | No |
| Double-checked locking | Yes (tricky, needs `volatile`) | No | No |
| Initialization-on-demand holder | Yes | No | No |
| **Enum** | **Yes** | **Yes** | **Yes** |

### Structural

**Decorator** — add behavior dynamically:
```java
// Java I/O is the classic decorator example
InputStream is = new BufferedInputStream(
    new GZIPInputStream(
        new FileInputStream("data.gz")));

// Real-world: adding cross-cutting concerns
public class LoggingOrderService implements OrderService {
    private final OrderService delegate;

    @Override
    public Order createOrder(OrderRequest req) {
        log.info("Creating order: {}", req);
        Order order = delegate.createOrder(req);
        log.info("Order created: {}", order.getId());
        return order;
    }
}
```

**Adapter** — convert interface to another:
```java
// Adapting a legacy payment gateway to your interface
public class LegacyPaymentAdapter implements PaymentGateway {
    private final OldPaymentSystem legacy;

    @Override
    public PaymentResult charge(Money amount, Card card) {
        // Translate to legacy API format
        LegacyResponse resp = legacy.processPayment(
            amount.cents(), card.number(), card.expiry());
        return mapToPaymentResult(resp);
    }
}
```

**Proxy** — control access (Spring AOP, lazy loading, caching, security checks):
```java
// Dynamic proxy — used under the hood by Spring AOP, MyBatis mappers, Feign clients
PaymentGateway proxy = (PaymentGateway) Proxy.newProxyInstance(
    PaymentGateway.class.getClassLoader(),
    new Class<?>[]{PaymentGateway.class},
    (p, method, args) -> {
        long start = System.nanoTime();
        try { return method.invoke(target, args); }
        finally { metrics.record(method.getName(), System.nanoTime() - start); }
    });

// Spring's @Transactional, @Cacheable, @PreAuthorize all work via proxies
// Cglib subclass proxy for classes, JDK dynamic proxy for interfaces.
```

**Facade** — a single simplified entry point in front of a subsystem (classic use: wrapping a noisy third-party SDK behind one service interface). Often confused with Adapter — Facade simplifies, Adapter translates.

### Behavioral

**Strategy** — interchangeable algorithms:
```java
public interface PricingStrategy {
    BigDecimal calculate(Order order);
}

@Component("standard")
class StandardPricing implements PricingStrategy { ... }

@Component("premium")
class PremiumPricing implements PricingStrategy { ... }

@Component("wholesale")
class WholesalePricing implements PricingStrategy { ... }

// Select at runtime
@Service
public class PricingService {
    private final Map<String, PricingStrategy> strategies;

    public BigDecimal calculatePrice(Order order, String tier) {
        return strategies.get(tier).calculate(order);
    }
}

// MODERN — when the strategy is a single method, drop the class hierarchy
// and use a functional interface (or Function / ToIntBiFunction / etc.)
Map<String, Function<Order, BigDecimal>> strategies = Map.of(
    "standard",  o -> o.subtotal(),
    "premium",   o -> o.subtotal().multiply(new BigDecimal("0.90")),
    "wholesale", o -> o.subtotal().multiply(new BigDecimal("0.75")));

BigDecimal price = strategies.getOrDefault(tier, o -> o.subtotal()).apply(order);
```

**Observer / Event-Driven** (Spring Events):
```java
// Publisher
applicationEventPublisher.publishEvent(new OrderShippedEvent(orderId));

// Listeners (decoupled)
@EventListener
public void handleOrderShipped(OrderShippedEvent event) {
    notificationService.notifyCustomer(event.getOrderId());
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void afterOrderShipped(OrderShippedEvent event) {
    // Only runs if transaction commits successfully
    analyticsService.trackShipment(event.getOrderId());
}
```

**Template Method** — define algorithm skeleton, subclasses fill in steps:
```java
public abstract class DataImporter {
    public final void importData() {
        List<String> rawData = readSource();
        List<Record> records = parse(rawData);
        validate(records);
        save(records);
    }

    protected abstract List<String> readSource();
    protected abstract List<Record> parse(List<String> data);
    // validate() and save() have default implementations
}
```

**Chain of Responsibility** — pass request through handler chain:
```java
// Servlet Filters, Spring Security Filter Chain, validation chains
public interface ValidationHandler {
    void setNext(ValidationHandler next);
    void validate(Order order);
}

// Each handler validates one aspect and passes to next — or compose with function chains:
Consumer<Order> pipeline = ((Consumer<Order>) this::checkInventory)
    .andThen(this::checkCreditLimit)
    .andThen(this::checkShippingAddress);
pipeline.accept(order);
```

**Command** — encapsulate a request as an object (undo/redo, queues, schedulers):
```java
public interface Command {
    void execute();
    default void undo() { throw new UnsupportedOperationException(); }
}

public record PlaceOrderCommand(OrderService svc, OrderRequest req) implements Command {
    public void execute() { svc.place(req); }
}

// Commands serialize well to a message queue (Kafka, SQS) — this is the core
// of CQRS write-side handling and request/reply RPC frameworks.
Deque<Command> history = new ArrayDeque<>();
history.push(cmd);
cmd.execute();
// later: history.pop().undo();
```

### Modern replacement: sealed types + pattern matching

The **Visitor** pattern is largely obsolete in Java 21+. Sealed hierarchies give the compiler exhaustiveness guarantees without double-dispatch ceremony:
```java
public sealed interface PaymentEvent
    permits Authorized, Captured, Refunded, Failed {}

public record Authorized(String txId, Money amount) implements PaymentEvent {}
public record Captured(String txId, Instant at)       implements PaymentEvent {}
public record Refunded(String txId, Money amount)     implements PaymentEvent {}
public record Failed(String txId, String reason)      implements PaymentEvent {}

// Exhaustive — add a new permit and the compiler flags every switch
String describe(PaymentEvent e) {
    return switch (e) {
        case Authorized(var id, var amt) -> "Auth " + id + " for " + amt;
        case Captured(var id, var at)    -> "Captured " + id + " at " + at;
        case Refunded(var id, var amt)   -> "Refunded " + amt + " on " + id;
        case Failed(var id, var why)     -> "Failed " + id + ": " + why;
    };
}
```
This replaces the Visitor pattern's double-dispatch machinery with a single exhaustive switch. For Strategy with a fixed, closed set of implementations, sealed + pattern matching is now often cleaner than polymorphism.

### Modern Observer: `java.util.concurrent.Flow`

`java.util.Observer` / `Observable` were **deprecated in Java 9** and fully removed in later releases. Use the Reactive Streams-compatible `Flow` API (or Project Reactor / RxJava for a richer operator set):
```java
SubmissionPublisher<OrderEvent> publisher = new SubmissionPublisher<>();
publisher.subscribe(new Flow.Subscriber<>() {
    Flow.Subscription sub;
    public void onSubscribe(Flow.Subscription s) { (this.sub = s).request(1); }
    public void onNext(OrderEvent e) { handle(e); sub.request(1); }
    public void onError(Throwable t) { log.error("stream error", t); }
    public void onComplete() { log.info("done"); }
});
publisher.submit(new OrderEvent(...));
```
For in-process pub/sub in Spring apps, `ApplicationEventPublisher` remains the pragmatic choice.

---

## 3. Resilience & Error Handling Patterns

### Retry with Idempotency

```java
// Idempotent endpoint design
@PostMapping("/payments")
public ResponseEntity<Payment> createPayment(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody PaymentRequest request) {

    // Check if already processed
    Optional<Payment> existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) return ResponseEntity.ok(existing.get());

    Payment payment = processPayment(request, idempotencyKey);
    return ResponseEntity.status(CREATED).body(payment);
}
```

### Graceful Degradation

When a dependency fails, provide a reduced but functional experience:
```java
@CircuitBreaker(name = "recommendations", fallbackMethod = "defaultRecs")
public List<Product> getRecommendations(Long userId) {
    return recommendationService.getPersonalized(userId);
}

private List<Product> defaultRecs(Long userId, Exception ex) {
    return productRepository.findTopSelling(10); // Generic fallback
}
```

### Bulkhead Pattern in Practice

Isolate critical and non-critical operations so a failure in one doesn't affect the other:
```yaml
# Separate thread pools
resilience4j.thread-pool-bulkhead:
  instances:
    payment:
      maxThreadPoolSize: 20     # Critical — more resources
      coreThreadPoolSize: 10
      queueCapacity: 50
    analytics:
      maxThreadPoolSize: 5      # Non-critical — limited resources
      coreThreadPoolSize: 2
      queueCapacity: 10
```

---

## 4. Testing Strategy

### Test Pyramid

```
         ╱╲         E2E Tests (few, slow, expensive)
        ╱──╲
       ╱────╲       Integration Tests (moderate)
      ╱──────╲
     ╱────────╲     Unit Tests (many, fast, cheap)
    ╱──────────╲
```

### Unit Testing Best Practices

```java
// Arrange-Act-Assert pattern
@Test
void shouldCalculateOrderTotal_withDiscount() {
    // Arrange
    Order order = new Order(List.of(
        new LineItem("SKU-1", 2, Money.of(10.00)),
        new LineItem("SKU-2", 1, Money.of(25.00))
    ));
    var calculator = new PriceCalculator(new TenPercentDiscount());

    // Act
    Money total = calculator.calculate(order);

    // Assert
    assertThat(total).isEqualTo(Money.of(40.50)); // 45 - 10%
}

// Parameterized tests
@ParameterizedTest
@CsvSource({
    "PENDING, true",
    "SHIPPED, false",
    "DELIVERED, false",
    "CANCELLED, false"
})
void shouldBeCancellableOnlyWhenPending(OrderStatus status, boolean expected) {
    Order order = new Order(status);
    assertThat(order.isCancellable()).isEqualTo(expected);
}
```

### Integration Testing with Testcontainers

```java
@SpringBootTest
@Testcontainers
class OrderServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test
    void shouldCreateOrderAndPublishEvent() {
        // Full integration test with real DB, Kafka, and Redis
    }
}
```

### Contract Testing (Consumer-Driven)

```java
// Spring Cloud Contract — producer side
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.app:payment-service:+:stubs:8090",
    stubsMode = StubsMode.LOCAL
)
class OrderServiceContractTest {
    @Test
    void shouldCallPaymentService() {
        // Stub runner provides mock of payment service
        // based on contract defined by payment service team
    }
}
```

---

## 5. Code Quality & Practices

### Clean Code Principles

- **Naming**: Classes = nouns (`OrderService`), methods = verbs (`calculateTotal`), booleans = predicates (`isActive`, `hasPermission`).
- **Method size**: Should fit on one screen. Extract when you need a comment to explain a block.
- **Parameters**: Prefer 0-2 parameters. Use parameter objects for more.
- **Fail fast**: Validate inputs at the boundary. Throw early.
- **Immutability by default**: Use `final`, records, unmodifiable collections.

### DRY, KISS, YAGNI — and when to break them

| Principle | Meaning | When it misfires |
|-----------|---------|-------------------|
| **DRY** (Don't Repeat Yourself) | Knowledge has a single source of truth. | Premature generalization: two lines that look alike but represent different concepts shouldn't be merged. **Rule of three** — duplicate twice, abstract on the third occurrence. |
| **KISS** (Keep It Simple, Stupid) | Prefer the simplest solution that works. | "Clever" one-liners that require five minutes to read. Optimize for the reader. |
| **YAGNI** (You Aren't Gonna Need It) | Don't build for a future requirement you haven't been given. | Speculative extension points — interfaces with one implementation, factories with one product. Add the seam when the second use case appears. |

> Duplication is cheaper than the wrong abstraction. — Sandi Metz

### Law of Demeter ("Principle of Least Knowledge")

Talk only to your immediate collaborators. Every `.`-chain past the first increases coupling to internal structure.
```java
// BAD — reaching through three objects ("train wreck")
BigDecimal total = order.getCustomer().getAccount().getBalance().subtract(amount);

// GOOD — tell, don't ask: ask the object that owns the data to do the work
order.debitCustomerBalance(amount);
```
Signs you're violating LoD: long getter chains, tests that require deep mocking (`when(a.getB().getC().getD()).thenReturn(...)`), ripple-effect refactors when an inner type changes.

Note: fluent APIs (`Stream`, builders) return `this`-like types and are not LoD violations — all calls target the same logical object.

### Composition over Inheritance

Favor "has-a" over "is-a". Inheritance is a tight, compile-time bind; composition defers the choice to runtime and lets you change it per-instance.
```java
// Inheritance: Stack extends Vector — now every Vector method pollutes Stack's API
// (the mistake the JDK admits to in Effective Java).

// Composition: wrap and delegate only what belongs.
public final class Stack<T> {
    private final Deque<T> backing = new ArrayDeque<>();
    public void push(T t) { backing.push(t); }
    public T pop()        { return backing.pop(); }
    public T peek()       { return backing.peek(); }
    public boolean isEmpty() { return backing.isEmpty(); }
}
```
Rules of thumb:
- Use inheritance only when there is a genuine `is-a` subtype relationship **and** the superclass was designed for extension (documented via `@implNote`, non-final methods, protected hooks).
- Records and sealed types make data hierarchies explicit without the fragility of open inheritance.
- Strategy, Decorator, Adapter, Bridge — all are forms of "prefer composition".

### Logging Best Practices

```java
// DO: Structured logging with context
log.info("Order created", kv("orderId", order.getId()),
    kv("customerId", order.getCustomerId()), kv("total", order.getTotal()));

// DON'T: String concatenation (always evaluated, even if level is off)
log.debug("Processing order " + orderId + " for customer " + customerId);

// DO: Use SLF4J parameterized logging
log.debug("Processing order {} for customer {}", orderId, customerId);

// Log levels:
// ERROR — something broke, needs immediate attention (alerts fire)
// WARN  — unexpected but recoverable (monitor trends)
// INFO  — business events (order created, payment processed)
// DEBUG — technical details for troubleshooting
// TRACE — very detailed, usually only in development
```

### Defensive Programming

```java
// Input validation at API boundary
public record CreateOrderRequest(
    @NotNull Long customerId,
    @NotEmpty List<@Valid OrderItemRequest> items,
    @NotNull @Valid AddressRequest shippingAddress
) {}

// Null safety
// Option 1: Optional return types
public Optional<User> findByEmail(String email) { ... }

// Option 2: @Nullable / @NonNull annotations
public @NonNull User findById(@NonNull Long id) { ... }

// NEVER: Optional as parameter, Optional in collections, Optional.get() without check
```

---

## 6. Domain-Driven Design Building Blocks

DDD gives you a vocabulary for modeling business rules inside your code. At the tactical level, four building blocks matter most.

| Block | Identity | Mutability | Purpose |
|-------|----------|-----------|---------|
| **Value Object** | none (equality by value) | immutable | Describes something (`Money`, `Address`, `DateRange`) |
| **Entity** | stable ID (equality by id) | mutable state over time | Something with a lifecycle (`Order`, `User`) |
| **Aggregate** | root entity's ID | mutates only through root | Consistency boundary — one transaction, one aggregate |
| **Repository** | n/a | n/a | Collection-like interface to persist/load one aggregate type |

```java
// Value object — Java records are purpose-built for this
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.scale() > currency.getDefaultFractionDigits())
            throw new IllegalArgumentException("too many decimals");
    }
    public Money add(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException();
        return new Money(amount.add(other.amount), currency);
    }
}

// Aggregate root — all invariants enforced here; outside code cannot touch line items directly
public class Order {
    private final OrderId id;
    private final List<OrderLine> lines = new ArrayList<>();
    private OrderStatus status = OrderStatus.DRAFT;

    public void addLine(ProductId p, int qty, Money unitPrice) {
        if (status != OrderStatus.DRAFT) throw new IllegalStateException("locked");
        lines.add(new OrderLine(p, qty, unitPrice));
    }
    public Money total() {
        return lines.stream()
            .map(OrderLine::subtotal)
            .reduce(Money.zero(CURRENCY), Money::add);
    }
    public List<OrderLine> lines() { return List.copyOf(lines); } // defensive copy
}

// Repository interface lives in the domain; implementation lives in infrastructure
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    void save(Order order);
}
```

**Domain events** — record facts that have happened (`OrderPlaced`, `PaymentCaptured`). Publish them from the aggregate; other aggregates react asynchronously. Keeps transactions small and aggregates loosely coupled.

---

## 7. Hexagonal Architecture (Ports & Adapters)

Goal: the domain depends on **nothing** — not Spring, not JPA, not HTTP. Everything external is an "adapter" that plugs into a "port" (interface) defined by the domain.

```
          ┌──────────────────────── adapters (in) ────────────────────────┐
          │  REST controller │ Kafka consumer │ CLI │ Scheduled job       │
          └───────┬────────────────┬───────────────┬──────────┬──────────┘
                  ▼                ▼               ▼          ▼
                        ┌──────── driving ports ─────┐
                        │   PlaceOrderUseCase        │
                        │   CancelOrderUseCase       │
                        └────────────┬───────────────┘
                                     ▼
                   ┌──── DOMAIN (pure Java, no framework) ─────┐
                   │   Order, Money, OrderStatus (aggregates,  │
                   │   value objects, domain services)         │
                   └────────────┬───────────────┬──────────────┘
                                ▼               ▼
                        ┌──── driven ports ────────┐
                        │ OrderRepository          │
                        │ PaymentGateway           │
                        │ NotificationSender       │
                        └───────┬──────────┬───────┘
                                ▼          ▼
          ┌──────────────────── adapters (out) ──────────────────────┐
          │  JPA repo │ Stripe client │ SMTP sender │ Kafka producer │
          └──────────────────────────────────────────────────────────┘
```

```java
// Driving port — what the application can do (use case)
public interface PlaceOrderUseCase {
    OrderId place(PlaceOrderCommand cmd);
}

// Driven port — what the application needs
public interface OrderRepository {
    void save(Order order);
}

// Application service implements the driving port, depends only on driven ports
public class PlaceOrderService implements PlaceOrderUseCase {
    private final OrderRepository orders;
    private final PaymentGateway payments;

    public PlaceOrderService(OrderRepository orders, PaymentGateway payments) {
        this.orders = orders; this.payments = payments;
    }

    public OrderId place(PlaceOrderCommand cmd) {
        Order order = Order.draft(cmd.customerId(), cmd.lines());
        payments.authorize(order.total(), cmd.paymentMethod());
        orders.save(order);
        return order.id();
    }
}

// Adapter — sits in the infrastructure module, depends inward only
@Repository
public class JpaOrderRepository implements OrderRepository {
    private final OrderJpaRepository jpa;
    public void save(Order order) { jpa.save(OrderEntity.fromDomain(order)); }
}
```

**Why interviewers love it**: it answers "how do you keep business logic testable" (just `new PlaceOrderService(mockRepo, mockGateway)` — no Spring context), and "how do you swap infrastructure" (replace the adapter, domain doesn't notice).

Related: **Onion / Clean Architecture** — same principle, different diagram. **Vertical Slice Architecture** — slice by feature rather than layer, popular in CQRS/event-sourced codebases.

---

## 8. Dependency Injection Patterns

| Style | When to use | Notes |
|-------|-------------|-------|
| **Constructor injection** | Always preferred for required dependencies. | Fields can be `final`. Object is never in a half-constructed state. Easy to unit test — just `new`. |
| **Setter injection** | Optional collaborators that have sensible defaults. | Weakens immutability; prefer a config object instead. |
| **Field injection (`@Autowired` on fields)** | Avoid. | Impossible to test without reflection, hides dependencies, can't be `final`. |
| **Method injection (`@Lookup`)** | Singleton needs prototype-scoped collaborator. | Rare; usually a smell. |

```java
// PREFERRED — constructor injection with a single constructor is picked up by Spring
// without @Autowired (since 4.3). Lombok @RequiredArgsConstructor makes it one line.
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository repository;
    private final PaymentGateway payments;
    private final ApplicationEventPublisher events;
}

// Ambiguity resolution when multiple beans implement the same port
@Service
public class OrderService {
    private final PaymentGateway stripe;
    public OrderService(@Qualifier("stripeGateway") PaymentGateway stripe) {
        this.stripe = stripe;
    }
}

// Conditional wiring
@Bean @ConditionalOnProperty(name = "features.fraud-check", havingValue = "true")
FraudCheck liveFraudCheck() { return new LiveFraudCheck(); }

@Bean @ConditionalOnMissingBean(FraudCheck.class)
FraudCheck noopFraudCheck() { return order -> FraudResult.clean(); }
```

**Service Locator** is the anti-pattern twin of DI — objects pull their dependencies from a global registry. It hides the dependency graph and defeats compile-time wiring checks. If you see `ApplicationContextAware` being used to fetch collaborators, that's a refactor target.

---

## 9. Common Anti-Patterns

| Anti-pattern | Symptoms | Refactor toward |
|--------------|---------|-----------------|
| **God Class / Blob** | One class with 1000+ lines, dozens of fields, mixed concerns (persistence + HTTP + rules). | SRP — extract collaborators by axis of change. Often the entry point to a bounded context rewrite. |
| **Spaghetti code** | Control flow jumps in and out of conditionals; no clear layering; shared mutable state. | Define clear layers (use case → domain → infra), extract methods, replace flags with polymorphism. |
| **Copy-paste programming** | Same 20-line block in five classes, each with a tiny tweak. | Apply DRY, but only after you see the **real** shared abstraction (rule of three). |
| **Primitive obsession** | `String customerId`, `long amountCents`, `int status` everywhere. | Introduce value objects (`CustomerId`, `Money`, `OrderStatus` enum). |
| **Anemic domain model** | Entities are pure getters/setters; all logic lives in `*Service` classes. | Push behavior into the aggregate; services orchestrate, don't compute. |
| **Feature envy** | Method in class A reads a chain of data from class B to make a decision. | Move the method to B (tell, don't ask — Law of Demeter). |
| **Shotgun surgery** | A single conceptual change forces edits in many unrelated files. | Locality of behavior; cohesive modules; vertical slices. |
| **Long parameter list** | `process(a, b, c, d, e, f, g)`. | Parameter object / record / builder. |
| **Magic numbers & strings** | `if (status == 3)`. | Named constants or enums. |
| **Exception swallowing** | `catch (Exception e) {}` or `catch + log + continue`. | Rethrow as domain-meaningful exception, or let it propagate. Never empty-catch. |
| **Reinventing the wheel** | Hand-rolled thread pool, home-grown caching, custom JSON parser. | Use the standard library or a vetted library. |
| **Premature optimization** | Micro-tuning before profiling; obscure code for a 2 % gain. | Measure first (JMH, async-profiler); optimize the hot path only. |
| **Singleton abuse** | Global mutable state masquerading as a service. | Prefer injected, scoped beans. True singletons: `enum`. |

---

## 10. Concurrency Patterns in Practice

### Producer-Consumer with BlockingQueue

```java
private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(1000);

// Producer
public void submitTask(Task task) {
    if (!queue.offer(task, 5, TimeUnit.SECONDS)) {
        throw new TaskQueueFullException();
    }
}

// Consumer (multiple threads)
@PostConstruct
public void startConsumers() {
    for (int i = 0; i < numConsumers; i++) {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Task task = queue.poll(1, TimeUnit.SECONDS);
                if (task != null) process(task);
            }
        });
    }
}
```

### Read-Write Lock

```java
private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
private Map<String, Config> configCache = new HashMap<>();

public Config getConfig(String key) {
    rwLock.readLock().lock();  // Multiple concurrent readers OK
    try {
        return configCache.get(key);
    } finally {
        rwLock.readLock().unlock();
    }
}

public void updateConfig(String key, Config config) {
    rwLock.writeLock().lock();  // Exclusive access
    try {
        configCache.put(key, config);
    } finally {
        rwLock.writeLock().unlock();
    }
}
```

### StampedLock (Java 8+, optimistic reading)

```java
private final StampedLock lock = new StampedLock();

public Config getConfig(String key) {
    long stamp = lock.tryOptimisticRead(); // No locking!
    Config config = configCache.get(key);
    if (!lock.validate(stamp)) { // Check if a write happened
        stamp = lock.readLock(); // Fall back to read lock
        try { config = configCache.get(key); }
        finally { lock.unlockRead(stamp); }
    }
    return config;
}
```

---

## 11. Senior-Level Behavioral Questions (Technical)

**Q: Describe a time you made a significant architecture decision. What trade-offs did you consider?**
Framework: Describe the problem, alternatives considered (at least 3), criteria for decision (performance, team expertise, time-to-market, maintainability), the decision and its rationale, outcome and what you'd do differently.

**Q: How do you approach technical debt?**
Track it visibly (tickets, ADRs). Categorize by impact and effort. Allocate 15-20% of sprint capacity. Address it when you're already modifying that area ("boy scout rule"). Communicate business impact to stakeholders.

**Q: How do you ensure code quality across a team?**
Code reviews (pair programming for complex work), automated testing (CI fails on coverage drop), static analysis (SonarQube, SpotBugs), architecture decision records (ADRs), style guides, internal tech talks, and leading by example.

**Q: How do you handle disagreements on technical approach?**
Listen first, understand their concerns. Present trade-offs objectively. Use data/benchmarks when available. Time-box discussions. If no consensus, the person closest to the problem decides. Document the decision (ADR). Disagree and commit.
