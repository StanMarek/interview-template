# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Maven project for Java interview practice. Production code lives in `src/main/java/com/stanmarek`, with Tesco-specific exercises under `com/stanmarek/tesco` and worked examples under `com/stanmarek/examples/tesco`. Tests mirror that package layout in `src/test/java/com/stanmarek`. Keep reusable test helpers in `src/main/java/com/stanmarek/testing_deps`, not interview solutions. Static inputs and images belong in `src/main/resources`, while long-form study notes live under `theory/`.

## Build, Test, and Development Commands
Use Maven from the repository root:

- `mvn compile`: compile the project against Java 24.
- `mvn test`: run the full JUnit 5 test suite.
- `mvn test -Dtest=TescoSolutionTest`: run one test class.
- `mvn test -Dtest=TescoSolutionTest#fullProblemScenario`: run one test method.
- `mvn clean compile`: remove build output and rebuild.

`target/` is generated output and should not be committed.

## Coding Style & Naming Conventions
Follow the existing Java style: 4-space indentation, standard `com.stanmarek...` packages, and descriptive class names tied to the exercise, such as `TescoRuleEngine`. Use `UpperCamelCase` for classes and records, `lowerCamelCase` for methods and variables, and keep test method names behavior-focused with underscores, for example `emptyCart_returnsEmptyList`. Prefer small helpers inside the test class when they improve readability.

## Testing Guidelines
Tests use JUnit 5 with AssertJ assertions. Add tests in the matching package under `src/test/java`, and keep test class names aligned with the production class name plus `Test`. Cover normal paths, edge cases, and interview-style boundary conditions. If you add libraries that require annotation processing, also update `maven-compiler-plugin` in `pom.xml`.

## Commit & Pull Request Guidelines
Recent history favors short imperative commit subjects, often sentence case, such as `Add Tesco interview coding exercises with solutions and tests` or `Refactor discount application logic in TescoSolution and update tests`. Keep commits focused. PRs should explain the exercise or topic changed, list commands run (`mvn test`, targeted test commands), and include screenshots only when updating documentation images under `src/main/resources` or `theory/`.
