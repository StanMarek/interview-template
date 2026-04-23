# Part 8 — Common Senior Interview Questions

## Q1: You inherit a legacy codebase with zero tests. How do you start?

Start with **characterization tests** around the highest-risk and highest-change areas. Do not chase blanket coverage first. Add tests around seams where you can observe behavior safely, usually through public APIs or integration boundaries. If the code is deeply tangled, integration tests often buy confidence faster than unit tests at the start. Then extract logic behind interfaces and add narrower tests as the design improves.

## Q2: What is the difference between `@Mock` and `@Spy` in Mockito?

`@Mock` creates a Mockito double whose methods return default values unless stubbed. Use it to isolate the class under test from a dependency.

`@Spy` wraps a real object. Real methods run unless you override them. Use it only when you genuinely want most real behavior but need to stub a narrow part. If spies show up everywhere, the design is usually too coupled.

## Q3: How do you decide between unit, integration, and E2E tests?

Unit test pure business logic. Integration test anything crossing a real boundary: database queries, HTTP clients, message flows, cache integration. E2E test only the critical user journeys. The heuristic is confidence per cost, not ideology. The more expensive the failure in production, the more real the test environment should be.

## Q4: Explain contract testing. When would you choose it over E2E tests?

Contract testing checks interface compatibility between services without running the full distributed system together. Use it when teams deploy independently, shared environments are slow or unreliable, and API compatibility is the main risk. It is faster than E2E, but it does not replace E2E for workflow or business correctness.

## Q5: What is mutation testing and how does it differ from code coverage?

Coverage says code executed. Mutation testing says tests would fail if the code meaningfully changed. A suite with high coverage but poor assertions can still have a weak mutation score. That is why mutation testing is a quality-of-assertions metric, not a reach metric.

## Q6: How would you test a service that depends on an external HTTP API?

Use WireMock for integration tests of the client adapter: success, retries, timeouts, malformed payloads, 4xx/5xx handling. For unit tests, mock your own interface rather than the underlying HTTP library. If you need cross-team compatibility, add contract tests on top.

## Q7: How do you handle test data setup without making tests brittle?

Use builders for flexible, local test setup and keep good defaults inside the builder. Use Object Mothers only for a few common scenarios. Each test should create the smallest amount of data it needs. Avoid giant shared SQL fixtures unless the test genuinely needs them.

## Q8: You have a flaky test in CI. How do you diagnose it?

First reproduce it repeatedly, locally or in CI reruns. Then classify the failure: shared state, time, async waiting, ordering, environment contention, or random data. Fix the cause instead of adding retries blindly. Inject `Clock`, use Awaitility, stabilize IDs, clean up data, and isolate global state. Quarantine the test if necessary, but do not normalize flakiness.

## Q9: Testcontainers vs H2: benefits and drawbacks?

Testcontainers gives you the real production engine, so SQL features, locking, JSON behavior, and driver quirks match reality much better. H2 is lighter and faster to start, but it can create false confidence. Use H2 only when the exact database behavior is not important. Use Testcontainers when persistence correctness matters. For expensive dependencies, share containers carefully or use Spring Boot's `@ServiceConnection` support rather than blindly spinning one container per class.

## Q10: What does "don't mock what you don't own" mean?

Do not mock third-party implementation types such as HTTP clients, JPA internals, or framework classes unless you absolutely have to. Wrap them behind your own seam, mock that seam in unit tests, and integration-test the adapter. That keeps tests aligned with your design instead of with somebody else's internals.

## Q11: Walk me through the five types of test doubles.

Dummy, stub, fake, spy, mock. The distinction matters because it drives what the test asserts. Stubs and fakes support state verification. Mocks and spies support behavior verification. If a test mixes everything at once, it often becomes hard to understand and hard to change.

## Q12: When would you use property-based testing instead of example-based tests?

When the behavior is best expressed as an invariant over a broad input space: parsing, serialization round-trips, mathematical properties, or aggregate invariants. Keep example-based tests too, because they document business intent better than properties do.

## Q13: How would you test a Spring Boot controller?

Use `@WebMvcTest` for servlet/MVC controllers and `@WebFluxTest` for reactive ones. Mock collaborators with `@MockitoBean`, not full `@SpringBootTest`, unless you intentionally want cross-layer integration. The slice keeps the test focused on routing, validation, serialization, and error mapping.

## Q14: How does `@ServiceConnection` change how you wire Testcontainers?

It removes most of the old `@DynamicPropertySource` boilerplate. Annotate the container with `@ServiceConnection`, and Spring Boot creates the matching connection details automatically. For `GenericContainer`, provide `name` if Boot cannot infer the service type. For local development, pair this with a `src/test` launcher and run via `spring-boot:test-run` or `bootTestRun`.

## Q15: Is PowerMock still relevant in 2026?

Not as a default choice. Mockito 5 covers most of the use cases that used to force PowerMock: final classes, static methods, and constructor interception. If you find PowerMock in a codebase, contain it, do not spread it, and use it as a signal that those seams should eventually be redesigned.
