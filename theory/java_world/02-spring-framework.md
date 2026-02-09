# Spring Framework — Senior Engineer Interview Preparation

---

## 1. Spring Core / IoC Container

### How the Container Works

The Spring IoC container manages object creation, wiring, and lifecycle. The two main container types:
- `BeanFactory`: Lazy initialization, lightweight
- `ApplicationContext`: Eager initialization, adds AOP, events, i18n, environment abstraction

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
- **Spring 6+ / Boot 3+**: Circular deps are prohibited by default; must opt-in with `spring.main.allow-circular-references=true`

**How @Autowired Resolution Works**:
1. Match by type
2. If multiple candidates → match by qualifier (`@Qualifier`)
3. If still ambiguous → match by bean name (field/parameter name)
4. If still ambiguous → `@Primary` bean wins
5. Otherwise → `NoUniqueBeanDefinitionException`

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

---

## 5. Spring Security

### Security Filter Chain

Spring Security inserts a `FilterChainProxy` (DelegatingFilterProxy) into the servlet filter chain. Internally it maintains an ordered list of security filters:

```
Request → SecurityContextPersistenceFilter
        → CsrfFilter
        → LogoutFilter
        → UsernamePasswordAuthenticationFilter / OAuth2LoginAuthenticationFilter
        → ExceptionTranslationFilter
        → FilterSecurityInterceptor (authorization)
        → Controller
```

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

**Risk**: `SecurityContext` is ThreadLocal-based. In async/reactive code, it's not propagated automatically. Use `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` or Reactor's Context.

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

## 6. Spring Data JPA

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

## 7. Spring WebFlux (Reactive)

### When to Use Reactive vs Servlet

| Use Reactive When | Use Servlet (MVC) When |
|-------------------|----------------------|
| High concurrency, I/O-bound workloads | CPU-bound processing |
| Streaming data (SSE, WebSocket) | JDBC/JPA (blocking by nature) |
| Gateway/proxy services | Team unfamiliar with reactive |
| Microservice orchestration | Simpler debugging needed |

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
- **Context propagation**: MDC, SecurityContext, Transaction context don't propagate automatically in reactive chains

---

## 8. Testing in Spring

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
    @MockBean OrderService orderService;

    @Test
    void shouldReturn200() throws Exception {
        when(orderService.findById(1L)).thenReturn(Optional.of(testOrder));

        mockMvc.perform(get("/api/orders/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }
}
```

---

## 9. Common Senior Interview Questions

**Q: How does Spring Boot start an application?**
`SpringApplication.run()` → creates `ApplicationContext` → scans for `@Component`s → processes auto-configuration → starts embedded server (Tomcat/Jetty/Netty) → publishes `ApplicationReadyEvent`.

**Q: What is the difference between `@Component`, `@Service`, `@Repository`, `@Controller`?**
Functionally identical (all are `@Component` stereotypes). Semantic differences: `@Repository` adds persistence exception translation (SQL exceptions → Spring's `DataAccessException`). `@Controller` enables request mapping. `@Service` is purely semantic.

**Q: How would you handle distributed transactions in Spring?**
Avoid them if possible. Alternatives: Saga pattern (choreography or orchestration), eventual consistency with outbox pattern, or `@Transactional` with `JtaTransactionManager` for XA (2PC) if absolutely necessary. XA transactions are slow and complex.

**Q: How do you handle configuration for different environments?**
Spring Profiles (`@Profile("prod")`, `application-prod.yml`), externalized config via environment variables, Spring Cloud Config Server, or Kubernetes ConfigMaps/Secrets. Sensitive values should use encrypted property sources (Vault, AWS Secrets Manager).

**Q: What is the difference between `@RequestMapping` method-level exception handling vs `@ControllerAdvice`?**
`@ExceptionHandler` in a controller handles exceptions from that controller only. `@ControllerAdvice` + `@ExceptionHandler` handles exceptions globally. Use `@RestControllerAdvice` for REST APIs to combine `@ControllerAdvice` + `@ResponseBody`.
