# Testing — Senior Engineer Interview Preparation

---

## 1. Testing Pyramid

### The Classic Pyramid

The testing pyramid (Mike Cohn) defines three layers of automated tests, each with different cost/speed/confidence trade-offs:

```
        /  E2E  \          Few, slow, expensive, high confidence
       /----------\
      / Integration \      Moderate number, moderate speed
     /----------------\
    /    Unit Tests     \  Many, fast, cheap, low integration confidence
   /____________________\
```

| Layer       | Speed      | Cost to Write/Maintain | Confidence in Integration | Typical Count |
|-------------|------------|------------------------|---------------------------|---------------|
| Unit        | < 10ms     | Low                    | Low                       | Thousands     |
| Integration | 100ms-10s  | Medium                 | Medium-High               | Hundreds      |
| E2E         | 10s-mins   | High                   | High                      | Tens          |

### Unit Tests

Test a single class or method in isolation. Dependencies are mocked or stubbed.

```java
@Test
void shouldCalculateDiscountForPremiumCustomer() {
    // Arrange
    var calculator = new PriceCalculator(new FixedTaxStrategy(0.2));
    var customer = Customer.premium("Alice");

    // Act
    var price = calculator.calculateFinalPrice(100.0, customer);

    // Assert
    assertThat(price).isEqualTo(96.0); // 20% tax, 20% premium discount
}
```

**When to use:** Pure business logic, algorithms, data transformations, validation rules.

### Integration Tests

Test the interaction between multiple components or with external systems (database, message broker, HTTP API).

```java
@SpringBootTest
@Testcontainers
class OrderRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldPersistAndRetrieveOrder() {
        var order = new Order("ORD-001", List.of(new LineItem("Widget", 2, 9.99)));
        orderRepository.save(order);

        var found = orderRepository.findById("ORD-001");
        assertThat(found).isPresent();
        assertThat(found.get().getLineItems()).hasSize(1);
    }
}
```

**When to use:** Repository/DAO testing, API endpoint testing, message producer/consumer testing, cache integration.

### End-to-End (E2E) Tests

Test the full system from the user's perspective, typically through the UI or API gateway.

```java
@Test
void shouldCompleteCheckoutFlow() {
    // Selenium/Playwright style
    loginPage.loginAs("customer@test.com", "password");
    catalogPage.addToCart("Widget");
    cartPage.proceedToCheckout();
    checkoutPage.enterShippingAddress(testAddress);
    checkoutPage.selectPaymentMethod("credit-card");
    checkoutPage.placeOrder();

    assertThat(confirmationPage.getOrderStatus()).isEqualTo("CONFIRMED");
}
```

**When to use:** Critical user journeys (checkout, registration), smoke tests after deployment.

### Anti-Patterns

**Ice Cream Cone (Inverted Pyramid):** Too many E2E tests, few unit tests. Results in slow, flaky CI pipelines and long feedback loops. Common in teams that test only through the UI.

**Hourglass:** Many unit tests, many E2E tests, but few integration tests. The integration layer is where most real bugs live (serialization, SQL queries, API contracts). This pattern gives a false sense of security.

**Testing Trophy (Kent C. Dodds):** An alternative view for frontend and service-heavy code that emphasizes integration tests as the sweet spot:

```
         / E2E \
        /--------\
       / Integra- \        <-- Emphasis here
      /   tion     \
     /--------------\
    /  Unit  | Static \
   /____________________\
```

The key insight: optimize for confidence per time invested. A single integration test that hits a real database catches more real-world bugs than ten unit tests with a mocked repository.

---

## 2. JUnit 5

### Architecture

JUnit 5 is composed of three sub-projects:

| Module           | Role                                                        |
|------------------|-------------------------------------------------------------|
| JUnit Platform   | Foundation for launching test frameworks on the JVM (TestEngine SPI, Launcher API) |
| JUnit Jupiter    | New programming model and extension model for writing tests |
| JUnit Vintage    | Backward compatibility: runs JUnit 3 and JUnit 4 tests on the Platform |

This modular design means IDEs and build tools only depend on the Platform, while different test engines (Jupiter, Vintage, custom) plug in independently.

### Lifecycle Callbacks

```java
class LifecycleDemo {

    @BeforeAll
    static void setupOnce() {
        // Expensive setup: start embedded DB, load config
    }

    @AfterAll
    static void teardownOnce() {
        // Close shared resources
    }

    @BeforeEach
    void setupEach(TestInfo testInfo) {
        System.out.println("Running: " + testInfo.getDisplayName());
    }

    @AfterEach
    void teardownEach() {
        // Reset state between tests
    }

    @Test
    void testSomething() { }
}
```

**Lifecycle per class:** By default, JUnit creates a new test instance per method (`PER_METHOD`). Use `@TestInstance(Lifecycle.PER_CLASS)` if you need non-static `@BeforeAll`/`@AfterAll` or shared mutable state between tests (use carefully).

### @Nested for Test Organization

```java
class OrderServiceTest {

    @Nested
    class WhenOrderIsValid {

        @Test
        void shouldCreateOrder() { }

        @Test
        void shouldPublishOrderCreatedEvent() { }

        @Nested
        class AndCustomerIsPremium {

            @Test
            void shouldApplyPremiumDiscount() { }

            @Test
            void shouldUsePriorityShipping() { }
        }
    }

    @Nested
    class WhenOrderIsInvalid {

        @Test
        void shouldThrowForEmptyLineItems() { }

        @Test
        void shouldThrowForNegativeQuantity() { }
    }
}
```

This produces a hierarchical test report that reads like a specification. Each `@Nested` class can have its own `@BeforeEach`/`@AfterEach`, creating a setup chain from outer to inner.

### @ParameterizedTest

Run the same test logic with different inputs:

