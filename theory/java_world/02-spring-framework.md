# Spring Framework — Senior Engineer Interview Preparation

> **Baseline (April 2026)**: Spring Framework 6.2.x, Spring Boot 3.5.x, Spring Security 6.5.x, Spring Data 2025.x, Java 17 minimum (Java 21 recommended for virtual threads). `jakarta.*` replaces `javax.*` throughout Spring 6 / Boot 3. Spring Framework 7.0 and Spring Boot 4 are in milestone/RC; production targets remain 6.2 / 3.5.

---

## 1. Spring Core / IoC Container

### How the Container Works

The Spring IoC container manages object creation, wiring, and lifecycle. The two main container types:
- `BeanFactory`: The base container contract — `getBean()` lazily resolves on demand.
- `ApplicationContext`: Extends `BeanFactory` (adds AOP, events, i18n, environment abstraction). By default it **pre-instantiates singletons eagerly at startup**.

> Note: `ApplicationContext` *extends* `BeanFactory` — they are not alternatives. The laziness contrast is about default behavior: `ApplicationContext` pre-instantiates singletons eagerly; `BeanFactory`'s basic contract is lazy on `getBean()`. Use `scope="prototype"` or `@Lazy` to defer `ApplicationContext` instantiation.

**Bean Lifecycle (in order)**:
1. Instantiation (constructor or factory method)
2. Populate properties (dependency injection)
3. `BeanNameAware.setBeanName()`
4. `BeanFactoryAware.setBeanFactory()`
5. `ApplicationContextAware.setApplicationContext()`
6. `BeanPostProcessor.postProcessBeforeInitialization()`
7. `@PostConstruct` / `InitializingBean.afterPropertiesSet()` / `init-method`
8. `BeanPostProcessor.postProcessAfterInitialization()` ← **AOP proxies created here**
9. Bean is ready for use
10. `@PreDestroy` / `DisposableBean.destroy()` / `destroy-method`

> Note: `ApplicationContextAware` is actually invoked DURING `postProcessBeforeInitialization` via `ApplicationContextAwareProcessor` — it's not a separate step before BPPs. Only the bean-factory-level Aware interfaces (`BeanNameAware`, `BeanClassLoaderAware`, `BeanFactoryAware`) fire before BPPs. The same applies to `EnvironmentAware`, `ApplicationEventPublisherAware`, `ResourceLoaderAware`, etc. — all are invoked by `ApplicationContextAwareProcessor` (a `BeanPostProcessor`) inside the before-init phase.

### Bean Scopes

| Scope | Description | Risk |
|-------|-------------|------|
| singleton (default) | One instance per container | Shared mutable state → thread-safety issues |
| prototype | New instance per injection | Not managed after creation (no @PreDestroy) |
| request | One per HTTP request | Only in web-aware context |
| session | One per HTTP session | Serialization concerns in clustered environments |
| application | One per ServletContext | Similar to singleton but per ServletContext |

**Scope Mismatch Problem**: Injecting a prototype/request-scoped bean into a singleton means the singleton always sees the same instance.

```java
// BROKEN — prototype injected once into singleton
@Component
public class SingletonService {
    @Autowired
    private PrototypeBean proto; // Same instance forever!
}

// FIX 1 — Provider/ObjectFactory
@Component
public class SingletonService {
    @Autowired
    private ObjectProvider<PrototypeBean> protoProvider;

    public void doWork() {
        PrototypeBean fresh = protoProvider.getObject(); // New instance each time
    }
}

// FIX 2 — @Lookup method injection
@Component
public abstract class SingletonService {
    @Lookup
    protected abstract PrototypeBean createProto();
}

// FIX 3 — Scoped proxy
@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class PrototypeBean { }
```

### Dependency Injection Internals

**Injection Types** (in order of preference):
1. **Constructor injection** (recommended): Immutable, required deps, testable, no reflection
2. **Setter injection**: Optional deps, allows re-configuration
3. **Field injection**: Convenient but untestable without reflection, hides dependencies

**Circular Dependencies**:
- Constructor injection → `BeanCurrentlyInCreationException` (fails fast)
- Setter/field injection → Resolved via early reference exposure (three-level cache internally), but is a design smell
- **Disabled by default since Spring Boot 2.6** (not Boot 3); must opt-in with `spring.main.allow-circular-references=true`. This setting has carried forward into Boot 3+.

