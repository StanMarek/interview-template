# Part 1 — Testing Strategy, JUnit, AssertJ

## Testing Pyramid

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

**Hourglass:** Many unit tests, many E2E tests, but few integration tests. The integration layer is where most real bugs live: serialization, SQL queries, API contracts, wiring, transaction boundaries. This pattern gives a false sense of security.

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

## JUnit Jupiter / JUnit 6

### Current Version Context (April 2026)

- JUnit **6.0.3** is the current stable line.
- JUnit 6 uses a **single version number** for Platform, Jupiter, and Vintage.
- JUnit 6 raised the runtime baseline to **Java 17**.
- JUnit **5.14.x** is the last 5.x line and still common in existing codebases because it includes migration help for JUnit 6.

Interview shorthand: many teams still say "JUnit 5" conversationally, but the current family is JUnit 6.

### Architecture

JUnit is composed of three sub-projects:

| Module           | Role |
|------------------|------|
| JUnit Platform   | Foundation for launching test frameworks on the JVM (`TestEngine` SPI, Launcher API) |
| JUnit Jupiter    | Programming model and extension model for writing tests |
| JUnit Vintage    | Compatibility engine for running JUnit 3 and JUnit 4 tests on the Platform |

This modular design means IDEs and build tools depend on the Platform, while different test engines plug in independently.

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

**Lifecycle per class:** By default, JUnit creates a new test instance per method (`PER_METHOD`). Use `@TestInstance(Lifecycle.PER_CLASS)` if you need non-static `@BeforeAll`/`@AfterAll` or intentionally shared mutable state. Treat that shared state as a trade-off, not a default.

### `@Nested` for Test Organization

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

This produces a hierarchical report that reads like a specification. Each `@Nested` class can have its own lifecycle callbacks, creating a setup chain from outer to inner.

### `@ParameterizedTest`

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
    "2147483647, 1, 2147483648"
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
```

`@CsvSource` values are parsed as literals, not Java expressions. If you need computed values or richer fixtures, use `@MethodSource`.

### `@TestFactory` and Dynamic Tests

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

// assertAll — grouped assertions, reports ALL failures
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

@ExtendWith(TimingExtension.class)
class PerformanceSensitiveTest {
    @Test
    void shouldBeFast() { }
}
```

**ParameterResolver** injects custom objects into test methods:

```java
public class RandomParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return paramCtx.getParameter().getType() == Random.class;
    }

    @Override
    public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx) {
        return new Random(42);
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
@Timeout(5)
@Test
void shouldCompleteQuickly() { }

@RepeatedTest(100)
void shouldBeIdempotent(RepetitionInfo info) {
    System.out.println("Repetition " + info.getCurrentRepetition());
}

@Disabled("Blocked by JIRA-1234")
@Test
void pendingFeature() { }
```

### Recent Version Notes That Actually Matter

You do not need a blow-by-blow release table in an interview. The important 2025-2026 shifts are:

- `@AutoClose` is available in the modern JUnit line, so simple resource cleanup no longer needs handwritten `@AfterEach`.
- JUnit 6 introduced the Java 17 baseline and removed several long-deprecated Platform modules and APIs.
- JUnit 6 also requires modern Maven Surefire/Failsafe: versions **below 3.0.0 are no longer supported**.
- `@ParameterizedClass` exists, but it is still niche compared with `@ParameterizedTest`; know it, but do not over-index on it.

Example:

```java
class KafkaConsumerTest {
    @AutoClose
    Consumer<String, String> consumer = new KafkaConsumer<>(props());
}
```

### Parallel Test Execution

JUnit Jupiter can run tests concurrently on the same JVM. This can cut suite time significantly but exposes shared mutable state immediately.

Enable via `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
```

```java
@Execution(ExecutionMode.CONCURRENT)
class FastUnitTest { }

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Test
void mutatesSystemProps() { System.setProperty("x", "y"); }

@ResourceLock("orders-table")
@Test
void truncatesOrdersTable() { }
```

