# Java Versions & Evolution — Senior Engineer Interview Preparation

---

## 1. Java Release Model

### Six-Month Release Cadence

Since Java 10 (March 2018), Oracle adopted a time-based release model delivering a new feature release every six months (March and September). This replaced the previous multi-year cycles that led to feature-bloated releases (Java 8 took over 2.5 years after Java 7).

Key implications:
- Features ship when ready rather than being held for a mega-release
- Each release is smaller and more predictable
- Non-LTS releases receive updates only until the next release (6 months of support)

### LTS Versions

Long-Term Support (LTS) versions receive extended updates (typically 5+ years from vendors like Oracle, Adoptium, Amazon Corretto):

| LTS Version | Release Date     | End of Public Updates (Oracle) |
|-------------|------------------|-------------------------------|
| Java 8      | March 2014       | December 2030 (extended)      |
| Java 11     | September 2018   | September 2026                |
| Java 17     | September 2021   | September 2029                |
| Java 21     | September 2023   | September 2031                |
| Java 25     | September 2025   | September 2033 (projected)    |

The LTS cadence shifted from every 3 years (8 -> 11 -> 17) to every 2 years (17 -> 21 -> 25).

### Preview Features, Incubator Modules, and Experimental Flags

Java uses a graduated maturity model for new features:

| Mechanism          | Purpose                                    | How to Enable                  | Stability   |
|--------------------|--------------------------------------------|--------------------------------|-------------|
| Experimental       | JVM-level features (GCs)                   | `-XX:+UnlockExperimentalVMOptions` | Low    |
| Incubator Module   | New APIs in `jdk.incubator.*`              | `--add-modules`                | Medium      |
| Preview Feature    | Language/VM features nearly finalized       | `--enable-preview`             | High        |
| Standard           | Fully committed, backward-compatible        | Nothing — enabled by default   | Stable      |

Preview features may go through multiple rounds (e.g., virtual threads had two preview rounds in Java 19 and 20 before standardizing in 21).

### Evaluating Feature Adoption

When deciding whether to adopt a new Java feature in production:

1. **Is it standard or preview?** Never use preview features in production libraries.
2. **Ecosystem readiness** — Do your frameworks (Spring, Hibernate) support the target version?
3. **Toolchain support** — IDE, build tools, static analysis, CI/CD compatibility.
4. **Migration effort** — Removed APIs, behavioral changes, module system impact.
5. **Team readiness** — Training, coding standards updates, code review guidelines.

---

## 2. Java 8 (LTS — March 2014)

Java 8 was the most transformative release in the language's history, introducing functional programming constructs that fundamentally changed how Java code is written.

### Lambdas and Functional Interfaces

Lambdas provide concise syntax for implementing single-abstract-method (SAM) interfaces:

```java
// Before Java 8
Comparator<String> comp = new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.length() - b.length();
    }
};

// Java 8 lambda
Comparator<String> comp = (a, b) -> a.length() - b.length();
```

Core functional interfaces in `java.util.function`:

| Interface         | Method              | Signature           | Use Case                    |
|-------------------|---------------------|---------------------|-----------------------------|
| `Function<T,R>`   | `apply(T)`          | `T -> R`            | Transformation              |
| `Predicate<T>`    | `test(T)`           | `T -> boolean`      | Filtering                   |
| `Consumer<T>`     | `accept(T)`         | `T -> void`         | Side effects                |
| `Supplier<T>`     | `get()`             | `() -> T`           | Lazy value production       |
| `UnaryOperator<T>`| `apply(T)`          | `T -> T`            | Same-type transformation    |
| `BiFunction<T,U,R>` | `apply(T, U)`    | `(T, U) -> R`       | Two-arg transformation      |

```java
Function<String, Integer> length = String::length;
Predicate<String> nonEmpty = s -> !s.isEmpty();
Consumer<String> printer = System.out::println;
Supplier<List<String>> listFactory = ArrayList::new;
```

### Stream API

Streams enable declarative data processing pipelines with lazy evaluation:

```java
List<String> names = List.of("Alice", "Bob", "Charlie", "David", "Eve");

// Intermediate operations (lazy) -> terminal operation (triggers execution)
List<String> result = names.stream()
    .filter(name -> name.length() > 3)       // intermediate
    .map(String::toUpperCase)                  // intermediate
    .sorted()                                  // intermediate (stateful)
    .collect(Collectors.toList());             // terminal

// result: [ALICE, CHARLIE, DAVID]
```

**Intermediate vs terminal operations:**

| Intermediate (Lazy)       | Terminal (Eager)              |
|---------------------------|-------------------------------|
| `filter()`, `map()`       | `collect()`, `toList()`       |
| `flatMap()`, `distinct()`  | `forEach()`, `count()`        |
| `sorted()`, `peek()`      | `reduce()`, `min()`, `max()`  |
| `limit()`, `skip()`       | `anyMatch()`, `allMatch()`    |
|                           | `findFirst()`, `findAny()`    |

**Parallel streams:**

```java
long count = list.parallelStream()
    .filter(item -> expensiveCheck(item))
    .count();
```

Parallel streams use the common `ForkJoinPool`. Pitfalls include shared mutable state, ordering assumptions, and overhead for small datasets. Use parallel streams only when the workload is CPU-bound, the dataset is large, and the operations are stateless.

### Optional

`Optional<T>` is a container that may or may not hold a non-null value:

```java
// Creation
Optional<String> present = Optional.of("value");
Optional<String> empty = Optional.empty();
Optional<String> nullable = Optional.ofNullable(possiblyNull);

// Proper usage patterns
String result = optional
    .map(String::toUpperCase)
    .filter(s -> s.length() > 3)
    .orElse("default");

// Chaining with flatMap (when inner method also returns Optional)
Optional<String> city = person
    .flatMap(Person::getAddress)
    .flatMap(Address::getCity);
```

**Anti-patterns to avoid:**
- `optional.get()` without `isPresent()` check — defeats the purpose
- `Optional` as method parameter or field type — use only for return types
- `Optional.of(null)` — throws `NullPointerException`; use `ofNullable()`
- Wrapping collections in `Optional` — return empty collection instead

### CompletableFuture

Asynchronous programming without callback hell:

```java
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> fetchFromDatabase())        // runs on ForkJoinPool
    .thenApply(data -> transform(data))            // chain transformation
    .thenCombine(otherFuture, (a, b) -> merge(a, b)) // combine results
    .exceptionally(ex -> fallbackValue());          // error handling

// Waiting for multiple futures
CompletableFuture<Void> all = CompletableFuture.allOf(future1, future2, future3);
CompletableFuture<Object> any = CompletableFuture.anyOf(future1, future2);
```

### Default and Static Methods in Interfaces

```java
public interface Sortable<T> {
    List<T> sort(List<T> items);

    // Default method — provides implementation, can be overridden
    default List<T> sortReversed(List<T> items) {
        List<T> sorted = sort(items);
        Collections.reverse(sorted);
        return sorted;
    }

    // Static method — utility, cannot be overridden
    static <T> boolean isSorted(List<? extends Comparable<T>> list) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i - 1).compareTo((T) list.get(i)) > 0) return false;
        }
        return true;
    }
}
```

### java.time API

Replaced the notoriously broken `Date`/`Calendar`:

```java
// Immutable, thread-safe date/time classes
LocalDate date = LocalDate.of(2024, Month.MARCH, 15);
LocalTime time = LocalTime.of(14, 30, 0);
LocalDateTime dateTime = LocalDateTime.of(date, time);
ZonedDateTime zoned = dateTime.atZone(ZoneId.of("Europe/London"));
Instant instant = Instant.now(); // machine timestamp

// Duration and Period
Duration duration = Duration.between(start, end);       // time-based
Period period = Period.between(startDate, endDate);      // date-based

// Formatting
DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
String formatted = dateTime.format(fmt);
```

### Method References

Four kinds of method references:

```java
// Static method reference
Function<String, Integer> parse = Integer::parseInt;

// Instance method of a particular object
Consumer<String> printer = System.out::println;

// Instance method of an arbitrary object of a particular type
Function<String, String> upper = String::toUpperCase;

// Constructor reference
Supplier<ArrayList<String>> listMaker = ArrayList::new;
```

---

## 3. Java 9 (September 2017)

### Module System (JPMS)

The Java Platform Module System introduced strong encapsulation at the package level:

```java
// module-info.java
module com.myapp.core {
    requires java.sql;                  // dependency on another module
    requires transitive com.myapp.api;  // transitive dependency
    exports com.myapp.core.api;         // packages visible to other modules
    opens com.myapp.core.model to com.fasterxml.jackson.databind; // reflection access
    provides com.myapp.api.Service with com.myapp.core.ServiceImpl; // SPI
}
```

Key concepts:
- **exports** — makes a package accessible to other modules at compile and runtime
- **opens** — makes a package accessible for deep reflection (needed by frameworks)
- **requires** — declares a dependency on another module
- **requires transitive** — dependency is also visible to modules that depend on this one
- **provides...with** — declares a service provider

### Immutable Collection Factory Methods

```java
List<String> list = List.of("a", "b", "c");        // immutable
Set<Integer> set = Set.of(1, 2, 3);                  // immutable, no duplicates
Map<String, Integer> map = Map.of("a", 1, "b", 2);  // immutable

// For more than 10 entries
Map<String, Integer> bigMap = Map.ofEntries(
    Map.entry("key1", 1),
    Map.entry("key2", 2)
);

// These throw UnsupportedOperationException on modification
// They also disallow null keys and values
```

### Private Interface Methods

```java
public interface Loggable {
    default void logInfo(String message) {
        log("INFO", message);
    }

    default void logError(String message) {
        log("ERROR", message);
    }

    // Private method to share logic between default methods
    private void log(String level, String message) {
        System.out.println("[" + level + "] " + message);
    }
}
```

### Stream Additions

```java
// takeWhile — takes elements while predicate is true (ordered streams)
Stream.of(1, 2, 3, 4, 5, 1).takeWhile(n -> n < 4); // [1, 2, 3]

// dropWhile — drops elements while predicate is true
Stream.of(1, 2, 3, 4, 5, 1).dropWhile(n -> n < 4); // [4, 5, 1]

// ofNullable — zero or one element stream
Stream.ofNullable(nullableValue); // empty stream if null
```

### Optional Additions

```java
// ifPresentOrElse
optional.ifPresentOrElse(
    value -> System.out.println("Found: " + value),
    () -> System.out.println("Not found")
);

// stream() — converts Optional to a Stream (0 or 1 elements)
// Useful for flatMapping a stream of Optionals
List<String> values = optionals.stream()
    .flatMap(Optional::stream)
    .collect(Collectors.toList());

// or() — lazy alternative Optional
Optional<String> result = primary.or(() -> secondary);
```

### Other Java 9 Features

- **JShell** — interactive REPL for rapid prototyping (`jshell` command)
- **ProcessHandle API** — `ProcessHandle.current().pid()`, process tree traversal
- **Multi-release JARs** — single JAR with version-specific class files under `META-INF/versions/`

---

## 4. Java 10 (March 2018)

### Local Variable Type Inference (`var`)

```java
// var infers the type from the right-hand side
var list = new ArrayList<String>();     // ArrayList<String>
var stream = list.stream();             // Stream<String>
var map = Map.of("a", 1, "b", 2);      // Map<String, Integer>

// Works in for loops
for (var entry : map.entrySet()) {
    System.out.println(entry.getKey() + ": " + entry.getValue());
}

// Works in try-with-resources
try (var reader = new BufferedReader(new FileReader("file.txt"))) {
    var line = reader.readLine();
}
```

**When to use `var` (readability guidelines):**

| Use `var`                                   | Avoid `var`                                  |
|---------------------------------------------|----------------------------------------------|
| Type is obvious from RHS: `var list = new ArrayList<>()` | Type is not obvious: `var result = process()` |
| Long generic types: `var map = new HashMap<String, List<Integer>>()` | Numeric literals: `var x = 0` (int? long?) |
| For-each and try-with-resources             | When the type aids comprehension              |
| Local scope, short-lived variables          | Return types and fields (not allowed anyway)  |

Restrictions: `var` cannot be used for fields, method parameters, return types, or when there is no initializer.

### Unmodifiable Collection Copies