**How @Autowired Resolution Works**:
1. Match by type
2. If multiple candidates → match by qualifier (`@Qualifier`)
3. If still ambiguous → `@Primary` bean wins
4. If still ambiguous → match by bean name (field/parameter name)
5. If all but one candidate are `@Fallback` (Spring 6.2+) → the non-fallback bean wins
6. Otherwise → `NoUniqueBeanDefinitionException`

### Spring 6.2 Bean Features

```java
// @Fallback — inverse of @Primary. Mark a default/stub that loses to any real candidate.
@Bean @Fallback
MyService stubMyService() { return new StubMyService(); }

// Background bean initialization — reduces startup time for slow singletons.
// Dependent beans with non-lazy injection points auto-wait. Combine with @Lazy
// to allow completion at first access.
@Bean(bootstrap = Bean.Bootstrap.BACKGROUND)
MyExpensiveComponent myComponent() { ... }
```

Also new in 6.2: `@Reflective` / `@RegisterReflection` / `@ReflectionScan` for ergonomic native-image hint registration, and observation instrumentation is now applied to `@Scheduled` methods.

---

## 2. Spring AOP (Aspect-Oriented Programming)

### How Proxying Works

Spring AOP uses **proxy-based** AOP (not bytecode weaving like AspectJ):
- **JDK Dynamic Proxy**: For beans implementing interfaces. Creates proxy implementing same interfaces.
- **CGLIB Proxy**: For classes without interfaces. Creates subclass at runtime. Cannot proxy `final` methods/classes.

Spring Boot defaults to CGLIB proxies (`spring.aop.proxy-target-class=true` since Boot 2.0).

### The Self-Invocation Problem

```java
@Service
public class OrderService {
    @Transactional
    public void createOrder(Order order) {
        // ... create order ...
        sendConfirmation(order); // SELF-INVOCATION — bypasses proxy!
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendConfirmation(Order order) {
        // This @Transactional is IGNORED because called via 'this', not proxy
    }
}
```

**Fixes**:
- Inject self: `@Autowired @Lazy private OrderService self;` then `self.sendConfirmation(order);`
- Extract to separate bean
- Use `AopContext.currentProxy()` (fragile, not recommended)
- Use full AspectJ weaving

### Advice Types

```java
@Aspect
@Component
public class LoggingAspect {

    // Before — runs before method
    @Before("execution(* com.app.service.*.*(..))")
    public void logBefore(JoinPoint jp) {
        log.info("Calling {}", jp.getSignature());
    }

    // Around — wraps method (most powerful)
    @Around("@annotation(com.app.Timed)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            return pjp.proceed(); // MUST call proceed()
        } finally {
            log.info("{}ms", (System.nanoTime() - start) / 1_000_000);
        }
    }

    // AfterReturning — access return value
    @AfterReturning(pointcut = "execution(* com.app.service.*.*(..))", returning = "result")
    public void logResult(Object result) { }

    // AfterThrowing — access exception
    @AfterThrowing(pointcut = "execution(* com.app.service.*.*(..))", throwing = "ex")
    public void logException(Exception ex) { }
}
```

---

## 3. Spring Transaction Management

### @Transactional Internals

`@Transactional` works via AOP proxy. The proxy intercepts the call, obtains a transaction from the `PlatformTransactionManager`, and commits/rolls back based on outcome.

**Key Attributes**:

| Attribute | Default | Notes |
|-----------|---------|-------|
| propagation | REQUIRED | Join existing or create new |
| isolation | DEFAULT (DB default) | Maps to DB isolation level |
| readOnly | false | Optimization hint; some DBs optimize queries |
| timeout | -1 (none) | Seconds before transaction timeout |
| rollbackFor | RuntimeException, Error | Checked exceptions do NOT trigger rollback by default! |

### Propagation Levels

| Propagation | Behavior |
|-------------|----------|
| REQUIRED | Join existing or create new (most common) |
| REQUIRES_NEW | Suspend current, create new (independent commit/rollback) |
| NESTED | Savepoint within existing transaction |
| SUPPORTS | Use existing if available, otherwise non-transactional |
| NOT_SUPPORTED | Suspend existing, run non-transactionally |
| MANDATORY | Must have existing transaction, else exception |
| NEVER | Must NOT have existing transaction, else exception |

