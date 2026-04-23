# Part 6 — Test Design and Reliability

## Test Doubles Taxonomy

"Test double" is the umbrella term for any stand-in used in a test.

| Double | What it is | Typical Java equivalent |
|--------|------------|-------------------------|
| Dummy  | Passed only to satisfy a parameter | filler object / ignored mock |
| Stub   | Returns canned answers for state verification | `when(x).thenReturn(y)` |
| Fake   | Real working implementation, unsuitable for production | in-memory repo |
| Spy    | Real object that also records interactions | Mockito `@Spy` |
| Mock   | Interaction-verifying double | Mockito `@Mock` + `verify(...)` |

Two styles fall out of that:

- **State verification**: use stubs/fakes and assert on the result.
- **Behavior verification**: use mocks and assert on collaborator interaction.

Neither is "the one true way". The question is which style gives confidence with the least coupling.

## Given / When / Then

Every test should separate setup, action, and verification.

```java
@Test
void shouldApplyBulkDiscount() {
    // Given
    var cart = new ShoppingCart();
    cart.addItem(new Item("Widget", 10.00), 50);

    // When
    var invoice = invoiceService.generateInvoice(cart);

    // Then
    assertThat(invoice.getDiscount()).isEqualTo(0.10);
    assertThat(invoice.getTotal()).isEqualByComparingTo(new BigDecimal("450.00"));
}
```

This is not cosmetics. It reduces cognitive load when a test fails.

## Test Data Builders

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
```

Builders localize fixture complexity. If the domain object changes, you update one helper instead of dozens of tests.

## Object Mother

```java
public class TestOrders {
    public static Order pendingOrder() {
        return anOrder().withStatus(OrderStatus.PENDING).build();
    }

    public static Order paidOrder() {
        return anOrder().withStatus(OrderStatus.PAID).build();
    }

    public static Order highValueOrder() {
        return anOrder()
            .withItems(List.of(new LineItem("Premium", 1, new BigDecimal("10000.00"))))
            .build();
    }
}
```

Object Mothers are useful, but they can become giant fixture catalogs. Builders usually scale better.

## What Makes a Good Test

| Property | Description |
|----------|-------------|
| Isolated | No dependence on other tests |
| Deterministic | Same result every time |
| Fast | Fast enough to be run often |
| Readable | Failure points are obvious |
| Behavior-focused | Asserts observable outcomes, not incidental implementation |

## Test Behavior, Not Implementation

```java
// BAD
@Test
void shouldCallValidateAndSaveInOrder() {
    orderService.createOrder(request);
    InOrder inOrder = inOrder(orderService);
    inOrder.verify(orderService).validate(request);
    inOrder.verify(orderService).persistOrder(any());
}

// GOOD
@Test
void shouldCreateOrderWithCorrectStatus() {
    var result = orderService.createOrder(request);

    assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(orderRepository.findById(result.getId())).isPresent();
}
```

The first test breaks when you refactor internals without changing behavior. The second protects the contract the caller actually cares about.

## Don't Mock What You Don't Own

Mocking third-party libraries usually couples tests to implementation details you do not control.

```java
// BAD
when(httpClient.send(any(), any())).thenReturn(httpResponse);

// BETTER
public interface PaymentGateway {
    ChargeResult charge(ChargeRequest request);
}
```

Mock your adapter interface in unit tests. Test the adapter itself with WireMock or Testcontainers.

## Awaitility — Testing Asynchronous Code

Never use `Thread.sleep()` as your default async testing tool.

```java
import static org.awaitility.Awaitility.await;

@Test
void shouldConsumeEventAndPersistOrder() {
    kafkaTemplate.send("orders", new OrderCreatedEvent("ORD-1"));

    await().atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
            assertThat(orderRepository.findById("ORD-1")).isPresent();
        });
}
```

Awaitility expresses:

- how long you are willing to wait
- how often to poll
- what condition eventually must become true

That is much clearer than a magic sleep duration.

## Snapshot Testing

For complex output such as big JSON payloads, generated SQL, or rendered templates, snapshot testing can be more maintainable than giant inline literals.

```java
@Test
void shouldSerializeOrderApproval() {
    var order = TestOrders.fullOrder();
    Approvals.verify(objectMapper.writeValueAsString(order));
}
```

Use snapshot tests carefully. If a snapshot is huge, it can hide what actually changed and make review noisy.

## Flakiness — Diagnosis Playbook

Flaky tests destroy trust in CI. Treat them as defects, not annoyances.

1. Quarantine rather than silently delete.
2. Reproduce locally in a loop.
3. Identify whether the root cause is shared state, time, async waits, order dependence, or environment contention.
4. Fix the cause, not the symptom.

Common causes:

- shared mutable state
- system clock dependence
- randomized IDs or seeds
- test order dependence
- unbounded async waits
- ports/files/network contention

Useful techniques:

- `@RepeatedTest(50)` for reproduction
- injected `Clock`
- fixed `Random` seed
- deterministic IDs
- data cleanup or rollback per test

## Parallel Test Execution in Practice

Parallel test execution can help a lot, but the safe defaults differ by test type.

- Pure unit tests: usually safe to parallelize
- Spring context tests: risky if they mutate shared DB state
- Container-heavy integration tests: often better with process-level parallelism than in-JVM class-level concurrency
- Tests touching system properties or other JVM-global state: guard with `@ResourceLock`

If you are using JUnit-level parallel execution, revisit the JUnit configuration in [Part 1](./01-testing-strategy-junit-assertj.md).

For integration tests, Maven Surefire forked JVMs are often safer:

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <forkCount>4</forkCount>
    <reuseForks>false</reuseForks>
  </configuration>
</plugin>
```

This is slower than fully shared in-JVM concurrency, but it avoids a whole class of shared-memory failures.

## Senior-Level Summary

Good test design is not about maximizing the number of tests. It is about maximizing signal:

- the right scope
- the right seams
- stable data
- readable intent
- failures that explain themselves