```java
List<String> original = new ArrayList<>(List.of("a", "b", "c"));
List<String> copy = List.copyOf(original);  // immutable copy

// Also available via collectors
List<String> unmod = stream.collect(Collectors.toUnmodifiableList());
Set<String> unmodSet = stream.collect(Collectors.toUnmodifiableSet());
Map<K, V> unmodMap = stream.collect(Collectors.toUnmodifiableMap(keyFn, valueFn));
```

### Application Class-Data Sharing (AppCDS)

Extends CDS to application classes, improving startup time by sharing class metadata across JVM instances. Requires a two-step process: dump a class list, then create a shared archive.

---

## 5. Java 11 (LTS — September 2018)

### String Additions

```java
" ".isBlank();           // true (whitespace-only or empty)
" hello ".strip();       // "hello" (Unicode-aware, unlike trim())
" hello ".stripLeading(); // "hello "
" hello ".stripTrailing(); // " hello"
"ha".repeat(3);          // "hahaha"

// lines() — splits by line terminators, returns Stream<String>
"line1\nline2\nline3".lines()
    .filter(line -> !line.isBlank())
    .forEach(System.out::println);
```

### HttpClient API

Replaced the legacy `HttpURLConnection` with a modern, HTTP/2-capable client:

```java
HttpClient client = HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

// Synchronous request
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .header("Accept", "application/json")
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode());
System.out.println(response.body());

// Asynchronous request
CompletableFuture<HttpResponse<String>> asyncResponse = client.sendAsync(
    request, HttpResponse.BodyHandlers.ofString()
);
asyncResponse.thenAccept(resp -> System.out.println(resp.body()));

// POST with body
HttpRequest postRequest = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString("{\"key\": \"value\"}"))
    .build();
```

### File Utility Methods

```java
// Read and write strings directly
String content = Files.readString(Path.of("file.txt"));
Files.writeString(Path.of("output.txt"), "Hello, World!");

// With charset
String content = Files.readString(Path.of("file.txt"), StandardCharsets.UTF_8);
```

### Single-File Source-Code Execution

```bash
# No need to compile first
java MyProgram.java

# With arguments
java MyProgram.java arg1 arg2
```

Useful for scripting, prototyping, and simple utilities. The file must contain a class with a `main` method.

### `var` in Lambda Parameters

```java
// Allows annotations on lambda parameters
list.stream()
    .map((@NonNull var item) -> item.toUpperCase())
    .collect(Collectors.toList());
```

### Removed Modules

The following Java EE and CORBA modules were removed (previously deprecated in Java 9):
- `java.xml.ws` (JAX-WS)
- `java.xml.bind` (JAXB) — add `jakarta.xml.bind:jakarta.xml.bind-api` dependency
- `java.activation` (JAF)
- `java.xml.ws.annotation` (Common Annotations)
- `java.corba` (CORBA)
- `java.transaction` (JTA)
- JavaFX — now a separate project (OpenJFX)
- Nashorn JavaScript engine — replaced by GraalJS

---

## 6. Java 12-13 (March/September 2019)

### Switch Expressions (Preview)

Switch evolved from a statement to an expression:

```java
// Java 12 preview — arrow labels, no fall-through
String dayType = switch (day) {
    case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> "Weekday";
    case SATURDAY, SUNDAY -> "Weekend";
};

// Java 13 preview — yield keyword for blocks
int numLetters = switch (day) {
    case MONDAY, FRIDAY, SUNDAY -> 6;
    case TUESDAY -> 7;
    default -> {
        String s = day.toString();
        yield s.length(); // yield returns value from block
    }
};
```

### Text Blocks (Preview in 13)

Multi-line string literals with automatic indentation management:

```java
String json = """
        {
            "name": "Alice",
            "age": 30,
            "city": "London"
        }
        """;

// Indentation is determined by the closing """
// Common leading whitespace is stripped (incidental whitespace removal)
```

### String Additions

```java
// indent(n) — adjusts indentation
"hello".indent(4);   // "    hello\n"
"  hello".indent(-2); // "hello\n"

// transform — applies a function to the string
String result = "hello"
    .transform(s -> s + " world")
    .transform(String::toUpperCase); // "HELLO WORLD"
```

### Shenandoah GC (Experimental)

A low-pause-time garbage collector developed by Red Hat. Performs concurrent compaction, resulting in pause times that are independent of heap size. Enabled with `-XX:+UseShenandoahGC`.

---

## 7. Java 14 (March 2020)

### Switch Expressions (Standard)

The switch expression syntax finalized as a standard feature:

```java
public static String describe(Object obj) {
    return switch (obj) {
        case Integer i -> "Integer: " + i;
        case String s  -> "String of length " + s.length();
        case null      -> "null value";
        default        -> "Other: " + obj.getClass().getSimpleName();
    };
}

// Exhaustiveness checking — compiler ensures all cases are covered
// for sealed types and enums
sealed interface Shape permits Circle, Rectangle {}
record Circle(double radius) implements Shape {}
record Rectangle(double width, double height) implements Shape {}

double area = switch (shape) {
    case Circle c    -> Math.PI * c.radius() * c.radius();
    case Rectangle r -> r.width() * r.height();
    // No default needed — all permitted subtypes covered
};
```

### Helpful NullPointerException Messages

```java
// Before Java 14:
// Exception in thread "main" java.lang.NullPointerException

// Java 14+ (enabled by default since Java 17):
// Exception in thread "main" java.lang.NullPointerException:
//   Cannot invoke "String.length()" because "person.getAddress().getCity()" is null
```

Enabled with `-XX:+ShowCodeDetailsInExceptionMessages` in Java 14; became the default in Java 17.

### Records (Preview)

Immutable data carriers with auto-generated `equals()`, `hashCode()`, `toString()`, and accessor methods:

```java
public record Point(int x, int y) {
    // Compact constructor for validation
    public Point {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Coordinates must be non-negative");
        }
    }

    // Custom method
    public double distanceTo(Point other) {
        return Math.sqrt(Math.pow(this.x - other.x, 2) + Math.pow(this.y - other.y, 2));
    }
}

// Usage
var p = new Point(3, 4);
System.out.println(p.x());    // 3 (accessor, not getX())
System.out.println(p);        // Point[x=3, y=4]
```

### Pattern Matching for `instanceof` (Preview)