### Critical Pitfalls

```java
// PITFALL 1: Checked exception doesn't rollback
@Transactional
public void process() throws BusinessException {
    repository.save(entity);
    throw new BusinessException(); // Transaction COMMITS! Not rolled back.
}
// FIX:
@Transactional(rollbackFor = BusinessException.class)

// PITFALL 2: @Transactional on private method — ignored
@Transactional
private void internalMethod() { } // Proxy can't intercept private methods

// PITFALL 3: @Transactional on non-Spring-managed class — ignored
// The class must be a Spring bean

// PITFALL 4: Catching exception inside @Transactional
@Transactional
public void process() {
    try {
        riskyOperation(); // throws RuntimeException
    } catch (Exception e) {
        log.error("Swallowed", e);
        // Transaction is STILL marked rollback-only by the proxy
        // Commit will fail: UnexpectedRollbackException
    }
}
```

### Read-Only Optimization

```java
@Transactional(readOnly = true)
public List<User> findAll() {
    // Hibernate: sets FlushMode to MANUAL (skips dirty checking)
    // Some JDBC drivers: send SET TRANSACTION READ ONLY to DB
    // Some connection pools: route to read replica
    return userRepository.findAll();
}
```

### Reactive Transactions & Virtual Threads

- **Reactive**: use `@Transactional` with `R2dbcTransactionManager` (reactive propagation) — JPA/JDBC are blocking and not compatible.
- **Virtual threads**: `@Transactional` is fully compatible. JDBC connection pools (HikariCP) are the main bottleneck since they're still platform-thread-sized. Keep `pool-size <= db_max_connections` to avoid pinning.
- **`TransactionalOperator`** (programmatic reactive): use for fine-grained boundaries inside reactor chains where `@Transactional` wrapping is too coarse.

---

## 4. Spring Boot Auto-Configuration

### How It Works

1. `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`
2. `@EnableAutoConfiguration` triggers `AutoConfigurationImportSelector`
3. Reads `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3+) or `META-INF/spring.factories` (Boot 2)
4. Each auto-config class uses `@Conditional` annotations to decide if it should apply

**Key Conditional Annotations**:

| Annotation | Condition |
|-----------|-----------|
| `@ConditionalOnClass` | Classpath contains the class |
| `@ConditionalOnMissingBean` | No bean of this type exists yet |
| `@ConditionalOnProperty` | Config property has specific value |
| `@ConditionalOnBean` | Bean of this type exists |
| `@ConditionalOnWebApplication` | Running in a web context |

```java
// Example: custom auto-configuration
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnProperty(name = "cache.type", havingValue = "redis")
public class RedisCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        return RedisCacheManager.builder(factory).build();
    }
}
```

**Debugging auto-configuration**: Run with `--debug` or set `debug=true` to see the conditions evaluation report.

### Configuration Properties

```java
@ConfigurationProperties(prefix = "app.mail")
@Validated
public record MailProperties(
    @NotBlank String host,
    @Min(1) @Max(65535) int port,
    @Email String from,
    Duration timeout  // Spring auto-converts "30s", "5m" etc.
) {}
```

**Property Sources (priority order)**: Command-line args > `SPRING_APPLICATION_JSON` > OS env vars > application-{profile}.yml > application.yml > `@PropertySource` > defaults.

### Notable Boot 3.3–3.5 Changes

| Area | Change |
|------|--------|
| `spring.factories` | Deprecated for auto-config; use `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Restructured `spring-boot` modules | 3.5 split many starters into narrower modules; `spring-boot-parent` is no longer published |
| SSL bundles | `spring.ssl.bundle.*` centralized keystore/truststore config, reusable across WebClient, RestClient, Kafka, Redis, DataSource |
| Profile naming | 3.5 restricts profile names to `[A-Za-z0-9_-]`, may not start/end with `-`/`_` |
| Heap-dump actuator | `access=NONE` by default in 3.5 |
| OpenTelemetry | `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES` honored natively |
| Docker Compose & Testcontainers | First-class integration (`@ServiceConnection`, `spring.docker.compose.*`) |

---

## 5. HTTP Clients (RestClient, WebClient, HTTP Interface)

Since Spring Framework 6.1, `RestTemplate` is **in maintenance mode**. Prefer one of:

| Client | Style | Needs WebFlux? | Use When |
|--------|-------|----------------|----------|
| `RestClient` | Synchronous, fluent | No | New synchronous code; drop-in upgrade from `RestTemplate` |
| `WebClient` | Reactive (Mono/Flux) | Yes (`spring-webflux`) | Reactive pipelines, streaming, high-concurrency |
| `@HttpExchange` interface | Declarative | No (RestClient adapter) | Typed client-as-interface, like Feign |
| `RestTemplate` | Synchronous | No | Legacy code only |

### RestClient (Spring 6.1+)

```java
@Bean
RestClient restClient(RestClient.Builder builder) {
    return builder
        .baseUrl("https://api.example.com")
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .requestInterceptor(new BearerTokenInterceptor())
        .build();
}

// Usage — same fluent API as WebClient, but synchronous
User user = restClient.get()
    .uri("/users/{id}", id)
    .retrieve()
    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
        throw new NotFoundException();
    })
    .body(User.class);

// Boot 3.4+ auto-configures a RestClient.Builder with Micrometer observation + SSL bundles.
```

**Gotcha**: `retrieve()` returns a builder-like object; you must call a terminal operation like `.body(Class)`, `.toEntity(Class)`, or `.toBodilessEntity()` to actually execute the request. This has always been the behavior — `retrieve()` alone is not enough.

### Declarative `@HttpExchange` Clients

```java
public interface GitHubClient {
    @GetExchange("/repos/{owner}/{repo}")
    Repo getRepo(@PathVariable String owner, @PathVariable String repo);

    @PostExchange("/repos/{owner}/{repo}/issues")
    Issue createIssue(@PathVariable String owner, @PathVariable String repo,
                      @RequestBody CreateIssue body);
}

@Bean
GitHubClient gitHubClient(RestClient restClient) {
    RestClientAdapter adapter = RestClientAdapter.create(restClient);
    return HttpServiceProxyFactory.builderFor(adapter).build()
        .createClient(GitHubClient.class);
}
```

Replaces most Feign use-cases without adding Spring Cloud. Works with both `RestClient` (sync) and `WebClient` (reactive) adapters.

### WebClient Timeouts & Resiliency

```java
WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .responseTimeout(Duration.ofSeconds(5))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2_000)
    ))
    .defaultStatusHandler(HttpStatusCode::is5xxServerError,
        res -> res.bodyToMono(String.class)
            .map(body -> new UpstreamException(body)))
    .build();
```

---

## 6. Virtual Threads & Project Loom Integration

Spring Boot 3.2+ added Loom support; 3.4/3.5 expanded it to MVC async, `@Async`, schedulers, messaging listeners, and HTTP clients.

### Enable

```yaml
spring:
  threads:
    virtual:
      enabled: true   # Requires Java 21+
```

When enabled, Boot wires virtual threads into:
- Tomcat request processing (one VT per request)
- `@Async` executor (`SimpleAsyncTaskExecutor` in virtual-thread mode)
- Spring MVC async request processing (`AsyncTaskExecutor`)
- Scheduled tasks (`TaskScheduler`)
- Kafka / RabbitMQ / JMS listener containers

### Pitfalls (senior-level)

| Pitfall | Why |
|---------|-----|
| **Pinning on `synchronized`** | A VT holding a monitor cannot unmount → throughput collapses. Use `ReentrantLock` for contended critical sections. (Fixed in JDK 24 via JEP 491, still relevant on 21.) |
| **ThreadLocal memory blowup** | Millions of VTs × ThreadLocals = heap pressure. Use **Scoped Values** (finalized in JDK 25, JEP 506) or explicit context propagation. |
| **JDBC connection pools** | HikariCP default (10) becomes the concurrency ceiling. Size pool for DB limits, use VTs for request threads only. |
| **Not a speedup for CPU-bound code** | VTs help I/O-bound workloads. CPU-bound code still needs `ForkJoinPool`. |

> For canonical version-status of every Loom-adjacent feature, see [`11-java-versions-evolution.md`](11-java-versions-evolution.md) — do not duplicate JEP numbers here, they drift.