```java
// @ValueSource — simple primitives
@ParameterizedTest
@ValueSource(strings = {"racecar", "madam", "level"})
void shouldDetectPalindrome(String candidate) {
    assertThat(StringUtils.isPalindrome(candidate)).isTrue();
}

// @CsvSource — multiple parameters per test case
@ParameterizedTest(name = "{0} + {1} = {2}")
@CsvSource({
    "1, 1, 2",
    "0, 0, 0",
    "-1, 1, 0",
    "Integer.MAX_VALUE, 1, 2147483648"
})
void shouldAddNumbers(long a, long b, long expected) {
    assertThat(a + b).isEqualTo(expected);
}

// @MethodSource — complex objects
@ParameterizedTest
@MethodSource("orderTestCases")
void shouldCalculateOrderTotal(Order order, BigDecimal expectedTotal) {
    assertThat(order.calculateTotal()).isEqualByComparingTo(expectedTotal);
}

static Stream<Arguments> orderTestCases() {
    return Stream.of(
        Arguments.of(
            new Order(List.of(new LineItem("A", 2, new BigDecimal("10.00")))),
            new BigDecimal("20.00")
        ),
        Arguments.of(
            new Order(List.of(
                new LineItem("A", 1, new BigDecimal("5.00")),
                new LineItem("B", 3, new BigDecimal("7.50"))
            )),
            new BigDecimal("27.50")
        )
    );
}

// @EnumSource — test each enum value
@ParameterizedTest
@EnumSource(OrderStatus.class)
void shouldSerializeAllStatuses(OrderStatus status) {
    var json = objectMapper.writeValueAsString(status);
    var deserialized = objectMapper.readValue(json, OrderStatus.class);
    assertThat(deserialized).isEqualTo(status);
}

// @EnumSource with filtering
@ParameterizedTest
@EnumSource(value = OrderStatus.class, names = {"CANCELLED", "REFUNDED"}, mode = EnumSource.Mode.INCLUDE)
void shouldAllowReorderForTerminalStatuses(OrderStatus status) {
    assertThat(status.isTerminal()).isTrue();
}
```

### @TestFactory and Dynamic Tests

Generate tests dynamically at runtime, useful when test cases come from external files or are computed:

```java
@TestFactory
Stream<DynamicTest> shouldParseAllCurrencies() {
    return Currency.getAvailableCurrencies().stream()
        .map(currency -> dynamicTest(
            "Parse " + currency.getCurrencyCode(),
            () -> {
                var parsed = CurrencyParser.parse(currency.getCurrencyCode());
                assertThat(parsed.getCode()).isEqualTo(currency.getCurrencyCode());
            }
        ));
}
```

### Assertions

```java
// assertThrows — capture and inspect exceptions
@Test
void shouldThrowWhenItemNotFound() {
    var exception = assertThrows(ItemNotFoundException.class,
        () -> catalog.findById("NONEXISTENT"));
    assertThat(exception.getMessage()).contains("NONEXISTENT");
}

// assertTimeout — fail if execution exceeds deadline
@Test
void shouldRespondWithinSLA() {
    var result = assertTimeout(Duration.ofMillis(500),
        () -> service.processRequest(request));
    assertThat(result.getStatus()).isEqualTo(200);
}

// assertTimeoutPreemptively — aborts after timeout (runs in separate thread)
@Test
void shouldNotHang() {
    assertTimeoutPreemptively(Duration.ofSeconds(2),
        () -> possiblyHangingMethod());
}

// assertAll — grouped assertions, reports ALL failures (not just first)
@Test
void shouldMapDtoCorrectly() {
    var dto = mapper.toDto(entity);
    assertAll(
        () -> assertThat(dto.getName()).isEqualTo("Alice"),
        () -> assertThat(dto.getEmail()).isEqualTo("alice@test.com"),
        () -> assertThat(dto.getAge()).isEqualTo(30),
        () -> assertThat(dto.getRoles()).containsExactly("USER", "ADMIN")
    );
}
```

### Extensions

JUnit Jupiter's extension model replaces JUnit 4's `@Rule` and `@RunWith`:

```java
// Custom extension: timing each test
public class TimingExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger(TimingExtension.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        getStore(context).put("start", System.currentTimeMillis());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        long start = getStore(context).remove("start", long.class);
        long duration = System.currentTimeMillis() - start;
        log.info("{} took {} ms", context.getDisplayName(), duration);
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }
}

// Usage
@ExtendWith(TimingExtension.class)
class PerformanceSensitiveTest {
    @Test
    void shouldBefast() { }
}
```

**ParameterResolver** — inject custom objects into test methods:

```java
public class RandomParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return paramCtx.getParameter().getType() == Random.class;
    }

    @Override
    public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return new Random(42); // Deterministic seed for reproducibility
    }
}

@ExtendWith(RandomParameterResolver.class)
class RandomizedTest {
    @Test
    void shouldHandleRandomInput(Random random) {
        int value = random.nextInt(1000);
        assertThat(service.process(value)).isNotNull();
    }
}
```

### Other Useful Annotations

```java
@Timeout(5)  // Fail if test takes longer than 5 seconds
@Test
void shouldCompleteQuickly() { }

@RepeatedTest(100)  // Run 100 times (useful for flaky test detection or concurrency tests)
void shouldBeIdempotent(RepetitionInfo info) {
    System.out.println("Repetition " + info.getCurrentRepetition());
}

@Disabled("Blocked by JIRA-1234")
@Test
void pendingFeature() { }
```

---

## 3. Mockito

### Core Annotations

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock                      // Creates a mock — all methods return defaults (null, 0, empty)
    private OrderRepository orderRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Spy                       // Creates a spy — wraps a REAL object, real methods called by default
    private NotificationService notificationService = new NotificationService();

    @InjectMocks               // Creates instance and injects @Mock/@Spy fields into constructor/setters
    private OrderService orderService;

    @Captor                    // Creates an ArgumentCaptor
    private ArgumentCaptor<Order> orderCaptor;
}
```

**@Mock vs @Spy:**

| Aspect          | @Mock                         | @Spy                                |
|-----------------|-------------------------------|-------------------------------------|
| Base behavior   | All methods stubbed (defaults)| Real methods called unless stubbed  |
| Use case        | Isolate from dependency       | Partial mocking of real object      |
| Stubbing syntax | `when(...).thenReturn(...)`   | `doReturn(...).when(spy).method()`  |

Important: With spies, use `doReturn().when()` instead of `when().thenReturn()` to avoid calling the real method during stubbing.

### Stubbing

```java
// Basic stubbing
when(orderRepository.findById("ORD-001")).thenReturn(Optional.of(testOrder));

// Stubbing with different return values on successive calls
when(paymentGateway.charge(any()))
    .thenReturn(PaymentResult.DECLINED)   // first call
    .thenReturn(PaymentResult.SUCCESS);    // second call

// doReturn for void methods or spies
doReturn(Optional.of(testOrder)).when(orderRepository).findById("ORD-001");
doNothing().when(notificationService).sendEmail(any());
doThrow(new RuntimeException("DB down")).when(orderRepository).save(any());

// thenAnswer — dynamic responses based on input
when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
    Order order = invocation.getArgument(0);
    order.setId(UUID.randomUUID().toString());
    return order;
});
```

### Argument Matchers

```java
// Built-in matchers
when(repository.findByStatus(any(OrderStatus.class))).thenReturn(List.of());
when(repository.findByName(eq("Widget"))).thenReturn(Optional.of(widget));
when(repository.findByPriceRange(anyDouble(), anyDouble())).thenReturn(List.of());