```java
// Before
if (obj instanceof String) {
    String s = (String) obj;
    System.out.println(s.length());
}

// Java 14+ pattern matching
if (obj instanceof String s) {
    System.out.println(s.length()); // s is already cast
}

// Works with logical operators
if (obj instanceof String s && s.length() > 5) {
    System.out.println("Long string: " + s);
}
```

---

## 8. Java 15 (September 2020)

### Text Blocks (Standard)

```java
// SQL query
String query = """
        SELECT e.name, e.salary, d.department_name
        FROM employees e
        JOIN departments d ON e.dept_id = d.id
        WHERE e.salary > 50000
        ORDER BY e.salary DESC
        """;

// HTML
String html = """
        <html>
            <body>
                <h1>%s</h1>
                <p>%s</p>
            </body>
        </html>
        """.formatted(title, content);

// Escape sequences
String block = """
        line1\s\
        continues on same line
        line2
        """;
// \s — preserves trailing space
// \  — line continuation (no newline)
```

### Sealed Classes (Preview)

Restrict which classes can extend or implement a type:

```java
public sealed interface Shape
    permits Circle, Rectangle, Triangle {
}

public final class Circle implements Shape {
    private final double radius;
    // ...
}

public non-sealed class Rectangle implements Shape {
    // non-sealed — open for further extension
}

public sealed class Triangle implements Shape
    permits EquilateralTriangle, RightTriangle {
    // further restricts subtypes
}
```

### ZGC and Shenandoah Production-Ready

Both low-latency garbage collectors moved to production status:
- **ZGC** — sub-millisecond pause times, scalable to multi-terabyte heaps (`-XX:+UseZGC`)
- **Shenandoah** — concurrent compaction, consistent pause times (`-XX:+UseShenandoahGC`)

### Hidden Classes

Classes that cannot be discovered or used directly by other classes. Primarily for framework use — generated at runtime, not visible via `Class.forName()`, and can be unloaded independently.

---

## 9. Java 16 (March 2021)

### Records (Standard)

Records became a standard feature with full language support:

```java
public record Employee(String name, String department, double salary) {

    // Static fields and methods are allowed
    private static final double MIN_SALARY = 30000;

    // Custom constructor
    public Employee {
        if (salary < MIN_SALARY) {
            throw new IllegalArgumentException("Salary below minimum");
        }
        name = name.strip(); // can modify parameters in compact constructor
    }

    // Additional constructor
    public Employee(String name, String department) {
        this(name, department, MIN_SALARY);
    }

    // Records can implement interfaces
    // Records cannot extend classes (implicitly extend java.lang.Record)
    // Records are implicitly final
    // Components are implicitly final (immutable)
}
```

**Records vs Lombok `@Value`:**

| Aspect          | Record                        | Lombok `@Value`             |
|-----------------|-------------------------------|-----------------------------|
| Boilerplate     | Built-in to language          | Annotation processor        |
| Inheritance     | Cannot extend classes         | Can extend classes          |
| Mutability      | Always immutable              | Always immutable            |
| Accessor style  | `name()` (no `get` prefix)    | `getName()`                 |
| Builder         | Not built-in                  | `@Builder` available        |
| IDE support     | Universal                     | Requires plugin             |

### Pattern Matching for `instanceof` (Standard)

```java
// Combining with sealed types
public double calculateArea(Shape shape) {
    if (shape instanceof Circle c) {
        return Math.PI * c.radius() * c.radius();
    } else if (shape instanceof Rectangle r) {
        return r.width() * r.height();
    } else if (shape instanceof Triangle t) {
        return 0.5 * t.base() * t.height();
    }
    throw new IllegalArgumentException("Unknown shape");
}

// Pattern variable scope — only in scope where the pattern matches
if (!(obj instanceof String s)) {
    return; // s is NOT in scope here
}
// s IS in scope here (after the guard)
System.out.println(s.length());
```

### Stream.toList()

```java
// Before Java 16
List<String> list = stream.collect(Collectors.toList()); // returns mutable list

// Java 16+
List<String> list = stream.toList(); // returns unmodifiable list

// Important difference: toList() returns an unmodifiable list
// Collectors.toList() returns a mutable ArrayList
```

### Vector API (Incubator)

SIMD (Single Instruction Multiple Data) operations for performance-critical numerical code:

```java
// Conceptual example (incubator API, syntax may evolve)
var species = FloatVector.SPECIES_256;
var va = FloatVector.fromArray(species, a, 0);
var vb = FloatVector.fromArray(species, b, 0);
var vc = va.mul(vb).add(va);
vc.intoArray(result, 0);
```

---

## 10. Java 17 (LTS — September 2021)

### Sealed Classes (Standard)

```java
public sealed interface Expression
    permits Literal, BinaryOp, UnaryOp, Variable {
}

public record Literal(double value) implements Expression {}
public record Variable(String name) implements Expression {}

public record BinaryOp(Expression left, Operator op, Expression right)
    implements Expression {}

public record UnaryOp(Operator op, Expression operand)
    implements Expression {}

public enum Operator { ADD, SUB, MUL, DIV, NEG }

// Exhaustive pattern matching with sealed hierarchy
public double evaluate(Expression expr, Map<String, Double> env) {
    return switch (expr) {
        case Literal l    -> l.value();
        case Variable v   -> env.getOrDefault(v.name(), 0.0);
        case BinaryOp b   -> {
            double left = evaluate(b.left(), env);
            double right = evaluate(b.right(), env);
            yield switch (b.op()) {
                case ADD -> left + right;
                case SUB -> left - right;
                case MUL -> left * right;
                case DIV -> left / right;
                default -> throw new UnsupportedOperationException();
            };
        }
        case UnaryOp u -> switch (u.op()) {
            case NEG -> -evaluate(u.operand(), env);
            default -> throw new UnsupportedOperationException();
        };
    };
}
```

### Pattern Matching for Switch (Preview)

```java
// Type patterns in switch (preview)
static String format(Object obj) {
    return switch (obj) {
        case Integer i              -> "int: " + i;
        case Long l                 -> "long: " + l;
        case Double d               -> "double: " + d;
        case String s               -> "String: " + s;
        case int[] arr              -> "int array of length " + arr.length;
        case null                   -> "null";
        default                     -> "Other: " + obj;
    };
}
```

### Strong Encapsulation of JDK Internals

