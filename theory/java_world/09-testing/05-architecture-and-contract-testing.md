# Part 5 — ArchUnit and Contract Testing

## ArchUnit

### Core Concept

ArchUnit lets you enforce architecture rules as executable tests. Instead of relying on code review alone to catch layering violations or naming drift, you encode the rules directly in the test suite.

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
        .andShould().beFinal();

@ArchTest
static final ArchRule noFieldInjection =
    noFields().should().beAnnotatedWith(Autowired.class)
        .because("Constructor injection is easier to test and reason about");
```

### Why Interviewers Like ArchUnit

ArchUnit signals that you treat architecture as executable policy, not a slide deck. That is senior-level testing thinking: protecting design boundaries with automated feedback.

---

## Contract Testing

### Why Contract Testing Exists

In a distributed system, end-to-end tests across many services are expensive, slow, and brittle. Contract testing narrows the question:

> Does the consumer and provider still agree on the interface they share?

It does **not** replace unit, integration, or E2E testing. It closes the gap between isolated tests and full environment tests.

| Approach | Who defines the contract | Flow |
|----------|--------------------------|------|
| Consumer-driven (Pact) | Consumer | Consumer writes expectations, provider verifies them |
| Producer-driven (Spring Cloud Contract) | Provider | Provider writes contract, consumers use generated stubs |

### Pact (Consumer-Driven)

**Consumer side**

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
            }).build())
            .toPact(V4Pact.class);
    }
}
```

**Provider side**

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
}
```

Use Pact when many consumers depend on one provider and each consumer cares about a slightly different subset of the API.

### Spring Cloud Contract (Producer-Driven)

The provider defines the contract, and the framework generates tests and/or stubs from it.

```groovy
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

Use Spring Cloud Contract when the provider team wants to own the published contract and generate stubs from a single source of truth.

### When to Use What

| Scenario | Recommended approach |
|----------|----------------------|
| Many consumers, one provider | Pact |
| Provider team owns the API shape | Spring Cloud Contract |
| Async messaging contracts | Either, depending on ecosystem and team preference |
| Third-party API you do not control | WireMock, not contract testing |
| Monolith / few tightly coupled services | Integration tests may be enough |

### Important Limits

Contract tests prove interface compatibility, not business correctness.

They do **not** prove:

- the provider's internal logic is correct
- the database data is correct
- distributed transaction / saga behavior is correct
- the production environment is wired correctly

That is why senior teams combine contract tests with a small number of critical end-to-end flows.
