# Part 3 — Testcontainers and WireMock

## Testcontainers

### Core Concept

Testcontainers provides lightweight, disposable Docker containers for integration testing. Containers start for the test run and give you real infrastructure: real Postgres, real Kafka, real Redis, not in-memory substitutes that only approximate production behavior.

**Why not H2 by default?** In-memory databases often differ from production in SQL syntax, locking, JSON support, collations, extensions, and query planning. If the production behavior matters, use the production engine in a container.

### Common Modules

| Module group | Typical use |
|--------------|-------------|
| `postgresql`, `mysql`, `mariadb`, `oracle-free`, `mssqlserver` | Relational database integration tests |
| `kafka`, `redpanda`, `confluent-platform` | Messaging tests |
| `mongodb`, `cassandra`, `elasticsearch`, `neo4j` | NoSQL / search integration tests |
| `localstack` | AWS-compatible local service testing |
| `rabbitmq`, `pulsar` | Broker testing |
| `wiremock` (community) | HTTP stub server in a container |

Rule of thumb: do not reach for `GenericContainer` if a typed module already exists.

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
        .withInitScript("schema.sql");

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

        var users = userRepository.findByEmailDomain("example.com");
        assertThat(users).extracting(User::getName)
            .containsExactlyInAnyOrder("alice", "bob");
    }
}
```

### Kafka Testing

```java
@Testcontainers
@SpringBootTest
class OrderEventConsumerIT {

    // `org.testcontainers.containers.KafkaContainer` is deprecated.
    // Use the Kafka module types instead.
    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
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

        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                var order = orderRepository.findById("ORD-001");
                assertThat(order).isPresent();
                assertThat(order.get().getStatus()).isEqualTo(OrderStatus.CREATED);
            });
    }
}
```

The current Kafka module docs are explicit:

- `org.testcontainers.containers.KafkaContainer` is deprecated.
- Use `org.testcontainers.kafka.KafkaContainer` for `apache/kafka`.
- Use `org.testcontainers.kafka.ConfluentKafkaContainer` for `confluentinc/cp-kafka`.

### `GenericContainer` for Custom Services

```java
@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379)
    .waitingFor(Wait.forListeningPort());

@DynamicPropertySource
static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```

Use `GenericContainer` when there is no typed module or when you are testing a private/internal service image.

### Shared Containers for Expensive Dependencies

Starting a container per test class is often too slow. A common pattern is to share a container across many tests:

```java
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

This is often called the singleton pattern in Testcontainers discussions. It is useful, but it increases the risk of shared mutable state. If you do this, reset data aggressively between tests.

### Reusable Containers

Reusable containers are a **development-loop accelerator**, not a general testing default.

Important current-state facts from the official docs:

- The feature is still marked **experimental**.
- It is **not suited for CI**.
- To reuse a container, you must **start it manually** and opt in via environment or user-level config.
- Testcontainers docs explicitly say not to rely on JUnit integration or `try-with-resources` for this mode.

```java
class LocalDevDatabase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("app")
        .withUsername("app")
        .withPassword("app")
        .withReuse(true);

    static {
        POSTGRES.start();
    }
}
```

Enable reuse with either:

- `TESTCONTAINERS_REUSE_ENABLE=true`
- `~/.testcontainers.properties` containing `testcontainers.reuse.enable=true`

Do not sell this as a team-wide CI pattern. It is for local speed.

### Testcontainers Desktop

Testcontainers Desktop is an optional companion app for local development. Useful features:

- switch between supported container runtimes
- inspect running Testcontainers services
- expose stable fixed ports for debugging local services
- manage container lifecycle during local investigation

It is useful for developers, but the open-source libraries must still work without it.

---

## WireMock

### Core Concept

WireMock is an HTTP mock server for testing code that talks to external HTTP APIs. It runs a real HTTP server and responds based on request matching rules.

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
stubFor(get(urlEqualTo("/exact/path?param=value")));
stubFor(get(urlPathEqualTo("/just/path")));
stubFor(get(urlMatching("/api/users/[0-9]+")));
stubFor(get(urlPathTemplate("/api/users/{userId}")));

stubFor(get(anyUrl())
    .withHeader("Authorization", equalTo("Bearer token123"))
    .withHeader("X-Request-Id", matching("[a-f0-9-]{36}")));

stubFor(post("/api/orders")
    .withRequestBody(equalToJson("""
        { "item": "Widget", "quantity": 5 }
        """))
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
stubFor(get("/api/slow-endpoint")
    .willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

stubFor(get("/api/unstable")
    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

stubFor(get("/api/broken")
    .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
```

### Verification

```java
@Test
void shouldCallExpectedEndpoint() {
    stubFor(get("/api/data")
        .willReturn(ok("success")));

    client.fetchData();

    verify(1, getRequestedFor(urlEqualTo("/api/data")));
    verify(getRequestedFor(urlEqualTo("/api/data"))
        .withHeader("Authorization", matching("Bearer .*")));
}
```

### Stateful Stubs with Scenarios

```java
@Test
void shouldHandleRetryAfterFailure() {
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

### When to Prefer WireMock

Use WireMock when you need to test:

- HTTP serialization and deserialization
- status-code handling
- retries, backoff, and timeout behavior
- auth headers and request shape
- client-side resilience without calling the real dependency

For service-to-service compatibility across teams, add contract testing on top; do not pretend WireMock alone proves the real provider still matches your assumptions.