// Custom matcher with argThat
when(repository.save(argThat(order ->
    order.getStatus() == OrderStatus.PENDING && order.getLineItems().size() > 0
))).thenReturn(savedOrder);

// IMPORTANT: You cannot mix matchers and raw values
// BAD:  when(service.process(any(), "literal"))  <-- will fail
// GOOD: when(service.process(any(), eq("literal")))
```

### Argument Captors

Capture arguments passed to mocked methods for detailed assertion:

```java
@Test
void shouldSaveOrderWithCorrectTimestamp() {
    orderService.createOrder(request);

    verify(orderRepository).save(orderCaptor.capture());

    Order savedOrder = orderCaptor.getValue();
    assertThat(savedOrder.getCreatedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.SECONDS));
    assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(savedOrder.getLineItems()).hasSize(request.getItems().size());
}

// Capture multiple invocations
@Test
void shouldSendNotificationForEachItem() {
    orderService.processOrder(orderWithThreeItems);

    verify(notificationService, times(3)).notify(orderCaptor.capture());

    List<Order> captured = orderCaptor.getAllValues();
    assertThat(captured).extracting(Order::getItemName)
        .containsExactly("Item-A", "Item-B", "Item-C");
}
```

### Verification

```java
// Verify method was called
verify(orderRepository).save(any(Order.class));

// Verify call count
verify(paymentGateway, times(1)).charge(any());
verify(notificationService, times(3)).sendEmail(any());
verify(auditLog, never()).logFailure(any());
verify(retryService, atLeast(2)).retry(any());
verify(cache, atMost(5)).evict(any());

// Verify call order
InOrder inOrder = inOrder(paymentGateway, orderRepository, notificationService);
inOrder.verify(paymentGateway).charge(any());
inOrder.verify(orderRepository).save(any());
inOrder.verify(notificationService).sendConfirmation(any());

// Verify no more interactions
verifyNoMoreInteractions(orderRepository);

// Verify zero interactions with mock
verifyNoInteractions(auditLog);
```

### BDDMockito

BDD-style aliases for Mockito methods that read more naturally:

```java
import static org.mockito.BDDMockito.*;

@Test
void shouldApplyDiscountForPremiumCustomer() {
    // Given
    given(customerRepository.findById("CUST-001")).willReturn(Optional.of(premiumCustomer));
    given(discountEngine.calculateDiscount(premiumCustomer)).willReturn(0.15);

    // When
    var result = orderService.calculatePrice(order, "CUST-001");

    // Then
    then(customerRepository).should().findById("CUST-001");
    then(discountEngine).should().calculateDiscount(premiumCustomer);
    then(auditLog).should(never()).logWarning(any());
    assertThat(result.getDiscount()).isEqualTo(0.15);
}
```

### Mocking Static Methods

Since Mockito 3.4+, you can mock static methods using `mockStatic`:

```java
@Test
void shouldMockCurrentTime() {
    var fixedInstant = Instant.parse("2024-01-15T10:00:00Z");

    try (MockedStatic<Instant> mockedInstant = mockStatic(Instant.class)) {
        mockedInstant.when(Instant::now).thenReturn(fixedInstant);

        var order = orderService.createOrder(request);
        assertThat(order.getCreatedAt()).isEqualTo(fixedInstant);
    }
    // Static mock is automatically cleaned up outside the try block
}
```

### Mocking Constructors

Since Mockito 3.5+:

```java
@Test
void shouldMockConstructor() {
    try (MockedConstruction<HttpClient> mocked = mockConstruction(HttpClient.class,
            (mock, context) -> {
                when(mock.send(any())).thenReturn(new Response(200, "OK"));
            })) {

        // Any new HttpClient() inside this block returns the mock
        var result = service.callExternalApi();
        assertThat(result.getStatusCode()).isEqualTo(200);
    }
}
```

### Full Service Test Example

```java
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentGateway gateway;
    @Mock private TransactionRepository transactionRepo;
    @Mock private EventPublisher eventPublisher;
    @Captor private ArgumentCaptor<Transaction> txnCaptor;

    @InjectMocks private PaymentService paymentService;

    @Test
    void shouldProcessPaymentAndPublishEvent() {
        // Given
        var request = new PaymentRequest("CUST-001", new BigDecimal("99.99"), Currency.USD);
        var gatewayResponse = new GatewayResponse("TXN-123", GatewayStatus.APPROVED);
        when(gateway.charge(any(ChargeRequest.class))).thenReturn(gatewayResponse);
        when(transactionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        var result = paymentService.processPayment(request);

        // Then
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getTransactionId()).isEqualTo("TXN-123");

        verify(transactionRepo).save(txnCaptor.capture());
        Transaction saved = txnCaptor.getValue();
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("99.99"));
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.COMPLETED);

        verify(eventPublisher).publish(argThat(event ->
            event instanceof PaymentCompletedEvent &&
            ((PaymentCompletedEvent) event).getTransactionId().equals("TXN-123")
        ));
    }

    @Test
    void shouldHandleGatewayDecline() {
        when(gateway.charge(any())).thenReturn(
            new GatewayResponse(null, GatewayStatus.DECLINED));

        var result = paymentService.processPayment(
            new PaymentRequest("CUST-001", new BigDecimal("99.99"), Currency.USD));

        assertThat(result.isSuccessful()).isFalse();
        verify(transactionRepo).save(argThat(txn -> txn.getStatus() == TransactionStatus.DECLINED));
        verify(eventPublisher, never()).publish(any(PaymentCompletedEvent.class));
    }
}
```

---

## 4. Testcontainers

### Core Concept

Testcontainers provides lightweight, disposable Docker containers for integration testing. Containers start before tests and are automatically cleaned up after, ensuring a fresh state for each test run.

**Why not H2?** In-memory databases like H2 don't support all features of your production database (window functions, JSON operators, extensions). Testcontainers lets you test against the exact same database engine you deploy to production.

### Database Testing

```java
@Testcontainers
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("schema.sql");  // Runs SQL script on startup

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldFindUsersByEmailDomain() {
        userRepository.saveAll(List.of(
            new User("alice", "alice@example.com"),
            new User("bob", "bob@example.com"),
            new User("charlie", "charlie@other.com")
        ));

        // Test native query with PostgreSQL-specific syntax
        var users = userRepository.findByEmailDomain("example.com");
        assertThat(users).extracting(User::getName)
            .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void shouldSupportJsonbQueries() {
        // Test PostgreSQL JSONB features that H2 doesn't support
        userRepository.save(new User("alice", "alice@test.com",
            Map.of("theme", "dark", "language", "en")));

        var users = userRepository.findByPreference("theme", "dark");
        assertThat(users).hasSize(1);
    }
}
```

### Kafka Testing

```java
@Testcontainers
@SpringBootTest
class OrderEventConsumerIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldConsumeOrderEventAndPersist() throws Exception {
        var event = new OrderEvent("ORD-001", OrderAction.CREATED, Instant.now());
        kafkaTemplate.send("order-events", event.orderId(), event).get();

