# Testing — Senior Engineer Interview Preparation

Version-sensitive JUnit / Mockito / Testcontainers / Spring Boot notes in this chapter were reviewed against official docs in April 2026.

## How To Use This Chapter

Start with the testing strategy and core tooling, then move into Spring and infrastructure-heavy testing. The original single sheet was split so each topic is easier to revise in isolation.

## Parts

1. [Part 1 — Strategy, JUnit, AssertJ](./09-testing/01-testing-strategy-junit-assertj.md)
   Testing pyramid, JUnit Jupiter/JUnit 6 essentials, fluent assertions.
2. [Part 2 — Mockito](./09-testing/02-mockito.md)
   Mocking, stubbing, verification, static/constructor mocking, strict stubs.
3. [Part 3 — Testcontainers and WireMock](./09-testing/03-testcontainers-wiremock.md)
   Real infrastructure tests, reusable containers, Kafka, HTTP stubbing.
4. [Part 4 — Spring Boot Testing](./09-testing/04-spring-boot-testing.md)
   Test slices, `@MockitoBean`, `@ServiceConnection`, development-time containers.
5. [Part 5 — ArchUnit and Contract Testing](./09-testing/05-architecture-and-contract-testing.md)
   Architecture rules, Pact, Spring Cloud Contract.
6. [Part 6 — Test Design and Reliability](./09-testing/06-test-design-and-reliability.md)
   Test doubles, builders, Awaitility, flakiness, practical parallelism.
7. [Part 7 — Property-Based Testing, TDD, BDD, Mutation Testing](./09-testing/07-property-based-tdd-bdd-mutation.md)
   jqwik, red-green-refactor, Gherkin, PIT.
8. [Part 8 — Common Senior Interview Questions](./09-testing/08-senior-interview-questions.md)
   Concise interview-style answers built from the material above.

## Fast Revision Path

If you have limited time:

1. Read Part 1 for scope and core JUnit/AssertJ vocabulary.
2. Read Part 2 and Part 4 because Mockito + Spring testing questions are common.
3. Read Part 3 if the role uses real infrastructure, messaging, or HTTP integrations.
4. Skim Part 8 last to rehearse interview answers.
