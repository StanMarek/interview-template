# Part 2 — Mockito

## Current Version Context (April 2026)

- Mockito 5 is still the current major line.
- The official repository shows **5.23.0** as the latest release as of March 11, 2026.
- Mockito 5 switched the default mock maker to **inline** and requires **Java 11+**.

Practical takeaway: modern Mockito handles final classes, records, static methods, and constructor mocking out of the box in normal JVM builds.

## Mockito 5.x — What Changed vs. Mockito 3/4

| Change | Impact |
|--------|--------|
| Default `MockMaker` = inline | In normal JVM projects, `mockito-core` is enough. Final classes, final methods, `mockStatic`, and `mockConstruction` work without extra MockMaker setup. |
| Minimum JDK 11 | Mockito 5 requires Java 11 or newer. |
| `STRICT_STUBS` is the default in `MockitoExtension` | Unused or mismatched stubs fail fast. |
| Records work naturally | Records are final, but Mockito handles them with the inline mock maker. |
| PowerMock is effectively legacy | Do not introduce it in new code. Refactor where possible and use built-in Mockito features. |

Important nuance: because inline mocking is the default, the old `mockito-inline` artifact is generally **unnecessary** in 5.x JVM projects. The official docs do not describe it as the normal path anymore.

## Core Annotations

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Spy
    private NotificationService notificationService = new NotificationService();

    @InjectMocks
    private OrderService orderService;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;
}
```

**`@Mock` vs `@Spy`:**

| Aspect          | `@Mock` | `@Spy` |
|-----------------|---------|--------|
| Base behavior   | Methods return Mockito defaults | Real methods run unless stubbed |
| Use case        | Isolate from dependency | Partial mocking of a real object |
| Stubbing syntax | `when(...).thenReturn(...)` | Prefer `doReturn(...).when(spy)...` |

With spies, prefer `doReturn().when()` so stubbing does not call the real method.

## Stubbing

```java
when(orderRepository.findById("ORD-001")).thenReturn(Optional.of(testOrder));

when(paymentGateway.charge(any()))
    .thenReturn(PaymentResult.DECLINED)
    .thenReturn(PaymentResult.SUCCESS);

doReturn(Optional.of(testOrder)).when(orderRepository).findById("ORD-001");
doNothing().when(notificationService).sendEmail(any());
doThrow(new RuntimeException("DB down")).when(orderRepository).save(any());

when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
    Order order = invocation.getArgument(0);
    order.setId(UUID.randomUUID().toString());
    return order;
});
```

Use `thenAnswer` when the result depends on the input rather than a fixed canned response.

## Argument Matchers

```java
when(repository.findByStatus(any(OrderStatus.class))).thenReturn(List.of());
when(repository.findByName(eq("Widget"))).thenReturn(Optional.of(widget));
when(repository.findByPriceRange(anyDouble(), anyDouble())).thenReturn(List.of());

when(repository.save(argThat(order ->
    order.getStatus() == OrderStatus.PENDING && order.getLineItems().size() > 0
))).thenReturn(savedOrder);
```

Do not mix raw values with matchers in the same invocation:

```java
// BAD
when(service.process(any(), "literal"));

// GOOD
when(service.process(any(), eq("literal")));
```

## Argument Captors

```java
@Test
void shouldSaveOrderWithCorrectTimestamp() {
    orderService.createOrder(request);

    verify(orderRepository).save(orderCaptor.capture());

    Order savedOrder = orderCaptor.getValue();
    assertThat(savedOrder.getCreatedAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.SECONDS));
    assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
}

@Test
void shouldSendNotificationForEachItem() {
    orderService.processOrder(orderWithThreeItems);

    verify(notificationService, times(3)).notify(orderCaptor.capture());

    List<Order> captured = orderCaptor.getAllValues();
    assertThat(captured).extracting(Order::getItemName)
        .containsExactly("Item-A", "Item-B", "Item-C");
}
```

Capture arguments when the assertion belongs on the outbound object, not when a matcher would be clearer.

## Verification

```java
verify(orderRepository).save(any(Order.class));