### Structured Concurrency (still preview through JDK 25, JEP 505 — 5th preview)

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> user = scope.fork(() -> userClient.get(id));
    Subtask<List<Order>> orders = scope.fork(() -> orderClient.list(id));
    scope.join().throwIfFailed();
    return new Dashboard(user.get(), orders.get());
}
```

Useful for fan-out calls inside a controller; cancellation is propagated if any subtask fails.

---

## 7. Observability (Micrometer, @Observed, Tracing)

Spring Boot 3 replaced Spring Cloud Sleuth with **Micrometer Observation** + **Micrometer Tracing** (Brave/OTel bridge).

```java
// Declarative observation
@Observed(name = "order.process", contextualName = "process-order",
          lowCardinalityKeyValues = {"tier", "premium"})
public Order process(Long id) { ... }

// Programmatic
Observation.createNotStarted("checkout", registry)
    .lowCardinalityKeyValue("region", "eu")
    .observe(() -> doCheckout());
```

Each `Observation` emits metrics, a tracing span, and log correlation automatically.

**Boot 3.5 specifics**:
- Honors `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES`
- `management.tracing.sampling.probability` controls sampling
- `spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel` is the standard trio
- `@Scheduled` methods get automatic observation in Spring 6.2

---

## 8. Problem Details (RFC 7807)

Standardized error responses in Spring 6 / Boot 3. `ProblemDetail` + `ErrorResponse` replace ad-hoc error DTOs.

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleNotFound(ProductNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://api.example.com/errors/product-not-found"));
        pd.setTitle("Product not found");
        pd.setProperty("productId", ex.getProductId());
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}

// Or throw an ErrorResponseException for status + message in one step
throw new ErrorResponseException(HttpStatus.CONFLICT,
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Duplicate SKU"), null);
```

Enable globally:
```yaml
spring:
  mvc:
    problemdetails:
      enabled: true   # Also spring.webflux.problemdetails.enabled for WebFlux
```

Response is `application/problem+json`:
```json
{ "type": "...", "title": "...", "status": 404, "detail": "...", "instance": "/api/products/42" }
```

---

## 9. AOT & GraalVM Native Image

Spring Boot 3 ships an AOT engine: `@Configuration` classes, bean definitions, property bindings, and proxy chains are analyzed at **build time** and generated as source code + reachability metadata consumable by GraalVM `native-image`.

### Build

```bash
# Maven
mvn -Pnative native:compile
# Gradle
./gradlew nativeCompile
```

Outputs a native executable — typical Spring Boot app starts in ~50ms, ~100MB RSS (vs ~1s / ~300MB on JVM).

### Programming Constraints

| Constraint | Consequence |
|------------|-------------|
| Classpath is fixed at build time | No runtime classpath scanning; `@Profile` is evaluated in a "build profile" pass then frozen |
| Reflection needs hints | Use `@RegisterReflectionForBinding`, `@Reflective` (Spring 6.2), or `reachability-metadata.json` |
| No runtime bytecode generation | CGLIB proxies, dynamic `@Configuration` subclassing pre-generated via AOT |
| Conditional beans frozen | `@ConditionalOnProperty` evaluated at build time by default (use `spring.profiles.active` at build) |

### When to Use

Good for: FaaS (Lambda/Cloud Run), CLI tools, short-lived containers, tight scaling windows.
Bad for: JIT-heavy throughput workloads (C2 ultimately beats AOT on long-running services), dynamic plugin architectures.

### CDS (Class Data Sharing) Alternative

Spring Boot 3.3+ supports AppCDS training runs — `-XX:ArchiveClassesAtExit=app.jsa` — giving ~30–50% faster JVM startup with no native-image tradeoffs. Often the pragmatic middle ground.

---

## 10. Spring Modulith

A modular-monolith framework for organizing a Spring Boot app into **application modules** with enforced boundaries, event-driven communication, integration testing, and architecture documentation.

```
com.acme.shop
├── order          ← module (public API)
│   ├── Order.java
│   └── internal   ← internal package (not visible to other modules)
├── inventory
└── billing
```

### Core Features

```java
// 1. Module-aware event listening (runs in a separate transaction, async-capable)
@Component
class OrderNotifier {
    @ApplicationModuleListener
    void on(OrderPlaced event) { /* ... */ }
}

// 2. Architecture tests — fail build if modules depend on each other's internals
@Test void verifiesModuleStructure() {
    ApplicationModules.of(ShopApplication.class).verify();
}

// 3. Integration test a single module
@ApplicationModuleTest
class OrderModuleTests {
    @Autowired Orders orders;
    @Test void publishesEvent(Scenario scenario) {
        scenario.stimulate(() -> orders.place(new Order()))
                .andWaitForEventOfType(OrderPlaced.class)
                .toArriveAndVerify(evt -> assertThat(evt.orderId()).isNotNull());
    }
}
```

