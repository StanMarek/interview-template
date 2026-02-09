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

**Singleton** — in Spring, beans are singleton-scoped by default. Avoid implementing manually; let the container manage it.

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

**Proxy** — control access (Spring AOP, lazy loading, caching, security checks).

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

// Each handler validates one aspect and passes to next
```

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
- **Don't Repeat Yourself (DRY)**: But premature abstraction is worse than duplication. Rule of three: duplicate twice, then abstract.
- **Fail fast**: Validate inputs at the boundary. Throw early.
- **Immutability by default**: Use `final`, records, unmodifiable collections.

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

## 6. Concurrency Patterns in Practice

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

## 7. Senior-Level Behavioral Questions (Technical)

**Q: Describe a time you made a significant architecture decision. What trade-offs did you consider?**
Framework: Describe the problem, alternatives considered (at least 3), criteria for decision (performance, team expertise, time-to-market, maintainability), the decision and its rationale, outcome and what you'd do differently.

**Q: How do you approach technical debt?**
Track it visibly (tickets, ADRs). Categorize by impact and effort. Allocate 15-20% of sprint capacity. Address it when you're already modifying that area ("boy scout rule"). Communicate business impact to stakeholders.

**Q: How do you ensure code quality across a team?**
Code reviews (pair programming for complex work), automated testing (CI fails on coverage drop), static analysis (SonarQube, SpotBugs), architecture decision records (ADRs), style guides, internal tech talks, and leading by example.

**Q: How do you handle disagreements on technical approach?**
Listen first, understand their concerns. Present trade-offs objectively. Use data/benchmarks when available. Time-box discussions. If no consensus, the person closest to the problem decides. Document the decision (ADR). Disagree and commit.