        // Wait for consumer to process
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                var order = orderRepository.findById("ORD-001");
                assertThat(order).isPresent();
                assertThat(order.get().getStatus()).isEqualTo(OrderStatus.CREATED);
            });
    }
}
```

### GenericContainer for Custom Services

```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379)
    .waitingFor(Wait.forListeningPort());

@Container
static GenericContainer<?> localstack = new GenericContainer<>("localstack/localstack:3.0")
    .withExposedPorts(4566)
    .withEnv("SERVICES", "s3,sqs")
    .waitingFor(Wait.forLogMessage(".*Ready\\.\n", 1));

@DynamicPropertySource
static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```

### Singleton Pattern for Shared Containers

Starting a container per test class is slow. The singleton pattern shares a single container instance across all test classes:

```java
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb");
        POSTGRES.start();

        KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}

// All integration tests extend this base class
class UserServiceIT extends AbstractIntegrationTest {
    @Test
    void shouldCreateUser() { /* ... */ }
}

class OrderServiceIT extends AbstractIntegrationTest {
    @Test
    void shouldCreateOrder() { /* ... */ }
}
```

The JVM shutdown hook in Testcontainers (Ryuk) automatically cleans up containers when the JVM exits.

---

## 5. WireMock

### Core Concept

WireMock is an HTTP mock server for testing code that interacts with external HTTP APIs. It runs an actual HTTP server and returns stubbed responses based on request matching rules.

### JUnit 5 Setup

```java
@WireMockTest(httpPort = 8089)
class WeatherClientTest {

    private final WeatherClient client = new WeatherClient("http://localhost:8089");