### Event Publication Registry

Solves "what happens if the listener fails after the transaction commits?" — events are persisted in a `event_publication` table inside the same transaction as the domain change (transactional outbox pattern). A re-publisher retries failed listeners on restart.

**Status (April 2026)**: Modulith 1.4.x (GA for Boot 3.5), 2.0 M1 tracks Spring Boot 4 / Framework 7, 2.1 M4 adds JobRunr-backed event externalization. A common senior question: "when would you reach for Modulith vs microservices?" — answer: Modulith when the bounded contexts are real but you don't yet need independent deployability, scaling, or heterogeneous data stores.

---

## 11. Spring Security

### Security Filter Chain

Spring Security inserts a `FilterChainProxy` (via `DelegatingFilterProxy`) into the servlet filter chain. Internally it maintains an ordered list of security filters:

```
Request → DisableEncodeUrlFilter
        → WebAsyncManagerIntegrationFilter
        → SecurityContextHolderFilter        (Security 6 replaced SecurityContextPersistenceFilter)
        → HeaderWriterFilter
        → CsrfFilter
        → LogoutFilter
        → UsernamePasswordAuthenticationFilter / OAuth2LoginAuthenticationFilter / BearerTokenAuthenticationFilter
        → ExceptionTranslationFilter
        → AuthorizationFilter                 (Security 6 replaced FilterSecurityInterceptor)
        → Controller
```

Key 6.x shifts vs 5.x: `authorizeRequests()` → `authorizeHttpRequests()`, `antMatchers()` → `requestMatchers()`, `FilterSecurityInterceptor` → `AuthorizationFilter` backed by `AuthorizationManager`, `SecurityContextPersistenceFilter` → `SecurityContextHolderFilter` (lazier session read).

### Modern Configuration (Spring Security 6+)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Cost factor 12
    }
}
```

### Authentication Flow

1. `AuthenticationFilter` extracts credentials → creates `Authentication` token
2. `AuthenticationManager` (usually `ProviderManager`) delegates to `AuthenticationProvider`s
3. `AuthenticationProvider` uses `UserDetailsService` + `PasswordEncoder` to validate
4. Success → `SecurityContextHolder.getContext().setAuthentication(auth)`
5. `SecurityContext` stored in `ThreadLocal` (or propagated for async)

**Risk**: `SecurityContext` is ThreadLocal-based. In async/reactive code, it's not propagated automatically.
- **Servlet async / `@Async`**: `DelegatingSecurityContextRunnable` or configure `MODE_INHERITABLETHREADLOCAL`.
- **Reactive**: `ReactiveSecurityContextHolder` reads from the Reactor `Context`.
- **Virtual threads**: ThreadLocal works per-request VT, but shared executors need `DelegatingSecurityContextExecutor` to copy context.

### JWT Token Validation

```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withPublicKey(rsaPublicKey)
        .build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("https://auth.example.com"),
        new JwtClaimValidator<>("scope", scopes ->
            ((Collection<?>) scopes).contains("read"))
    ));
    return decoder;
}
```

---

## 12. Spring Data JPA

### N+1 Problem

```java
// PROBLEM: fetching 100 orders triggers 100 additional queries for items
List<Order> orders = orderRepository.findAll(); // 1 query
orders.forEach(o -> o.getItems().size()); // N queries

// FIX 1: Join fetch in JPQL
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.status = :status")
List<Order> findWithItems(@Param("status") Status status);

// FIX 2: Entity graph
@EntityGraph(attributePaths = {"items", "customer"})
List<Order> findByStatus(Status status);

// FIX 3: Batch fetching (Hibernate)
@BatchSize(size = 25) // On the collection or entity
private List<OrderItem> items;
```

### Projection Optimization

```java
// Don't load full entities when you only need a few fields
public interface OrderSummary {
    Long getId();
    String getCustomerName();
    BigDecimal getTotal();
}