Parallel execution is usually safe for pure unit tests, risky for Spring context tests, and something you should enable deliberately rather than globally by reflex.

---

## AssertJ

### Why AssertJ over JUnit `Assertions` / Hamcrest

- **Fluent, type-aware chains:** `assertThat(list).hasSize(3).contains("A").doesNotContain("Z")`
- **Better failure messages:** expected and actual read well in failures
- **Rich collection / object assertions:** especially useful for DTOs and aggregates
- **One consistent API:** fewer mental context switches inside tests

### Everyday Assertions

```java
// Strings
assertThat("Hello, World").startsWith("Hello").endsWith("World").contains(",")
    .hasSize(12).matches("[A-Za-z, ]+");

// Numbers
assertThat(computed).isEqualTo(expected);
assertThat(price).isBetween(new BigDecimal("9.99"), new BigDecimal("10.01"));
assertThat(ratio).isCloseTo(0.3333, within(0.001));
assertThat(tax).isEqualByComparingTo(new BigDecimal("5.00"));

// Collections
assertThat(orders).hasSize(3)
    .extracting(Order::status)
    .containsExactly(PENDING, PAID, SHIPPED);

assertThat(map).containsEntry("env", "prod").containsKeys("region", "zone");

// Optional
assertThat(maybeUser).isPresent().get().extracting(User::email).isEqualTo("a@b.com");

// Exceptions
assertThatThrownBy(() -> service.deleteUser("unknown"))
    .isInstanceOf(UserNotFoundException.class)
    .hasMessageContaining("unknown")
    .hasNoCause();
```

### Soft Assertions

```java
@Test
void shouldMapDtoAllFields() {
    var dto = mapper.toDto(entity);

    SoftAssertions.assertSoftly(softly -> {
        softly.assertThat(dto.id()).isEqualTo("42");
        softly.assertThat(dto.name()).isEqualTo("Alice");
        softly.assertThat(dto.email()).isEqualTo("alice@x.com");
        softly.assertThat(dto.roles()).containsExactly("USER");
    });
}

@ExtendWith(SoftAssertionsExtension.class)
class DtoMappingTest {
    @Test
    void shouldMap(@InjectSoftAssertions SoftAssertions softly) { /* ... */ }
}
```

### Recursive Comparison

```java
assertThat(actualOrder)
    .usingRecursiveComparison()
    .isEqualTo(expectedOrder);

assertThat(actual)
    .usingRecursiveComparison()
    .ignoringFields("id", "createdAt", "updatedAt")
    .ignoringFieldsMatchingRegexes(".*\\.id$")
    .ignoringFieldsOfTypes(Instant.class, UUID.class)
    .isEqualTo(expected);

assertThat(actual)
    .usingRecursiveComparison()
    .withEqualsForType(BigDecimal::compareTo, BigDecimal.class)
    .isEqualTo(expected);
```

This is usually the cleanest way to assert on DTOs, event payloads, mapper output, and record-heavy models.

### Custom Assertions for Domain Types

```java
public class OrderAssert extends AbstractAssert<OrderAssert, Order> {
    public OrderAssert(Order actual) { super(actual, OrderAssert.class); }

    public static OrderAssert assertThat(Order actual) { return new OrderAssert(actual); }

    public OrderAssert isPaid() {
        isNotNull();
        if (actual.status() != OrderStatus.PAID) {
            failWithMessage("Expected order <%s> to be PAID but was <%s>",
                            actual.id(), actual.status());
        }
        return this;
    }

    public OrderAssert hasTotal(BigDecimal expected) {
        isNotNull();
        if (actual.total().compareTo(expected) != 0) {
            failWithMessage("Expected total <%s>, was <%s>", expected, actual.total());
        }
        return this;
    }
}

OrderAssert.assertThat(order).isPaid().hasTotal(new BigDecimal("99.99"));
```

For frequently used domain types, custom assertions pay off quickly.