- `--illegal-access=permit` no longer available (was default in 9-15, denied in 16)
- Internal APIs like `sun.misc.Unsafe` are strongly encapsulated
- Must use `--add-opens` explicitly for reflective access to internal APIs
- Libraries using internal APIs (e.g., older versions of Lombok, ByteBuddy) need updates

### Removed Features

- **Applet API** — deprecated for removal
- **RMI Activation** — removed
- **AOT and Graal JIT** — experimental AOT compilation removed (GraalVM still available separately)
- **Security Manager** — deprecated for removal

---

## 11. Java 18-20 (2022-2023)

### Java 18 (March 2022)

**Simple web server:**

```bash
# Start a minimal static file server
jwebserver --port 8080 --directory /path/to/files
```

```java
// Programmatic API
var server = SimpleFileServer.createFileServer(
    new InetSocketAddress(8080),
    Path.of("/var/www"),
    SimpleFileServer.OutputLevel.INFO
);
server.start();
```

**UTF-8 by default:** The default charset for Java SE APIs is now UTF-8 on all platforms (previously platform-dependent, e.g., Windows-1252 on Windows).

**Code snippets in Javadoc:**

```java
/**
 * Example usage:
 * {@snippet :
 * var list = List.of("a", "b", "c");
 * list.forEach(System.out::println); // @highlight substring="forEach"
 * }
 */
```

### Java 19 (September 2022)

**Virtual threads (preview):**

```java
// Create a virtual thread
Thread vThread = Thread.ofVirtual().start(() -> {
    System.out.println("Running in virtual thread: " + Thread.currentThread());
});

// Virtual thread executor
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000).forEach(i ->
        executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1));
            return i;
        })
    );
}
```

**Structured concurrency (incubator):**

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<String> user = scope.fork(() -> fetchUser());
    Subtask<Integer> order = scope.fork(() -> fetchOrder());
    scope.join().throwIfFailed();
    return new Response(user.get(), order.get());
}
```

### Java 20 (March 2023)

- Virtual threads (second preview) — minor refinements
- Record patterns (second preview)
- Scoped values (incubator) — alternative to `ThreadLocal` for virtual threads
- Foreign Function & Memory API (second preview) — safe, efficient native interop replacing JNI

---

## 12. Java 21 (LTS — September 2023)

Java 21 is a landmark LTS release, bringing virtual threads, pattern matching for switch, record patterns, and sequenced collections to standard status.

### Virtual Threads (Standard)

Virtual threads are lightweight threads managed by the JVM, enabling millions of concurrent threads:

```java
// Creating virtual threads
Thread vThread = Thread.ofVirtual()
    .name("worker-", 0)
    .start(() -> {
        // This runs on a virtual thread
        var result = blockingHttpCall();
        processResult(result);
    });

// Virtual thread executor — one virtual thread per task
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<String>> futures = new ArrayList<>();

    for (int i = 0; i < 100_000; i++) {
        futures.add(executor.submit(() -> {
            // Each task gets its own virtual thread
            // Blocking operations release the carrier thread
            return httpClient.send(request, BodyHandlers.ofString()).body();
        }));
    }

    for (var future : futures) {
        System.out.println(future.get());
    }
}

// Thread.Builder for factory pattern
Thread.Builder builder = Thread.ofVirtual().name("handler-", 0);
ThreadFactory factory = builder.factory();
```

**Virtual threads vs platform threads:**

| Aspect              | Platform Threads          | Virtual Threads              |
|---------------------|---------------------------|------------------------------|
| Managed by          | OS                        | JVM                          |
| Memory overhead     | ~1 MB stack               | ~few KB (grows as needed)    |
| Creation cost       | Expensive                 | Cheap                        |
| Max count           | Thousands                 | Millions                     |
| Scheduling          | OS scheduler              | JVM work-stealing scheduler  |
| Blocking            | Blocks OS thread          | Unmounts from carrier thread |
| Best for            | CPU-bound work            | I/O-bound work               |

**Key guidelines:**
- Do not pool virtual threads — create a new one per task
- Avoid `synchronized` blocks during I/O (pin the carrier thread) — use `ReentrantLock` instead
- Virtual threads are daemon threads by default
- Thread-per-request model becomes viable for high-concurrency servers

### Sequenced Collections

New interfaces providing defined encounter order:

```java
// SequencedCollection<E> — ordered, with first/last access
SequencedCollection<String> seq = new LinkedHashSet<>();
seq.addFirst("a");
seq.addLast("z");
String first = seq.getFirst();     // "a"
String last = seq.getLast();       // "z"
seq.removeFirst();
seq.removeLast();
SequencedCollection<String> reversed = seq.reversed(); // reversed view

// SequencedSet<E> extends SequencedCollection<E>
// SequencedMap<K,V> — ordered map
SequencedMap<String, Integer> map = new LinkedHashMap<>();
map.putFirst("first", 1);
map.putLast("last", 99);
Map.Entry<String, Integer> firstEntry = map.firstEntry();
Map.Entry<String, Integer> lastEntry = map.lastEntry();
SequencedMap<String, Integer> reversedMap = map.reversed();
```

**Collection hierarchy with sequenced interfaces:**

| Interface             | Implementations                                    |
|-----------------------|----------------------------------------------------|
| `SequencedCollection` | `ArrayList`, `LinkedList`, `LinkedHashSet`, `TreeSet` |
| `SequencedSet`        | `LinkedHashSet`, `TreeSet`, `SortedSet`            |
| `SequencedMap`        | `LinkedHashMap`, `TreeMap`, `SortedMap`             |

### Pattern Matching for Switch (Standard)

```java
// Guarded patterns with 'when' clause
static String classify(Shape shape) {
    return switch (shape) {
        case Circle c when c.radius() > 100    -> "Large circle";
        case Circle c                           -> "Small circle";
        case Rectangle r when r.isSquare()      -> "Square";
        case Rectangle r                        -> "Rectangle";
        case Triangle t                         -> "Triangle";
    };
}

// Null handling
static String process(Object obj) {
    return switch (obj) {
        case null          -> "null value";
        case String s      -> "String: " + s;
        case Integer i     -> "Integer: " + i;
        default            -> "Unknown";
    };
}
```

### Record Patterns (Standard)

Deconstruct records directly in pattern matching:

```java
record Point(int x, int y) {}
record Line(Point start, Point end) {}