List<OrderSummary> findByStatusOrderByCreatedDesc(Status status);
```

### Specifications for Dynamic Queries

```java
public class OrderSpecs {
    public static Specification<Order> hasStatus(Status status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> createdAfter(LocalDate date) {
        return (root, query, cb) -> cb.greaterThan(root.get("createdDate"), date);
    }
}

// Usage — composable
List<Order> orders = orderRepo.findAll(
    hasStatus(PENDING).and(createdAfter(lastWeek))
);
```

### Auditing

```java
@Configuration
@EnableJpaAuditing
public class AuditConfig {
    @Bean
    public AuditorAware<String> auditorProvider() {
        return () -> Optional.ofNullable(SecurityContextHolder.getContext())
            .map(ctx -> ctx.getAuthentication().getName());
    }
}

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {
    @CreatedDate private Instant createdDate;
    @LastModifiedDate private Instant modifiedDate;
    @CreatedBy private String createdBy;
    @LastModifiedBy private String modifiedBy;
}
```

---

## 13. Spring WebFlux (Reactive)

### When to Use Reactive vs Servlet

With **virtual threads** (Java 21+, Boot 3.2+), the "high concurrency, I/O-bound" argument for WebFlux is weaker — a synchronous MVC app on VTs can scale to tens of thousands of concurrent requests. Reactive's remaining strengths are streaming semantics and backpressure.

| Use Reactive When | Use Servlet MVC (+ virtual threads) When |
|-------------------|------------------------------------------|
| Streaming data (SSE, WebSocket, chunked transfer) | JDBC/JPA or any blocking driver dominates |
| Explicit backpressure needed (slow consumer) | Team unfamiliar with reactive |
| Compose many async upstream calls with operators | Simpler debugging needed |
| Reactive DB (R2DBC, Mongo Reactive) end-to-end | Existing synchronous code — VTs give most of the throughput win |

### Key Concepts

```java
// Mono = 0 or 1 element
Mono<User> user = webClient.get()
    .uri("/users/{id}", id)
    .retrieve()
    .bodyToMono(User.class);

// Flux = 0 to N elements
Flux<Event> events = eventRepository.findAll()
    .filter(e -> e.isActive())
    .flatMap(e -> enrichEvent(e))  // async transformation
    .onErrorResume(ex -> Flux.empty());

// Backpressure
Flux.range(1, 1000)
    .onBackpressureBuffer(100)
    .subscribe(new BaseSubscriber<>() {
        @Override
        protected void hookOnSubscribe(Subscription s) {
            request(10); // Request only 10 at a time
        }
    });
```

**Common Pitfalls**:
- **Blocking in reactive pipeline**: Calling `.block()`, Thread.sleep(), JDBC in a reactive chain blocks the event loop → use `Schedulers.boundedElastic()` for wrapping blocking calls
- **Nothing happens without subscribe**: Reactive streams are lazy; if nobody subscribes, the pipeline never executes
- **Context propagation**: MDC, SecurityContext, Transaction context don't propagate automatically — use `io.micrometer:context-propagation` (Spring Boot 3+) with `Hooks.enableAutomaticContextPropagation()` so `ThreadLocal`s are captured into Reactor `Context` transparently
- **Reactor `BlockHound`**: test-scope dependency that detects blocking calls in non-blocking schedulers — invaluable for catching regressions

---

## 14. Testing in Spring

```java
// Slice tests — load only relevant auto-configuration
@WebMvcTest(OrderController.class) // Only web layer
@DataJpaTest                       // Only JPA components + in-memory DB
@WebFluxTest                       // Only WebFlux components

// Integration test with custom config
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void shouldCreateOrder() {
        // Full integration test against real PostgreSQL
    }
}

