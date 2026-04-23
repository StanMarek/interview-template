# Part 4 — Spring Boot Testing

## Why Slices Matter

`@SpringBootTest` starts the full application context. That is useful for cross-layer integration, but it is wasteful for controller-only, repository-only, or JSON-only tests.

Use the narrowest test scope that still exercises the behavior you care about.

| Slice | Loads | Use for |
|-------|-------|---------|
| `@WebMvcTest` | MVC infra: controllers, advice, filters, converters | Blocking REST controllers |
| `@WebFluxTest` | WebFlux infra: controllers, functional routing support, codecs | Reactive endpoints |
| `@DataJpaTest` | JPA repositories, `EntityManager`, rollback | JPA queries and mappings |
| `@DataJdbcTest` | Spring Data JDBC | JDBC repositories |
| `@JdbcTest` | `JdbcTemplate`, JDBC infra | SQL-heavy code |
| `@JsonTest` | Jackson / Gson / JSON-B auto-config | Serialization tests |
| `@RestClientTest` | client-side HTTP support and `MockRestServiceServer` | Outbound HTTP clients |

---

## `@WebMvcTest` — Testing a Controller

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean OrderService orderService;

    @Test
    void shouldReturnOrderAsJson() throws Exception {
        given(orderService.find("ORD-1"))
            .willReturn(new Order("ORD-1", OrderStatus.PAID, BigDecimal.TEN));

        mockMvc.perform(get("/orders/ORD-1").accept(APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("ORD-1"))
            .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void shouldReturn404WhenNotFound() throws Exception {
        given(orderService.find("MISSING")).willThrow(new OrderNotFoundException());

        mockMvc.perform(get("/orders/MISSING"))
            .andExpect(status().isNotFound());
    }
}
```

### `@MockitoBean` vs `@MockBean`

As of Spring Boot 3.4+:

- Boot's `@MockBean` / `@SpyBean` are **deprecated for removal in 4.0**.
- Spring Framework 6.2 introduced `@MockitoBean` / `@MockitoSpyBean`.
- Prefer the Framework annotations in new code.

That is the current direction of travel. If you see `@MockBean` in an older codebase, it is still common, but it is no longer the forward-looking answer.

---

## `@DataJpaTest` — Testing a Repository

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OrderRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OrderRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void shouldFindByCustomer() {
        em.persist(new Order("O1", "cust-1", BigDecimal.TEN));
        em.persist(new Order("O2", "cust-2", BigDecimal.ONE));
        em.flush();

        assertThat(repository.findByCustomerId("cust-1"))
            .extracting(Order::id).containsExactly("O1");
    }
}
```

`@DataJpaTest` rolls back after each test by default, which keeps data isolated without extra boilerplate.

---

## `@WebFluxTest` — Testing a Reactive Endpoint

```java
@WebFluxTest(OrderController.class)
class OrderControllerWebFluxTest {

    @Autowired WebTestClient client;
    @MockitoBean OrderService orderService;

    @Test
    void shouldStreamOrders() {
        given(orderService.stream())
            .willReturn(Flux.just(new Order("A", PAID, TEN), new Order("B", PAID, ONE)));

        client.get().uri("/orders/stream")
            .accept(TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(Order.class).hasSize(2);
    }
}
```

---

## `@JsonTest` — Testing a Serializer

```java
@JsonTest
class OrderJsonTest {

    @Autowired JacksonTester<Order> json;

    @Test
    void shouldSerialize() throws IOException {
        var order = new Order("ORD-1", PAID, new BigDecimal("99.99"));

        assertThat(json.write(order))
            .hasJsonPathStringValue("$.id")
            .extractingJsonPathStringValue("$.status").isEqualTo("PAID");
    }
}
```

---

## `@ServiceConnection` (Spring Boot 3.1+)

Spring Boot 3.1 introduced first-class Testcontainers integration via `@ServiceConnection`.

```java
@SpringBootTest
@Testcontainers
class OrderServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired OrderService orderService;
}
```

Key points:

- `@ServiceConnection` removes most `@DynamicPropertySource` boilerplate.
- `name` is required when Spring cannot infer the service type from a `GenericContainer`.
- The built-in mapping list is fairly broad now; think of Postgres, MySQL, MongoDB, Redis, Neo4j, Kafka, RabbitMQ, Cassandra, LDAP, Zipkin, and more.

### Important Lifecycle Nuance

Current Spring Boot docs make an important distinction:

- JUnit-managed `@Container` fields are fine and common.
- But when your application beans rely on container-backed services during shutdown, **Spring-managed container beans** give better lifecycle guarantees.

That matters in larger integration suites where shutdown ordering can produce noisy failures.

---

## Development-Time Testcontainers with Spring Boot

Spring Boot supports using Testcontainers not only in tests, but also for local development.

The important correction here is operational:

- The app must run on the **test classpath**, not the normal `bootRun` path.
- For Maven, use **`spring-boot:test-run`**.
- For Gradle, use **`bootTestRun`**.

### Typical Setup

```java
// src/test/java/.../TestcontainersConfig.java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
```

```java
// src/test/java/.../TestMyApplication.java
public class TestMyApplication {
    public static void main(String[] args) {
        SpringApplication.from(MyApplication::main)
            .with(TestcontainersConfig.class)
            .run(args);
    }
}
```

Then launch:

- Maven: `./mvnw spring-boot:test-run`
- Gradle: `./gradlew bootTestRun`

This gives you Java-defined dev services without a separate `docker compose up` workflow.

---

## Picking the Right Scope

```
      /  @SpringBootTest  \          Full context, HTTP server, containers — slowest
     /----------------------\
    /   Slice tests          \       @WebMvcTest, @DataJpaTest, ... — medium
   /--------------------------\
  /   Plain JUnit + Mockito    \    No Spring — fastest
 /______________________________\
```

Rule of thumb:

- Pure business logic: plain JUnit + Mockito or fakes
- Controller ownership: `@WebMvcTest` / `@WebFluxTest`
- Repository ownership: `@DataJpaTest` / `@JdbcTest`
- Full wiring across layers: `@SpringBootTest`

If a test uses Spring only because "that is how we always do it", its scope is probably too wide.