// Nested record pattern deconstruction
static double length(Line line) {
    return switch (line) {
        case Line(Point(var x1, var y1), Point(var x2, var y2)) ->
            Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    };
}

// In instanceof
if (obj instanceof Point(int x, int y)) {
    System.out.println("Point at " + x + ", " + y);
}

// With guards
if (obj instanceof Point(int x, int y) && x > 0 && y > 0) {
    System.out.println("Point in first quadrant");
}
```

### String Templates (Preview)

```java
// STR template processor (preview)
String name = "Alice";
int age = 30;
String message = STR."Hello, \{name}! You are \{age} years old.";
// "Hello, Alice! You are 30 years old."

// Expressions in templates
String calc = STR."Result: \{2 + 3}";

// Multi-line
String json = STR."""
    {
        "name": "\{name}",
        "age": \{age}
    }
    """;

// FMT template processor — with format specifiers
String formatted = FMT."Pi is approximately %.4f\{Math.PI}";
```

Note: String templates were removed in later releases and are being redesigned.

### Structured Concurrency (Preview)

```java
// ShutdownOnFailure — fail fast, cancel siblings on first failure
ScopedValue<String> USER = ScopedValue.newInstance();

Response handle(Request request) throws Exception {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Subtask<User> userTask = scope.fork(() -> fetchUser(request.userId()));
        Subtask<List<Order>> ordersTask = scope.fork(() -> fetchOrders(request.userId()));

        scope.join();            // wait for all tasks
        scope.throwIfFailed();   // propagate first exception

        return new Response(userTask.get(), ordersTask.get());
    }
}

// ShutdownOnSuccess — return first successful result, cancel the rest
try (var scope = new StructuredTaskScope.ShutdownOnSuccess<String>()) {
    scope.fork(() -> queryMirror1());
    scope.fork(() -> queryMirror2());
    scope.fork(() -> queryMirror3());

    scope.join();
    return scope.result(); // first successful result
}
```

### Scoped Values (Preview)

Thread-safe, immutable alternative to `ThreadLocal` designed for virtual threads:

```java
private static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

void handleRequest(Request request) {
    User user = authenticate(request);
    ScopedValue.where(CURRENT_USER, user).run(() -> {
        // CURRENT_USER is bound within this scope
        processRequest();
    });
}

void processRequest() {
    User user = CURRENT_USER.get(); // access the scoped value
    // ...
}
```

### Unnamed Patterns and Variables (Preview)

```java
// Unnamed variable _ in patterns
if (obj instanceof Point(int x, _)) {
    // Only care about x coordinate
    System.out.println("x = " + x);
}

// In switch
switch (shape) {
    case Circle _   -> System.out.println("It is a circle");
    case Rectangle _ -> System.out.println("It is a rectangle");
}
```

---

## 13. Java 22-24 (2024-2025)

### Java 22 (March 2024)

**Unnamed variables `_` (standard):**

```java
// In for-each when you do not need the variable
for (var _ : collection) {
    count++;
}

// In try-catch
try {
    risky();
} catch (NumberFormatException _) {
    // Exception variable unused
    System.out.println("Invalid number");
}

// In lambda
map.forEach((_, value) -> process(value));

// Multiple catch
try {
    parse(input);
} catch (IOException _) {
    log("IO error");
} catch (ParseException _) {
    log("Parse error");
}
```

**Statements before `super()` (preview):**

```java
public class PositiveInteger extends Number {
    private final int value;

    public PositiveInteger(int value) {
        // Validation BEFORE super() — previously not allowed
        if (value <= 0) {
            throw new IllegalArgumentException("Must be positive: " + value);
        }
        super();
        this.value = value;
    }
}
```

### Java 23 (September 2024)

**Stream gatherers (preview) — custom intermediate operations:**

```java
// Built-in gatherers
import java.util.stream.Gatherers;

// Fixed-size sliding window
List<List<Integer>> windows = Stream.of(1, 2, 3, 4, 5)
    .gather(Gatherers.windowSliding(3))
    .toList();
// [[1,2,3], [2,3,4], [3,4,5]]

// Fixed-size groups (partitioning)
List<List<Integer>> groups = Stream.of(1, 2, 3, 4, 5)
    .gather(Gatherers.windowFixed(2))
    .toList();
// [[1,2], [3,4], [5]]

// Custom gatherer — running average
Gatherer<Double, ?, Double> runningAverage = Gatherer.ofSequential(
    () -> new double[]{0.0, 0},  // initializer: [sum, count]
    (state, element, downstream) -> {
        state[0] += element;
        state[1]++;
        return downstream.push(state[0] / state[1]);
    }
);

List<Double> averages = Stream.of(1.0, 2.0, 3.0, 4.0)
    .gather(runningAverage)
    .toList();
// [1.0, 1.5, 2.0, 2.5]

// Scan (cumulative reduction)
List<Integer> cumSum = Stream.of(1, 2, 3, 4, 5)
    .gather(Gatherers.scan(() -> 0, Integer::sum))
    .toList();
// [1, 3, 6, 10, 15]

// mapConcurrent — parallel mapping preserving order
List<String> results = urls.stream()
    .gather(Gatherers.mapConcurrent(10, url -> fetch(url)))
    .toList();
```

**Flexible constructor bodies (second preview):**

Constructor bodies can now include statements before the explicit constructor invocation (`this()` or `super()`), enabling validation, transformation, and local variable declarations before delegation.

### Java 24 (March 2025)

**Stream gatherers (standard):** The stream gatherers API was finalized.

**Structured concurrency evolution:**

```java
try (var scope = new StructuredTaskScope<String>()) {
    Subtask<String> task1 = scope.fork(() -> callService1());
    Subtask<String> task2 = scope.fork(() -> callService2());

    scope.join();

    // Process results
    if (task1.state() == Subtask.State.SUCCESS) {
        process(task1.get());
    }
}
```

**Scoped values evolution** — continued refinement of the `ScopedValue` API for production readiness.

**Class-file API:** A standard API for reading, writing, and transforming Java class files, replacing ASM for JDK internal use:

```java
// Reading a class file
ClassModel cm = ClassFile.of().parse(bytes);
for (FieldModel fm : cm.fields()) {
    System.out.println(fm.fieldName().stringValue());
}