verify(paymentGateway, times(1)).charge(any());
verify(notificationService, times(3)).sendEmail(any());
verify(auditLog, never()).logFailure(any());
verify(retryService, atLeast(2)).retry(any());
verify(cache, atMost(5)).evict(any());

InOrder inOrder = inOrder(paymentGateway, orderRepository, notificationService);
inOrder.verify(paymentGateway).charge(any());
inOrder.verify(orderRepository).save(any());
inOrder.verify(notificationService).sendConfirmation(any());

verifyNoMoreInteractions(orderRepository);
verifyNoInteractions(auditLog);
```

Use `verifyNoMoreInteractions` sparingly. It makes tests more brittle if collaborators legitimately gain new calls during refactors.

## BDDMockito

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

Useful when the team likes Given/When/Then phrasing in tests.

## Strict Stubbing

`STRICT_STUBS` is the default when using `MockitoExtension`. It fails the test if:

- A stubbed method is never called.
- A stubbed method is called with arguments that do not match any configured stubbing.

```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class StrictTest {
    @Mock OrderRepository repo;

    @Test
    void shouldOnlyStubWhatYouUse() {
        when(repo.findById("A")).thenReturn(Optional.of(orderA));
        when(repo.findById("B")).thenReturn(Optional.of(orderB));  // unused
        assertThat(service.process("A")).isNotNull();
    }
}

lenient().when(repo.findAll()).thenReturn(List.of());

@MockitoSettings(strictness = Strictness.LENIENT)
class LegacyTest { }
```

Use leniency as an escape hatch, not as the default.

## Mocking Java Records

```java
public record PricingRequest(String sku, int quantity, Currency currency) { }

@Test
void shouldMockRecord() {
    PricingRequest mock = mock(PricingRequest.class);
    when(mock.sku()).thenReturn("WIDGET-01");
    when(mock.quantity()).thenReturn(10);

    assertThat(service.price(mock)).isEqualTo(new BigDecimal("99.90"));
}
```

In practice, records are usually cheaper to instantiate than to mock. Prefer real values unless mocking actually removes noise.

## Mocking Static Methods

```java
@Test
void shouldMockCurrentTime() {
    var fixedInstant = Instant.parse("2024-01-15T10:00:00Z");

    try (MockedStatic<Instant> mockedInstant = mockStatic(Instant.class)) {
        mockedInstant.when(Instant::now).thenReturn(fixedInstant);

        var order = orderService.createOrder(request);
        assertThat(order.getCreatedAt()).isEqualTo(fixedInstant);
    }
}
```

This is legitimate, but it is still usually better to inject a `Clock` than to mock time statically everywhere.

## Mocking Constructors

```java
@Test
void shouldMockConstructor() {
    try (MockedConstruction<HttpClient> mocked = mockConstruction(HttpClient.class,
            (mock, context) -> {
                when(mock.send(any())).thenReturn(new Response(200, "OK"));
            })) {

        var result = service.callExternalApi();
        assertThat(result.getStatusCode()).isEqualTo(200);
    }
}
```

Use this as a migration tool, not as an excuse to keep hard-wired `new` calls forever.

## Full Service Test Example

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
        var request = new PaymentRequest("CUST-001", new BigDecimal("99.99"), Currency.USD);
        var gatewayResponse = new GatewayResponse("TXN-123", GatewayStatus.APPROVED);
        when(gateway.charge(any(ChargeRequest.class))).thenReturn(gatewayResponse);
        when(transactionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = paymentService.processPayment(request);

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

## Senior-Level Heuristics

- Mock your **own seam**, not third-party implementation types.
- Verify **behavior that matters**, not every collaborator interaction.
- Prefer **fakes or real infrastructure** over elaborate mocking for data stores and HTTP boundaries.
- If static or constructor mocking becomes common in a codebase, the design is telling you something.