    @Test
    void shouldFetchCurrentWeather() {
        stubFor(get(urlPathEqualTo("/api/weather"))
            .withQueryParam("city", equalTo("London"))
            .withHeader("Accept", containing("application/json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "city": "London",
                        "temperature": 15.5,
                        "condition": "Cloudy"
                    }
                    """)));

        var weather = client.getCurrentWeather("London");

        assertThat(weather.getCity()).isEqualTo("London");
        assertThat(weather.getTemperature()).isEqualTo(15.5);
    }
}
```

### Request Matching

```java
// URL matching
stubFor(get(urlEqualTo("/exact/path?param=value")));          // exact URL + query
stubFor(get(urlPathEqualTo("/just/path")));                    // path only, ignoring query
stubFor(get(urlMatching("/api/users/[0-9]+")));                // regex
stubFor(get(urlPathTemplate("/api/users/{userId}")));          // path template

// Header matching
stubFor(get(anyUrl())
    .withHeader("Authorization", equalTo("Bearer token123"))
    .withHeader("X-Request-Id", matching("[a-f0-9-]{36}")));

// Request body matching (for POST/PUT)
stubFor(post("/api/orders")
    .withRequestBody(equalToJson("""
        { "item": "Widget", "quantity": 5 }
        """))
    .willReturn(created()));

stubFor(post("/api/orders")
    .withRequestBody(matchingJsonPath("$.item", equalTo("Widget")))
    .willReturn(created()));
```

### Response Templating

```java
stubFor(get(urlPathTemplate("/api/users/{userId}"))
    .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("""
            {
                "id": "{{request.pathSegments.[2]}}",
                "requestedAt": "{{now}}",
                "correlationId": "{{request.headers.X-Correlation-Id}}"
            }
            """)
        .withTransformers("response-template")));
```

### Fault Simulation

```java
// Simulate slow responses
stubFor(get("/api/slow-endpoint")
    .willReturn(aResponse()
        .withStatus(200)
        .withFixedDelay(5000)));  // 5 second delay

// Simulate connection reset
stubFor(get("/api/unstable")
    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

// Simulate empty response
stubFor(get("/api/broken")
    .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

// Simulate random delay (for realistic latency distribution)
stubFor(get("/api/variable")
    .willReturn(aResponse()
        .withStatus(200)
        .withUniformRandomDelay(100, 2000)));
```

### Verification

```java
@Test
void shouldRetryOnFailure() {
    stubFor(get("/api/data")
        .willReturn(serverError())        // First call fails
        .willReturn(ok("success")));       // Use Scenarios for stateful behavior

    client.fetchDataWithRetry();

    // Verify the client retried
    verify(2, getRequestedFor(urlEqualTo("/api/data")));

    // Verify specific request details
    verify(getRequestedFor(urlEqualTo("/api/data"))
        .withHeader("Authorization", matching("Bearer .*")));
}
```

### Stateful Stubs with Scenarios

```java
@Test
void shouldHandleRetryAfterFailure() {
    // First request returns 503, second returns 200
    stubFor(get("/api/data")
        .inScenario("Retry")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(serviceUnavailable())
        .willSetStateTo("Recovered"));

    stubFor(get("/api/data")
        .inScenario("Retry")
        .whenScenarioStateIs("Recovered")
        .willReturn(ok().withBody("{\"status\": \"ok\"}")));

    var result = clientWithRetry.fetchData();

    assertThat(result.getStatus()).isEqualTo("ok");
    verify(2, getRequestedFor(urlEqualTo("/api/data")));
}
```

### Full HTTP Client Test Example

```java
@WireMockTest(httpPort = 8089)
class PaymentGatewayClientTest {

    private final PaymentGatewayClient client =
        new PaymentGatewayClient("http://localhost:8089", Duration.ofSeconds(2));

    @Test
    void shouldChargeSuccessfully() {
        stubFor(post("/v1/charges")
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(matchingJsonPath("$.amount", equalTo("99.99")))
            .willReturn(okJson("""
                {
                    "id": "ch_abc123",
                    "status": "succeeded",
                    "amount": 99.99
                }
                """)));

        var result = client.charge(new ChargeRequest(new BigDecimal("99.99"), "USD"));

        assertThat(result.getId()).isEqualTo("ch_abc123");
        assertThat(result.getStatus()).isEqualTo("succeeded");
    }

    @Test
    void shouldThrowOnTimeout() {
        stubFor(post("/v1/charges")
            .willReturn(ok().withFixedDelay(5000)));

        assertThrows(PaymentTimeoutException.class,
            () -> client.charge(new ChargeRequest(new BigDecimal("99.99"), "USD")));
    }

    @Test
    void shouldThrowOnServerError() {
        stubFor(post("/v1/charges")
            .willReturn(serverError().withBody("""
                {"error": {"code": "processing_error", "message": "Internal error"}}
                """)));

        var exception = assertThrows(PaymentGatewayException.class,
            () -> client.charge(new ChargeRequest(new BigDecimal("99.99"), "USD")));
        assertThat(exception.getErrorCode()).isEqualTo("processing_error");
    }
}
```

---

## 6. ArchUnit

### Core Concept

ArchUnit lets you define and enforce architectural rules as unit tests. Instead of relying on code review to catch architecture violations, you write tests that automatically fail when someone introduces a forbidden dependency or violates a naming convention.

### Layer Checks

```java
@AnalyzeClasses(packages = "com.example.myapp")
class ArchitectureTest {

    @ArchTest
    static final ArchRule layerDependencies = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Controller").definedBy("..controller..")
        .layer("Service").definedBy("..service..")
        .layer("Repository").definedBy("..repository..")
        .layer("Domain").definedBy("..domain..")

        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Service", "Repository");

    @ArchTest
    static final ArchRule noControllerShouldAccessRepository =
        noClasses().that().resideInAPackage("..controller..")
            .should().accessClassesThat().resideInAPackage("..repository..");
}
```

### Naming Conventions

```java
@ArchTest
static final ArchRule controllerNaming =
    classes().that().resideInAPackage("..controller..")
        .should().haveSimpleNameEndingWith("Controller");

@ArchTest
static final ArchRule serviceNaming =
    classes().that().resideInAPackage("..service..")
        .and().areNotInterfaces()
        .should().haveSimpleNameEndingWith("ServiceImpl")
        .orShould().haveSimpleNameEndingWith("Service");

@ArchTest
static final ArchRule repositoryNaming =
    classes().that().areAnnotatedWith(Repository.class)
        .should().haveSimpleNameEndingWith("Repository");
```

### Annotation Checks

```java
@ArchTest
static final ArchRule restControllersShouldBeAnnotated =
    classes().that().haveSimpleNameEndingWith("Controller")
        .should().beAnnotatedWith(RestController.class);

@ArchTest
static final ArchRule servicesShouldBeTransactional =
    methods().that().areDeclaredInClassesThat().resideInAPackage("..service..")
        .and().arePublic()
        .should().beAnnotatedWith(Transactional.class);

@ArchTest
static final ArchRule noDependencyOnSpringInDomainLayer =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAPackage("org.springframework..");
```

### Hexagonal Architecture Enforcement

```java
@AnalyzeClasses(packages = "com.example.myapp")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainShouldNotDependOnInfrastructure =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..infrastructure..",
                "..adapter..",
                "org.springframework..",
                "javax.persistence.."
            );

    @ArchTest
    static final ArchRule portsShouldBeInterfaces =
        classes().that().resideInAPackage("..port..")
            .should().beInterfaces();

    @ArchTest
    static final ArchRule adaptersShouldOnlyAccessPortsAndDomain =
        classes().that().resideInAPackage("..adapter..")
            .should().onlyAccessClassesThat()
            .resideInAnyPackage(
                "..adapter..",
                "..port..",
                "..domain..",
                "java..",
                "org.springframework.."
            );

    @ArchTest
    static final ArchRule useCasesShouldOnlyDependOnPorts =
        classes().that().resideInAPackage("..usecase..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                "..usecase..",
                "..port..",
                "..domain..",
                "java.."
            );

    @ArchTest
    static final ArchRule noCyclicDependencies =
        slices().matching("com.example.myapp.(*)..")
            .should().beFreeOfCycles();
}
```

### Custom Rules

```java
@ArchTest
static final ArchRule loggersShouldBePrivateStaticFinal =
    fields().that().haveRawType(Logger.class)
        .should().bePrivate()
        .andShould().beStatic()
        .andShould().beFinal()
        .because("Logger fields should follow the standard convention");

@ArchTest
static final ArchRule noFieldInjection =
    noFields().should().beAnnotatedWith(Autowired.class)
        .because("Use constructor injection instead of field injection");

@ArchTest
static final ArchRule exceptionsShouldEndWithException =
    classes().that().areAssignableTo(Exception.class)
        .should().haveSimpleNameEndingWith("Exception");
```

---

## 7. Contract Testing

### Why Contract Testing

In a microservice architecture, integration tests between services are slow and brittle. Contract testing verifies that services agree on their API interface without requiring both services to run simultaneously.

| Approach         | Who defines the contract | Flow                         |
|------------------|--------------------------|------------------------------|
| Consumer-driven (Pact) | Consumer defines expectations | Consumer writes contract -> Provider verifies |
| Producer-driven (Spring Cloud Contract) | Provider defines contract | Provider writes contract -> Consumer generates stubs |

### Pact (Consumer-Driven Contract Testing)

**Consumer side** — the service that calls the API:

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "OrderService", port = "8080")
class OrderClientPactTest {

    @Pact(consumer = "PaymentService", provider = "OrderService")
    V4Pact getOrderPact(PactDslWithProvider builder) {
        return builder
            .given("order ORD-001 exists")
            .uponReceiving("a request to get order ORD-001")
            .path("/api/orders/ORD-001")
            .method("GET")
            .headers("Accept", "application/json")
            .willRespondWith()
            .status(200)
            .headers(Map.of("Content-Type", "application/json"))
            .body(newJsonBody(body -> {
                body.stringType("id", "ORD-001");
                body.stringType("status", "PENDING");
                body.decimalType("total", 99.99);
                body.array("items", items -> {
                    items.object(item -> {
                        item.stringType("name", "Widget");
                        item.integerType("quantity", 2);
                    });
                });
            }).build())
            .toPact(V4Pact.class);
    }

    @Pact(consumer = "PaymentService", provider = "OrderService")
    V4Pact orderNotFoundPact(PactDslWithProvider builder) {
        return builder
            .given("order ORD-999 does not exist")
            .uponReceiving("a request for a non-existent order")
            .path("/api/orders/ORD-999")
            .method("GET")
            .willRespondWith()
            .status(404)
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "getOrderPact")
    void shouldFetchOrder() {
        var client = new OrderClient("http://localhost:8080");
        var order = client.getOrder("ORD-001");

        assertThat(order.getId()).isEqualTo("ORD-001");
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getItems()).isNotEmpty();
    }

    @Test
    @PactTestFor(pactMethod = "orderNotFoundPact")
    void shouldHandleOrderNotFound() {
        var client = new OrderClient("http://localhost:8080");

        assertThrows(OrderNotFoundException.class,
            () -> client.getOrder("ORD-999"));
    }
}
```

The consumer test generates a Pact file (JSON contract) which is shared with the provider via a Pact Broker or file system.

**Provider side** — the service that implements the API:

```java
@Provider("OrderService")
@PactBroker(url = "https://pact-broker.example.com")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServicePactVerificationTest {

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @BeforeEach
    void before(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @State("order ORD-001 exists")
    void orderExists() {
        orderRepository.save(new Order("ORD-001", OrderStatus.PENDING,
            new BigDecimal("99.99"), List.of(new LineItem("Widget", 2))));
    }

    @State("order ORD-999 does not exist")
    void orderDoesNotExist() {
        orderRepository.deleteById("ORD-999");
    }
}
```

### Spring Cloud Contract (Producer-Driven)

The provider defines contracts in Groovy DSL or YAML, and Spring Cloud Contract generates tests for the provider and stubs for consumers.

```groovy
// src/test/resources/contracts/shouldReturnOrder.groovy
Contract.make {
    description "should return order by ID"
    request {
        method GET()
        url "/api/orders/ORD-001"
        headers {
            accept(applicationJson())
        }
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            id: "ORD-001",
            status: "PENDING",
            total: 99.99
        ])
    }
}
```

### When to Use What

| Scenario                                  | Recommended Approach     |
|-------------------------------------------|--------------------------|
| Many consumers, one provider              | Pact (consumer-driven)   |
| Provider team controls the API contract   | Spring Cloud Contract    |
| Async messaging contracts                 | Pact or Spring Cloud Contract (both support it) |
| Third-party API you don't control         | WireMock (not contract testing) |
| Monolith or few services                  | Integration/E2E tests may suffice |

---

## 8. Test Patterns & Best Practices

### Given/When/Then (Arrange/Act/Assert)

Every test should have three clearly separated sections:

```java
@Test
void shouldApplyBulkDiscount() {
    // Given (Arrange) — set up preconditions
    var cart = new ShoppingCart();
    cart.addItem(new Item("Widget", 10.00), 50);

    // When (Act) — perform the action under test
    var invoice = invoiceService.generateInvoice(cart);

    // Then (Assert) — verify the outcome
    assertThat(invoice.getDiscount()).isEqualTo(0.10);  // 10% bulk discount
    assertThat(invoice.getTotal()).isEqualByComparingTo(new BigDecimal("450.00"));
}
```

### Test Data Builders

When you need complex objects with many fields, builders keep tests readable:

```java
public class OrderBuilder {
    private String id = "ORD-001";
    private String customerId = "CUST-001";
    private OrderStatus status = OrderStatus.PENDING;
    private List<LineItem> items = List.of(new LineItem("Widget", 1, new BigDecimal("9.99")));
    private Instant createdAt = Instant.now();

    public static OrderBuilder anOrder() {
        return new OrderBuilder();
    }

    public OrderBuilder withId(String id) { this.id = id; return this; }
    public OrderBuilder withCustomerId(String customerId) { this.customerId = customerId; return this; }
    public OrderBuilder withStatus(OrderStatus status) { this.status = status; return this; }
    public OrderBuilder withItems(List<LineItem> items) { this.items = items; return this; }
    public OrderBuilder withCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

    public Order build() {
        return new Order(id, customerId, status, items, createdAt);
    }
}

// Usage in tests
@Test
void shouldCancelPendingOrder() {
    var order = anOrder()
        .withStatus(OrderStatus.PENDING)
        .withItems(List.of(new LineItem("Gadget", 3, new BigDecimal("25.00"))))
        .build();

    orderService.cancel(order);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
}
```

### ObjectMother Pattern

A catalogue of pre-built test objects for common scenarios:

```java
public class TestOrders {
    public static Order pendingOrder() {
        return anOrder().withStatus(OrderStatus.PENDING).build();
    }

    public static Order paidOrder() {
        return anOrder().withStatus(OrderStatus.PAID).build();
    }

    public static Order orderWithItems(int count) {
        var items = IntStream.rangeClosed(1, count)
            .mapToObj(i -> new LineItem("Item-" + i, 1, new BigDecimal("10.00")))
            .toList();
        return anOrder().withItems(items).build();
    }

    public static Order highValueOrder() {
        return anOrder()
            .withItems(List.of(new LineItem("Premium", 1, new BigDecimal("10000.00"))))
            .build();
    }
}

// Usage
@Test
void shouldRequireApprovalForHighValueOrders() {
    var order = TestOrders.highValueOrder();
    var result = orderService.submit(order);
    assertThat(result.requiresApproval()).isTrue();
}
```

### What Makes a Good Test

| Property       | Description                                                 |
|----------------|-------------------------------------------------------------|
| Isolated       | No dependency on other tests; can run in any order          |
| Deterministic  | Same result every time (no random data, no system clock)    |
| Fast           | Unit tests < 10ms, integration tests < seconds              |
| Readable       | A failing test should clearly communicate what went wrong   |
| Tests behavior | Verifies observable outcomes, not implementation details    |

### Testing Behavior, Not Implementation

```java
// BAD — tests implementation details (which internal method is called)
@Test
void shouldCallValidateAndSaveInOrder() {
    orderService.createOrder(request);
    InOrder inOrder = inOrder(orderService);
    inOrder.verify(orderService).validate(request);     // Testing private method order
    inOrder.verify(orderService).enrichOrder(any());     // Couples test to internal flow
    inOrder.verify(orderService).persistOrder(any());
}

// GOOD — tests observable behavior
@Test
void shouldCreateOrderWithCorrectStatus() {
    var result = orderService.createOrder(request);

    assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(orderRepository.findById(result.getId())).isPresent();
}
```

### Don't Mock What You Don't Own

Mocking third-party libraries leads to brittle tests that pass even when the library changes behavior. Instead, create your own abstraction:

```java
// BAD — mocking HttpClient directly
when(httpClient.send(any(), any())).thenReturn(httpResponse);

// GOOD — create your own interface and mock that
public interface PaymentGateway {
    ChargeResult charge(ChargeRequest request);
}

// In production: HttpPaymentGateway implements PaymentGateway (uses HttpClient internally)
// In tests: mock PaymentGateway, not HttpClient
when(paymentGateway.charge(any())).thenReturn(ChargeResult.success("TXN-123"));
```

This way, if the HTTP library changes API, you update one adapter class instead of dozens of tests.

---

## 9. TDD vs BDD

### Test-Driven Development (TDD)

TDD follows the **Red-Green-Refactor** cycle:

1. **Red** — Write a failing test for the next piece of functionality
2. **Green** — Write the minimum code to make the test pass
3. **Refactor** — Clean up the code while keeping tests green

```java
// Step 1: RED — write a failing test
@Test
void shouldReturnFizzForMultiplesOfThree() {
    assertThat(FizzBuzz.convert(3)).isEqualTo("Fizz");
    assertThat(FizzBuzz.convert(9)).isEqualTo("Fizz");
}

// Step 2: GREEN — minimal implementation
public class FizzBuzz {
    public static String convert(int n) {
        if (n % 3 == 0) return "Fizz";
        return String.valueOf(n);
    }
}

// Step 3: REFACTOR — improve design (if needed), then write the next failing test
@Test
void shouldReturnBuzzForMultiplesOfFive() {
    assertThat(FizzBuzz.convert(5)).isEqualTo("Buzz");
}
```

**Benefits:** Forces design thinking before implementation, ensures high test coverage naturally, produces modular code with small interfaces.

**When TDD shines:** Well-understood domains, algorithm implementation, library/API design, working with legacy code (write characterization tests first).

### Behavior-Driven Development (BDD)

BDD uses natural language scenarios (Gherkin syntax) to describe behavior, bridging the gap between business stakeholders and developers.

**Feature file (Gherkin):**

```gherkin
Feature: Shopping Cart Pricing

  Scenario: Apply bulk discount
    Given a shopping cart
    And the cart contains 50 units of "Widget" at $10.00 each
    When the invoice is generated
    Then the discount should be 10%
    And the total should be $450.00

  Scenario Outline: Tiered discounts
    Given a shopping cart with <quantity> units of "Widget" at $10.00
    When the invoice is generated
    Then the discount should be <discount>

    Examples:
      | quantity | discount |
      | 10       | 0%       |
      | 25       | 5%       |
      | 50       | 10%      |
      | 100      | 15%      |
```

**Step definitions (Cucumber-Java):**

```java
public class ShoppingCartSteps {

    private ShoppingCart cart;
    private Invoice invoice;

    @Given("a shopping cart")
    public void createCart() {
        cart = new ShoppingCart();
    }

    @Given("the cart contains {int} units of {string} at ${double} each")
    public void addItems(int quantity, String name, double price) {
        cart.addItem(new Item(name, price), quantity);
    }

    @When("the invoice is generated")
    public void generateInvoice() {
        invoice = new InvoiceService().generateInvoice(cart);
    }

    @Then("the discount should be {int}%")
    public void verifyDiscount(int expectedPercent) {
        assertThat(invoice.getDiscountPercent()).isEqualTo(expectedPercent);
    }

    @Then("the total should be ${double}")
    public void verifyTotal(double expectedTotal) {
        assertThat(invoice.getTotal()).isEqualByComparingTo(BigDecimal.valueOf(expectedTotal));
    }
}
```

### TDD vs BDD Comparison

| Aspect              | TDD                                 | BDD                                      |
|---------------------|--------------------------------------|------------------------------------------|
| Audience            | Developers                           | Developers + business stakeholders       |
| Language            | Code (JUnit, etc.)                   | Gherkin (natural language) + code        |
| Focus               | Correctness of implementation        | Behavior from user perspective           |
| Granularity         | Method/class level                   | Feature/scenario level                   |
| Overhead            | Low                                  | Higher (Gherkin files + step defs)       |
| Best for            | Libraries, algorithms, internal logic| User-facing features, acceptance criteria|
| Maintenance cost    | Low (tests are close to code)        | Higher (keeping Gherkin in sync)         |

**Practical guidance:** Use TDD for internal/technical code. Use BDD when business stakeholders actively participate in defining acceptance criteria. Many teams use TDD day-to-day and BDD only for critical user-facing flows.

---

## 10. Mutation Testing

### What It Measures

Code coverage tells you which lines execute during tests. It does NOT tell you if the tests actually assert correct behavior. Mutation testing evaluates **test quality** by introducing small changes (mutations) to source code and checking if tests catch them.

**Process:**

1. PIT modifies your source code (e.g., changes `>` to `>=`, removes a method call, replaces `return true` with `return false`)
2. Runs your test suite against each mutant
3. If a test fails: the mutant is **killed** (good -- your tests caught the change)
4. If all tests pass: the mutant **survived** (bad -- your tests missed a real code change)

### PIT (Pitest) Setup

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.15.0</version>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.1</version>
        </dependency>
    </dependencies>
    <configuration>
        <targetClasses>
            <param>com.example.myapp.service.*</param>
        </targetClasses>
        <targetTests>
            <param>com.example.myapp.service.*Test</param>
        </targetTests>
        <mutators>
            <mutator>DEFAULTS</mutator>  <!-- Standard set of mutators -->
        </mutators>
        <outputFormats>
            <param>HTML</param>
        </outputFormats>
    </configuration>
</plugin>
```

Run: `mvn org.pitest:pitest-maven:mutationCoverage`

### Common Mutators

| Mutator                    | What it does                                 | Example                          |
|----------------------------|----------------------------------------------|----------------------------------|
| ConditionalsBoundary       | Changes `<` to `<=`, `>` to `>=`             | `if (x > 0)` -> `if (x >= 0)`   |
| Negate Conditionals        | Negates conditions                           | `if (x == 0)` -> `if (x != 0)`  |
| Math Mutator               | Replaces math operators                      | `a + b` -> `a - b`              |
| Return Values              | Changes return values                        | `return true` -> `return false`  |
| Void Method Calls          | Removes void method calls                    | `list.add(x)` -> (removed)      |
| Increments                 | Changes `++` to `--` and vice versa          | `i++` -> `i--`                   |

### Interpreting Results

```
>> Generated 150 mutations
>> Killed: 135 (90%)
>> Survived: 12 (8%)
>> No coverage: 3 (2%)
```

- **Mutation score 90%+** is generally excellent
- **Survived mutants** highlight tests that execute code but don't assert on it
- **No coverage mutants** are in code that tests don't reach at all

### Equivalent Mutants

Some mutations produce logically equivalent code that no test can catch:

```java
// Original
int index = 0;
while (index < list.size()) { ... index++; }

// Mutant: changes < to !=
while (index != list.size()) { ... index++; }
// Functionally identical when index starts at 0 and increments by 1
```

Equivalent mutants inflate the survival rate. PIT attempts to avoid obvious equivalent mutants, but some always slip through. A mutation score of 100% is unrealistic and not worth pursuing.

### When Mutation Testing Is Worth the Cost

- **Critical business logic** (pricing, billing, access control) where a bug means real money or security
- **Libraries and shared code** used by many consumers
- **Core algorithms** where subtle boundary errors have outsized impact
- **NOT worth it** for: UI layers, glue code, simple CRUD, prototypes, rapidly changing code

---

## 11. Common Senior Interview Questions

**Q1: You inherit a legacy codebase with zero tests. How do you start adding tests?**

Start with **characterization tests** (Michael Feathers, "Working Effectively with Legacy Code"). Before refactoring anything, write tests that capture the current behavior of critical paths, even if that behavior is buggy. Use the "strangler fig" approach: identify seams in the code where you can introduce interfaces, then write tests at those boundaries. Prioritize testing code that changes frequently (use `git log` to find hotspots) and code with the highest business risk. Don't aim for 100% coverage upfront; focus on the "test the change" principle -- every new change gets a test. Use integration tests initially (they catch more with less setup), then add unit tests as you extract logic into testable classes.

**Q2: What is the difference between @Mock and @Spy in Mockito? When would you use each?**

A `@Mock` creates a fully stubbed proxy -- all methods return default values (null, 0, false, empty collections) unless explicitly stubbed. Use mocks to isolate the class under test from its dependencies. A `@Spy` wraps a real object -- real methods execute unless explicitly overridden with `doReturn().when()`. Use spies when you need most of the real behavior but want to override one or two methods, such as a notification service where you want real formatting logic but don't want to actually send emails. Be cautious with spies: they indicate tight coupling and often suggest the code should be refactored to make mocking cleaner.

**Q3: How do you decide what to unit test vs integration test vs E2E test?**

Apply the testing pyramid principle with pragmatism. **Unit test** pure business logic, calculations, validations, and state machines -- anything with no I/O dependencies. **Integration test** anything that crosses a system boundary: database queries (test the actual SQL, not a mock), HTTP clients (use WireMock), message producers/consumers (use Testcontainers with Kafka), and cache interactions. **E2E test** only the most critical user journeys (happy path through checkout, login flow, core workflow). The key heuristic: if a bug in this code would wake you up at 2 AM, it deserves integration tests. If it would lose revenue, add an E2E test.

**Q4: Explain contract testing. When would you choose it over E2E tests?**

Contract testing verifies that two services agree on their API interface without running them together. Consumer-driven (Pact): the consumer defines what it needs from the provider, generating a contract file. The provider then independently verifies it can fulfill the contract. Choose contract testing over E2E when: you have many independently deployed microservices, E2E environments are unreliable or expensive, teams need to work independently, and you need fast CI feedback. Contract tests run in seconds (no live services needed), while E2E tests require full environment orchestration. However, contract tests don't catch issues beyond the interface (e.g., a provider returning valid JSON but wrong business logic). Use contract testing for API compatibility and reserve E2E for critical cross-service workflows.

**Q5: What is mutation testing and how does it differ from code coverage?**

Code coverage measures which lines of code are executed during tests. A test that executes code but never asserts anything still counts as "covered." Mutation testing goes further: it introduces small code changes (mutants) such as flipping conditions, changing return values, or removing method calls, then runs your tests against each mutant. If a test fails, the mutant is "killed" -- meaning your test actually verifies that behavior. If all tests pass, the mutant "survived" -- revealing a gap in your assertions. For example, 100% line coverage with 60% mutation score means 40% of your code changes would go undetected by your tests. PIT (pitest) is the standard tool for Java. Use mutation testing on critical business logic; it's too slow for full-codebase runs.

**Q6: How would you test a service that depends on an external HTTP API?**

Use WireMock to stub the external API in integration tests. Define stubs for expected requests and responses, including error scenarios (timeouts, 5xx errors, malformed responses). This tests your HTTP client, serialization, error handling, and retry logic without depending on the external service's availability. For unit tests, don't mock `HttpClient` directly -- create your own interface (like `PaymentGateway`) with an implementation that uses `HttpClient`, then mock your interface. For contract verification, use Pact to ensure your expectations match what the provider actually returns. In CI, never call real external APIs -- this creates flaky tests and can incur costs.

**Q7: How do you handle test data setup without tests becoming brittle and hard to maintain?**

Use the Test Data Builder pattern and ObjectMother pattern. Builders provide a fluent API where each test only specifies the fields relevant to that test case, with sensible defaults for everything else. This means adding a new required field to an entity requires updating only the builder, not hundreds of tests. For database tests, prefer programmatic setup over SQL scripts (which are fragile) and avoid shared mutable test data (leads to test interdependence). Each test should create exactly the data it needs. For complex scenarios, use fixtures or factory methods in a shared `TestData` class. Also consider `@Sql` annotations for Spring or Flyway/Liquibase for schema management, but keep test data creation in Java where refactoring tools work.

**Q8: You have a flaky test that fails intermittently in CI. How do you diagnose and fix it?**

Common causes of flaky tests: (1) **Shared mutable state** between tests -- fix with proper isolation (`@DirtiesContext`, `@Transactional`, separate test instances). (2) **Timing/async issues** -- fix with `Awaitility` library (`await().atMost(10, SECONDS).untilAsserted(() -> ...)`), never `Thread.sleep()`. (3) **Test order dependency** -- run with randomized order (`@TestMethodOrder(Random.class)`) to surface it, then fix the shared state. (4) **Resource contention** -- ports, files, database connections. Use random ports and isolated containers. (5) **Non-deterministic data** -- dates, random values, UUIDs. Inject clocks and random seeds. To diagnose: run the test in a loop (`@RepeatedTest(100)`), check if it fails consistently in isolation vs only with other tests, and examine CI logs for resource exhaustion or timeout patterns.

**Q9: What are the benefits and drawbacks of Testcontainers versus in-memory databases like H2?**

Testcontainers runs the actual production database (PostgreSQL, MySQL, etc.) in Docker, ensuring SQL syntax, functions, extensions, and behavior exactly match production. H2 is faster to start and requires no Docker, but it has incomplete compatibility -- window functions, JSON operators, CTEs, stored procedures, and locking behavior may differ from your production database. The trade-off: Testcontainers gives higher confidence but slower tests (container startup takes 2-5 seconds with the singleton pattern). H2 is faster but can give false confidence. Best practice: use Testcontainers for repository/DAO integration tests where SQL correctness matters, and H2 (if at all) only for quick smoke tests in development. Always use the singleton container pattern to avoid the startup cost per test class.

**Q10: What does "don't mock what you don't own" mean in practice?**

This principle (from "Growing Object-Oriented Software, Guided by Tests" by Freeman and Pryce) means you should not create mocks of third-party library types (e.g., `HttpClient`, `EntityManager`, `KafkaTemplate`). Reasons: (1) You might mock behavior that doesn't match the real implementation. (2) Library upgrades can silently break your mocks without test failures. (3) It couples tests to implementation details of external code. Instead, create your own thin adapter/port interface, implement it using the third-party library, and mock your interface in unit tests. Test the adapter itself with integration tests (using Testcontainers, WireMock, etc.). This follows the Ports and Adapters (Hexagonal) architecture naturally.