// Transforming a class file
byte[] newBytes = ClassFile.of().transform(cm, (builder, element) -> {
    // Modify or pass through elements
    builder.with(element);
});
```

**Primitive types in patterns (preview):**

```java
// Pattern matching with primitive types
switch (statusCode) {
    case 200 -> "OK";
    case 404 -> "Not Found";
    case int i when i >= 500 -> "Server Error: " + i;
    case int i -> "Other: " + i;
}

// In instanceof
if (obj instanceof int i) {
    // narrowing conversion from Integer to int
}
```

**Flexible constructor bodies (standard):** Statements before `super()` became a standard feature.

---

## 14. Migration Guide

### Java 8 to Java 11

**Removed modules requiring dependency additions:**

```xml
<!-- JAXB — previously in java.xml.bind -->
<dependency>
    <groupId>jakarta.xml.bind</groupId>
    <artifactId>jakarta.xml.bind-api</artifactId>
    <version>4.0.0</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>4.0.0</version>
</dependency>

<!-- Common Annotations — previously in java.xml.ws.annotation -->
<dependency>
    <groupId>jakarta.annotation</groupId>
    <artifactId>jakarta.annotation-api</artifactId>
    <version>2.1.1</version>
</dependency>

<!-- JTA — previously in java.transaction -->
<dependency>
    <groupId>jakarta.transaction</groupId>
    <artifactId>jakarta.transaction-api</artifactId>
    <version>2.0.1</version>
</dependency>
```

**Key changes:**
- `javax` packages in Java EE modules migrated to `jakarta` namespace
- JavaFX extracted to OpenJFX — add as separate dependency
- Nashorn removed — use GraalJS
- `sun.misc.BASE64Encoder/Decoder` removed — use `java.util.Base64`
- Some internal APIs no longer accessible without `--add-opens`

### Java 11 to Java 17

**Strong encapsulation:**
- Internal APIs are strongly encapsulated by default
- `--illegal-access=permit` no longer available
- Use `--add-opens` for specific packages that require reflective access

```bash
# Example: opening internal packages for frameworks
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     -jar myapp.jar
```

**Removed APIs:**
- Applet API deprecated for removal
- Security Manager deprecated for removal
- RMI Activation removed
- Experimental AOT/Graal JIT removed from JDK (use GraalVM separately)

**Behavioral changes:**
- `Pattern.compile("...")` now uses Unicode by default
- Strongly encapsulated APIs may cause `InaccessibleObjectException`
- Serialization filter framework added

### Java 17 to Java 21

**Module system maturity:**
- Stronger enforcement of module boundaries
- More internal APIs sealed off

**Virtual threads adoption:**
- Refactor thread pools for I/O-bound tasks to virtual thread executors
- Replace `synchronized` with `ReentrantLock` where I/O happens inside synchronized blocks
- Avoid `ThreadLocal` in virtual-thread-heavy code — prefer `ScopedValue`
- Remove thread pool sizing tuning for I/O workloads — create thread per task

```java
// Before (Java 17)
ExecutorService executor = Executors.newFixedThreadPool(200);

// After (Java 21) — for I/O-bound work
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
```

### Common Migration Pain Points

| Pain Point                        | Solution                                          |
|-----------------------------------|---------------------------------------------------|
| `ClassNotFoundException` for EE modules | Add Jakarta dependency equivalents             |
| `InaccessibleObjectException`     | Add `--add-opens` or update library version       |
| Reflection on JDK internals fails | Use `--add-opens` or find public API alternative  |
| Annotation processor issues       | Update processor and configure in compiler plugin |
| Serialization incompatibilities   | Review serialization filters                      |
| Build tool compatibility          | Update Maven/Gradle to versions supporting target JDK |
| Test framework issues             | Update JUnit, Mockito, etc. to compatible versions |

### Multi-Release JAR Strategy

For libraries that must support multiple Java versions:

```
META-INF/
  versions/
    9/
      com/example/Util.class    # Java 9+ version
    17/
      com/example/Util.class   # Java 17+ version
  MANIFEST.MF                  # Multi-Release: true