// MockMvc for controller testing
@WebMvcTest(OrderController.class)
class OrderControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean OrderService orderService; // Boot 3.4+: @MockBean is DEPRECATED, use @MockitoBean / @MockitoSpyBean

    @Test
    void shouldReturn200() throws Exception {
        when(orderService.findById(1L)).thenReturn(Optional.of(testOrder));

        mockMvc.perform(get("/api/orders/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }
}

// MockMvcTester (Spring 6.2+) — AssertJ-fluent alternative to MockMvc
@Autowired MockMvcTester mvc;

assertThat(mvc.get().uri("/api/orders/1"))
    .hasStatusOk()
    .bodyJson()
    .extractingPath("$.id").isEqualTo(1);

// @ServiceConnection (Boot 3.1+) — removes DynamicPropertySource boilerplate
@Container @ServiceConnection
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
// Spring auto-configures spring.datasource.* from the container. Works for Redis, Kafka, MongoDB, RabbitMQ, etc.
```

---

## 15. Common Senior Interview Questions

**Q: How does Spring Boot start an application?**
`SpringApplication.run()` → creates `ApplicationContext` → scans for `@Component`s → processes auto-configuration → starts embedded server (Tomcat/Jetty/Netty) → publishes `ApplicationReadyEvent`.

**Q: What is the difference between `@Component`, `@Service`, `@Repository`, `@Controller`?**
Functionally identical (all are `@Component` stereotypes). Semantic differences: `@Repository` adds persistence exception translation (SQL exceptions → Spring's `DataAccessException`). `@Controller` enables request mapping. `@Service` is purely semantic.

**Q: How would you handle distributed transactions in Spring?**
Avoid them if possible. Alternatives: Saga pattern (choreography or orchestration), eventual consistency with outbox pattern, or `@Transactional` with `JtaTransactionManager` for XA (2PC) if absolutely necessary. XA transactions are slow and complex.

**Q: How do you handle configuration for different environments?**
Spring Profiles (`@Profile("prod")`, `application-prod.yml`), externalized config via environment variables, Spring Cloud Config Server, or Kubernetes ConfigMaps/Secrets. Sensitive values should use encrypted property sources (Vault, AWS Secrets Manager).

**Q: What is the difference between `@RequestMapping` method-level exception handling vs `@ControllerAdvice`?**
`@ExceptionHandler` in a controller handles exceptions from that controller only. `@ControllerAdvice` + `@ExceptionHandler` handles exceptions globally. Use `@RestControllerAdvice` for REST APIs to combine `@ControllerAdvice` + `@ResponseBody`. For standardized error bodies, return `ProblemDetail` (RFC 7807) or throw `ErrorResponseException`.

**Q: `@GetMapping` vs `@RequestMapping`?**
`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping` are composed shortcuts that set `method = RequestMethod.X` on `@RequestMapping`. Prefer them — they document intent and reduce typos. `@RequestMapping` at class level remains useful to set a base path.

**Q: When would you choose virtual threads over WebFlux?**
Virtual threads let you keep imperative, blocking code (JDBC, JPA, synchronous clients) while scaling I/O concurrency similarly to reactive. Pick VTs when the team and ecosystem are synchronous; pick WebFlux when you need streaming/backpressure semantics or end-to-end non-blocking drivers. Don't mix: calling `.block()` from a VT on a reactive chain just re-introduces thread contention.

**Q: When would you reach for Spring Modulith instead of microservices?**
When your bounded contexts are real but you don't (yet) need independent deployability, polyglot storage, or independent scaling. Modulith enforces module boundaries at compile/test time, provides a transactional outbox via the Event Publication Registry, and keeps operational overhead at "one deployable". Split out a module to a microservice only when a forcing function appears (team autonomy, isolated scaling, different SLAs).

**Q: What does Boot 3's AOT engine actually do at build time?**
Processes `@Configuration` classes, resolves bean definitions, pre-creates CGLIB subclasses, evaluates most `@Conditional`s with a "build profile", and emits Java source + `reachability-metadata.json`. This speeds JVM startup (via generated `ApplicationContextInitializer`s) and is the prerequisite for GraalVM `native-image`. Consequence: runtime classpath scanning and dynamic bean registration are restricted; reflection needs explicit hints (`@RegisterReflectionForBinding`, `@Reflective`).

**Q: How do you standardize error responses across a Spring Boot API?**
Enable `spring.mvc.problemdetails.enabled=true` so built-in exceptions produce RFC 7807 responses, then add a `@RestControllerAdvice extends ResponseEntityExceptionHandler` that maps domain exceptions to `ProblemDetail` with a stable `type` URI, custom properties, and tracing correlation (`traceId`).

**Q: `RestTemplate` vs `RestClient` vs `WebClient` vs `@HttpExchange` — which do you use?**
`RestTemplate` is maintenance-only — don't start new code with it. Use `RestClient` for new synchronous code (same fluent API as `WebClient`, no WebFlux dependency). Use `WebClient` when you're in a reactive pipeline. Use `@HttpExchange` interfaces on top of either adapter for typed, declarative clients (replaces Feign in most cases).
