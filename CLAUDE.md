# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java 24 interview template project with Maven. Pre-loaded with libraries useful for solving algorithm/data structure problems.

## Build Commands

- **Compile:** `mvn compile`
- **Run tests:** `mvn test`
- **Run single test class:** `mvn test -Dtest=ClassName`
- **Run single test method:** `mvn test -Dtest=ClassName#methodName`
- **Clean build:** `mvn clean compile`

## Architecture

Single-module Maven project with standard layout:
- Source: `src/main/java/com/stanmarek/`
- Tests: `src/test/java/com/stanmarek/`
- Entry point: `com.stanmarek.Main`
- `testing_deps/` subpackage is for library verification only — put interview solutions in the base package

## Available Libraries

### Testing
- **JUnit 5** (`junit-jupiter`) — `@Test`, `@ParameterizedTest`, `@CsvSource`, `@MethodSource`, `@Nested`
- **AssertJ** (`assertj-core`) — fluent assertions: `assertThat(x).isEqualTo(y)`, `containsExactly()`, `isSorted()`

### Collections & Data Structures
- **Guava** — `Multimap`, `BiMap`, `Multiset` (frequency counter), `Table` (2D map), `Graph` API, `Range`, `Sets.intersection()`, `Lists.partition()`
- **Commons Collections** — `PatriciaTrie` (prefix tree), `Bag`, `BidiMap`
- **Eclipse Collections** — primitive collections without boxing: `IntArrayList`, `IntIntHashMap`, `ObjectIntHashMap`; rich functional API

### Utilities
- **Commons Lang** — `Pair`/`Triple` (tuple types), `StringUtils`, `ArrayUtils`
- **Commons Math** — `ArithmeticUtils.gcd()`/`lcm()`, `CombinatoricsUtils.binomialCoefficient()`, `Fraction`

### Graphs
- **JGraphT** — `SimpleGraph`, `SimpleDirectedGraph`, Dijkstra, BFS/DFS iterators, topological sort, cycle detection, MST

### Other
- **Lombok** — `@Data`, `@Value`, `@Builder`, `@EqualsAndHashCode` to reduce boilerplate on custom classes (Node, Edge, State)
- **Vavr** — `Tuple2`–`Tuple8`, `Either`, persistent immutable collections, pattern matching
- **JMH** (test scope) — microbenchmarking with `@Benchmark` to compare algorithm performance

## Notes

- JDK 23+ no longer auto-discovers annotation processors; Lombok and JMH are configured in `maven-compiler-plugin` `annotationProcessorPaths`
- Lombok `sun.misc.Unsafe` deprecation warning at compile time is a known upstream issue — safe to ignore
- When adding a new annotation processor dependency, also add it to `annotationProcessorPaths` in the `maven-compiler-plugin` config in `pom.xml`
- Use `--release` (not `-source/-target`) in compiler properties to avoid "location of system modules" warnings