com/example/Util.class         # Base (Java 8) version
```

### Dependency Compatibility Checklist

Before migrating, verify compatibility of:
1. **Build tools** — Maven 3.9+, Gradle 8+
2. **Frameworks** — Spring Boot 3.x requires Java 17+, Spring 6 requires Java 17+
3. **ORM** — Hibernate 6.x for Java 17+
4. **Testing** — JUnit 5.9+, Mockito 5+
5. **Code generation** — Lombok, MapStruct, annotation processors
6. **Bytecode manipulation** — ASM, ByteBuddy, Javassist
7. **Serialization** — Jackson, Gson
8. **Logging** — Log4j 2.x, SLF4J 2.x
9. **Application servers** — Tomcat 10+, Jetty 12+ for Jakarta namespace
10. **Monitoring** — JMX access, profiler compatibility

---

## 15. Common Senior Interview Questions

**Q1: What are the key differences between Java 8, 11, 17, and 21? When would you choose each as a target version?**

Java 8 introduced lambdas, streams, and the functional programming paradigm. Java 11 added the HttpClient API, removed Java EE modules, and was the first LTS under the new release model. Java 17 brought sealed classes, pattern matching for instanceof, and strong encapsulation of JDK internals. Java 21 delivered virtual threads, sequenced collections, pattern matching for switch, and record patterns.

Choose Java 8 only for legacy systems that cannot be upgraded. Java 11 is the minimum for modern applications but is approaching end of support. Java 17 is the current pragmatic choice with wide framework support (Spring Boot 3 requires it). Java 21 is recommended for new projects, especially those with high-concurrency I/O workloads that benefit from virtual threads.

**Q2: Explain virtual threads. How do they differ from platform threads, and what problems do they solve?**

Virtual threads are lightweight threads managed by the JVM runtime rather than the OS. They are mounted on carrier threads (platform threads) from a pool. When a virtual thread performs a blocking operation (I/O, sleep, lock), it is unmounted from its carrier thread, which is then free to run other virtual threads.

This solves the thread-per-request scalability problem: platform threads are expensive (approximately 1 MB stack each), limiting concurrent connections to thousands. Virtual threads cost only a few kilobytes and can scale to millions. This makes the simple thread-per-request model viable for high-throughput servers without the complexity of reactive programming.

Key caveats: virtual threads do not make CPU-bound code faster (they still need carrier threads), `synchronized` blocks can pin the carrier thread during I/O, and `ThreadLocal` should be replaced with `ScopedValue` to avoid memory waste.

**Q3: What are sealed classes? How do they work with pattern matching?**

Sealed classes restrict which classes can extend or implement them using the `permits` clause. Subtypes must be declared as `final`, `sealed` (further restricting), or `non-sealed` (reopening for extension). This creates a closed type hierarchy known at compile time.

Combined with pattern matching for switch, the compiler can verify exhaustiveness — ensuring all permitted subtypes are handled without a default branch. This enables algebraic data types in Java, making code safer and more expressive. If a new subtype is added to the sealed hierarchy, any switch expression that does not handle it will fail to compile.

**Q4: Describe the Java Module System (JPMS). What problems does it solve, and what are its challenges in practice?**

JPMS provides strong encapsulation at the package level. A `module-info.java` file declares which packages a module exports (public API), which modules it depends on (requires), and which packages are opened for reflection. This solves classpath hell (split packages, duplicate classes), enables reliable dependency graphs, and allows the JDK itself to be modularized for smaller runtime images via `jlink`.

In practice, challenges include: libraries that rely on deep reflection of JDK internals need `--add-opens`, the module system is opt-in (unnamed module for classpath code), tooling adoption is uneven, and many organizations find the migration cost unjustified for application code (as opposed to library code).

**Q5: What is the difference between `Stream.toList()` (Java 16) and `Collectors.toList()`? Why does it matter?**

`stream.toList()` returns an unmodifiable list, while `stream.collect(Collectors.toList())` returns a mutable `ArrayList`. This difference matters because code that modifies the returned list will throw `UnsupportedOperationException` with `toList()`. When migrating, blindly replacing `collect(Collectors.toList())` with `toList()` can introduce runtime errors if downstream code mutates the list. Use `toList()` when immutability is desired, and `collect(Collectors.toCollection(ArrayList::new))` when mutability is needed.

**Q6: How would you migrate a large application from Java 11 to Java 21? What is your strategy?**

The migration strategy involves multiple phases. First, update the build tools and test frameworks to versions compatible with Java 21. Second, compile the project with Java 21 and fix compilation errors (removed APIs, annotation processor changes). Third, run the test suite and fix runtime issues (strong encapsulation violations, behavioral changes). Fourth, add `--add-opens` flags as a temporary measure for libraries that need reflective access to internals. Fifth, update dependencies to versions that natively support Java 21. Sixth, gradually adopt new features (records, sealed classes, pattern matching) in new code. Finally, evaluate virtual threads for I/O-bound services.

For large codebases, use multi-release JARs to maintain backward compatibility during the transition. Run the application with `-Xlog:deprecated` to identify deprecated API usage and `jdeprscan` to find deprecated dependencies.

**Q7: Explain the evolution of pattern matching in Java. How do records, sealed classes, and pattern matching for switch work together?**

Pattern matching evolved through several releases: `instanceof` pattern matching (preview in 14, standard in 16), pattern matching for switch (preview in 17, standard in 21), and record patterns (preview in 19, standard in 21).

Together, they enable algebraic data type programming. Sealed classes define a closed type hierarchy. Records provide concise data carriers with deconstruction. Pattern matching for switch enables exhaustive, type-safe branching with nested record deconstruction.

```java
sealed interface Expr permits Num, Add, Mul {}
record Num(int value) implements Expr {}
record Add(Expr left, Expr right) implements Expr {}
record Mul(Expr left, Expr right) implements Expr {}

int compute(Expr expr) {
    return switch (expr) {
        case Num(int v)                  -> v;
        case Add(var left, var right)    -> compute(left) + compute(right);
        case Mul(var left, var right)    -> compute(left) * compute(right);
    };
}
```

**Q8: What are stream gatherers? How do they compare to existing stream operations?**

Stream gatherers (standard in Java 24) introduce custom intermediate operations via the `Gatherer` interface. Before gatherers, creating custom intermediate operations required writing a `Collector` (terminal operation) or external library support. Gatherers fill the gap by allowing stateful, potentially parallel, custom intermediate transformations.

Built-in gatherers include `windowFixed()` (non-overlapping partitions), `windowSliding()` (overlapping windows), `scan()` (cumulative reduction), and `mapConcurrent()` (parallel mapping with bounded concurrency). Custom gatherers implement the `Gatherer` interface with an initializer, integrator, combiner, and finisher — similar to the `Collector` pattern but for intermediate operations.

**Q9: What is structured concurrency, and why was it introduced alongside virtual threads?**

Structured concurrency enforces that concurrent subtasks are confined to a lexical scope (try-with-resources block). When the scope exits, all subtasks are guaranteed to be complete — either successfully, by cancellation, or by failure. This eliminates common concurrency bugs: orphaned threads that outlive their parent, error handling that misses concurrent failures, and cancellation that does not propagate.

It was introduced alongside virtual threads because virtual threads make creating many concurrent tasks trivially cheap. Without structured concurrency, this ease of creation would amplify the existing problems of unstructured thread management. `StructuredTaskScope.ShutdownOnFailure` cancels siblings on first failure, while `ShutdownOnSuccess` returns the first successful result and cancels the rest.

**Q10: How has Java's approach to immutability evolved from Java 8 to Java 21?**

Java's immutability story has progressively strengthened. Java 8 introduced `Collections.unmodifiableList()` wrappers and `Optional`. Java 9 added `List.of()`, `Set.of()`, `Map.of()` factory methods creating truly immutable collections (not just unmodifiable views). Java 10 added `List.copyOf()` and `Collectors.toUnmodifiable*()`. Java 14 introduced records, which have implicitly final fields. Java 16 added `Stream.toList()` returning unmodifiable lists.

The trend is clear: Java is making immutability the default and mutation the exception. Records combined with sealed classes create immutable algebraic data types. Scoped values (preview in 21) provide immutable thread-local alternatives. The language is converging toward a model where immutable data structures are the natural choice, reducing concurrency bugs and improving code reasoning.
