# Java OOP — Abstraction, Inheritance, Polymorphism & Encapsulation

> **Modernization note (Java 25 LTS, Sept 2025)**: The OOP landscape in modern Java has shifted significantly. Classical class-heavy inheritance is no longer the default — senior engineers are expected to reach for **records** (data), **sealed types** (closed hierarchies / ADTs), **pattern matching** (destructuring + exhaustive dispatch), and **interfaces with default methods** (behaviour composition) before abstract classes. The material below covers the classical pillars *and* how they are expressed idiomatically today.

---

## 1. The Four Pillars of OOP

### Overview

| Pillar | Purpose | Java Mechanism | Modern Java Expression |
|--------|---------|----------------|-----------------------|
| **Abstraction** | Hide complexity, expose essentials | Abstract classes, interfaces | Sealed interfaces + records as ADTs |
| **Encapsulation** | Protect internal state, control access | Access modifiers, getters/setters | Records, modules, `private` constructors, `List.copyOf` |
| **Inheritance** | Reuse and extend behavior | `extends`, `implements` | Interface composition; sealed hierarchies with `permits` |
| **Polymorphism** | One interface, many implementations | Method overriding, overloading, generics | Pattern matching switch + record deconstruction |

**Shift since Java 8 → 25**: Inheritance as the primary reuse mechanism has lost ground to composition and *data-oriented programming* (JEP amber design notes). "Make illegal states unrepresentable" through sealed + records is now a first-class idiom, not an FP curiosity.

---

## 2. Abstraction

### Abstract Classes

An abstract class is a partially implemented class that cannot be instantiated. It defines a contract AND provides shared behavior.

```java
public abstract class Payment {
    private final String transactionId;
    private final BigDecimal amount;
    private PaymentStatus status = PaymentStatus.PENDING;

    protected Payment(String transactionId, BigDecimal amount) {
        this.transactionId = transactionId;
        this.amount = amount;
    }

    // Abstract — subclasses MUST implement
    protected abstract boolean executePayment();
    protected abstract void rollbackPayment();

    // Concrete — shared behavior (Template Method pattern)
    public final PaymentResult process() {
        validate();
        boolean success = executePayment();
        if (success) {
            this.status = PaymentStatus.COMPLETED;
            return PaymentResult.success(transactionId);
        } else {
            rollbackPayment();
            this.status = PaymentStatus.FAILED;
            return PaymentResult.failure(transactionId);
        }
    }

    // Concrete with default behavior — can be overridden
    protected void validate() {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}

public class CreditCardPayment extends Payment {
    private final String cardToken;

    public CreditCardPayment(String txnId, BigDecimal amount, String cardToken) {
        super(txnId, amount);
        this.cardToken = cardToken;
    }

    @Override
    protected boolean executePayment() {
        return gateway.charge(cardToken, getAmount());
    }

    @Override
    protected void rollbackPayment() {
        gateway.refund(getTransactionId());
    }

    @Override
    protected void validate() {
        super.validate(); // Call parent validation first
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("Card token required");
        }
    }
}
```

### Abstract Class Rules

- Cannot be instantiated directly (`new Payment()` → compile error)
- CAN have constructors (called via `super()` from subclasses)
- CAN have state (instance fields)
- CAN have any combination of abstract and concrete methods
- CAN have `static` methods and `final` methods
- A subclass MUST implement all abstract methods OR itself be declared `abstract`
- Supports single inheritance only (one parent class)

### Interfaces

An interface defines a pure contract — what a class CAN DO, not what it IS.

```java
public interface Auditable {
    Instant getCreatedAt();
    String getCreatedBy();
    Instant getModifiedAt();
    String getModifiedBy();
}

public interface Cacheable {
    String getCacheKey();

    // Default method (Java 8+) — shared behavior without abstract class
    default Duration getCacheTTL() {
        return Duration.ofMinutes(30); // Override for different TTL
    }
}

public interface Exportable {
    byte[] toCSV();
    byte[] toJSON();

    // Static factory method in interface (Java 8+)
    static Exportable of(List<?> items) {
        return new ListExporter(items);
    }
}

// Multiple interface implementation
public class Order implements Auditable, Cacheable, Exportable {
    // Must implement ALL methods from all three interfaces
    // (except default methods, which are optional to override)
}
```

### Interface Evolution (Java 8 → 17+)

| Feature | Version | Purpose |
|---------|---------|---------|
| Constant fields (`public static final`) | Java 1 | Constants |
| Abstract methods | Java 1 | Contract definition |
| Default methods | Java 8 | Backward-compatible interface evolution |
| Static methods | Java 8 | Utility methods on the interface |
| Private methods | Java 9 | Helper methods for default methods |
| Sealed interfaces | Java 17 | Restrict implementations |

```java
public interface DataProcessor {
    // Abstract — implementors must define
    List<Record> process(byte[] rawData);

    // Default — shared behavior, overridable
    default List<Record> processWithRetry(byte[] rawData, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                return process(rawData);
            } catch (TransientException e) {
                if (i == maxRetries - 1) throw e;
                sleep(calculateBackoff(i));
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    // Static — utility, not overridable
    static DataProcessor noOp() {
        return rawData -> List.of();
    }

    // Private — helper for default methods (Java 9+)
    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private long calculateBackoff(int attempt) {
        return (long) Math.pow(2, attempt) * 100;
    }
}
```

### Abstract Class vs Interface — When to Use Which

| Use Abstract Class When | Use Interface When |
|------------------------|-------------------|
| Subclasses share state (fields) | Defining a capability/contract |
| Need constructors for initialization | Multiple inheritance needed |
| Closely related classes (IS-A hierarchy) | Unrelated classes share behavior |
| Want to provide significant shared logic | Want to decouple completely |
| Need non-public methods in the contract | API stability with default methods |

**Rule of thumb (modern)**: *Default* to interfaces — they impose the fewest constraints, enable multiple inheritance of type, and compose cleanly with records. Use abstract classes only when multiple subclasses truly share **state** (fields) and **constructor logic**. Many designs use both: interface for the public contract, abstract class as a *skeletal implementation* (Effective Java Item 20).

**Decision tree**:
1. Pure data carrier → `record`
2. Closed set of shapes (ADT) → `sealed interface` + records/finals
3. Capability unrelated classes might need → `interface` (with `default` methods where safe)
4. Non-trivial shared state + constructor logic → `abstract class` (only if #3 is insufficient)
5. Template algorithm with overridable hooks → `abstract class` with `final` template method + protected abstract hooks

```java
// Classic JDK example: both interface and abstract class (skeletal implementation)
public interface List<E> extends Collection<E> { ... }        // Contract
public abstract class AbstractList<E> implements List<E> { ... } // Skeleton (convention: Abstract<Interface>)
public class ArrayList<E> extends AbstractList<E> { ... }        // Concrete
```

**Effective Java Item 20 — Skeletal implementation pattern**: Ship an `AbstractXxx` alongside every non-trivial exported interface so users get implementation help for free without being locked into inheritance (they can always implement the interface directly).

---

## 3. Inheritance

### Method Resolution Order & `super`

Java uses **single inheritance** for classes. The method resolution follows the chain from the most specific class upward to `Object`.

```java
class Animal {
    String speak() { return "..."; }
}

class Dog extends Animal {
    @Override
    String speak() { return "Woof"; }
}

class GoldenRetriever extends Dog {
    @Override
    String speak() {
        String parentSound = super.speak(); // Calls Dog.speak() → "Woof"
        return parentSound + "!";           // "Woof!"
    }
}
```

**`super` limitations**: You can only call the immediate parent's method. You CANNOT do `super.super.speak()` to skip a level. This is by design — it would break encapsulation of the intermediate class.

### Constructor Chaining

Constructors are NOT inherited. Every constructor must chain to a parent constructor (implicitly or explicitly).

```java
class Vehicle {
    private final String vin;
    private final int year;

    Vehicle(String vin, int year) {
        // Implicit: super() → Object() is called first
        this.vin = vin;
        this.year = year;
    }
}

class Car extends Vehicle {
    private final int doors;

    Car(String vin, int year, int doors) {
        super(vin, year); // MUST be first statement
        this.doors = doors;
    }

    Car(String vin, int year) {
        this(vin, year, 4); // Delegate to another constructor
    }
}

class ElectricCar extends Car {
    private final int batteryKWh;

    ElectricCar(String vin, int year, int batteryKWh) {
        super(vin, year);
        this.batteryKWh = batteryKWh;
    }
}
// Chain: ElectricCar() → Car() → Vehicle() → Object()
```

**Critical rule**: If the parent has NO no-arg constructor, the subclass MUST explicitly call `super(args)`. Otherwise → compile error.

### The Fragile Base Class Problem

Changing a base class can break subclasses in unexpected ways.

```java
// Version 1: Base class
public class InstrumentedHashSet<E> extends HashSet<E> {
    private int addCount = 0;

    @Override
    public boolean add(E e) {
        addCount++;
        return super.add(e);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return super.addAll(c); // BUG: HashSet.addAll() internally calls add()!
    }
    // addAll(List.of("a","b","c")) → addCount = 6, not 3
    // Because: our addAll adds 3, then super.addAll calls our add() 3 more times
}
```

**This is why "Effective Java" says: Favor composition over inheritance.**

```java
// FIX: Composition + delegation (Decorator pattern)
public class InstrumentedSet<E> implements Set<E> {
    private final Set<E> delegate; // Composition
    private int addCount = 0;

    public InstrumentedSet(Set<E> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean add(E e) {
        addCount++;
        return delegate.add(e); // Delegate, not super
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        addCount += c.size();
        return delegate.addAll(c); // Safe — delegate's addAll won't call OUR add()
    }

    // Delegate all other Set methods to delegate...
}
```

### Inheritance & Access Modifiers

| Modifier | Same Class | Same Package | Subclass (diff package) | World |
|----------|-----------|-------------|------------------------|-------|
| `private` | ✅ | ❌ | ❌ | ❌ |
| (default/package) | ✅ | ✅ | ❌ | ❌ |
| `protected` | ✅ | ✅ | ✅ | ❌ |
| `public` | ✅ | ✅ | ✅ | ✅ |

**Overriding access rules**: You CAN widen access (protected → public) but CANNOT narrow it (public → protected). Reason: Liskov Substitution — a subclass must be usable wherever the parent is expected.

### Covariant Return Types

```java
class Animal {
    Animal create() { return new Animal(); }
}

class Dog extends Animal {
    @Override
    Dog create() { return new Dog(); } // Return type is MORE specific — allowed since Java 5
}
```

### `final` Keyword in Inheritance Context

```java
// final class — cannot be extended
public final class String { ... }           // Security & immutability
public final class Integer { ... }          // Prevent breaking wrapper behavior

// final method — cannot be overridden
public class Account {
    public final BigDecimal getBalance() {  // Prevent subclass tampering
        return balance;
    }
}

// final field — must be initialized once, cannot be reassigned
public class Config {
    private final String url;  // Immutability
    Config(String url) { this.url = url; }
}
```

**When to use `final`**:
- Classes: When inheritance would break invariants (immutable classes, security-sensitive classes)
- Methods: Template Method pattern (the template itself should be final), security-critical methods
- Fields: Immutability by default — make everything final unless mutation is needed

**Modern default (Java 21+)**: *Design for inheritance or prohibit it* (Effective Java Item 19). If a class is not explicitly designed and documented to be subclassed — make it `final` (or use `sealed ... permits ...` to whitelist exactly who may extend). Records are implicitly `final`; enum constants cannot be extended. The modern codebase treats `final` as the default and extensibility as a deliberate design act.

### Composition vs Inheritance in Practice

The classical guidance ("favour composition over inheritance") is sharper with modern Java features. Concrete heuristics:

| Symptom | Likely Fix |
|---------|-----------|
| Subclass overrides one method to change behaviour | Pass a `Function`/`Strategy` into the constructor |
| Deep hierarchy (3+ levels) for reuse | Flatten: record + injected collaborators |
| Subclass needs ≤ 2 of the parent's methods | Parent should expose an interface; subclass implements it instead |
| Parent has protected fields mutated by subclasses | Encapsulate as state object passed via composition |
| `instanceof` checks proliferating over a hierarchy | Sealed types + `switch` pattern match |
| Sharing behaviour between unrelated types | Interface with `default` methods |

**Rule**: Inherit from *your own* classes only. Extending third-party classes (especially `HashSet`, `HashMap`, `ArrayList`) is a fragile-base-class landmine — wrap and delegate instead (see `InstrumentedSet` above).

```java
// ANTIPATTERN — inheritance for reuse
class EmailNotifier extends AbstractNotifier { ... }
class SmsNotifier extends AbstractNotifier { ... }
class SlackNotifier extends AbstractNotifier { ... }

// MODERN — sealed interface + records + composition
public sealed interface Notifier permits EmailNotifier, SmsNotifier, SlackNotifier {
    void send(Message m);
}
public record EmailNotifier(SmtpClient client, String fromAddress) implements Notifier {
    @Override public void send(Message m) { client.send(fromAddress, m); }
}
public record SmsNotifier(TwilioClient client) implements Notifier {
    @Override public void send(Message m) { client.sms(m.to(), m.body()); }
}
public record SlackNotifier(SlackWebhook webhook) implements Notifier {
    @Override public void send(Message m) { webhook.post(m.channel(), m.body()); }
}
```

---

## 4. Polymorphism

### Compile-Time (Static) Polymorphism — Method Overloading

```java
public class MathUtils {
    // Overloading: same name, different parameter types
    public int add(int a, int b) { return a + b; }
    public double add(double a, double b) { return a + b; }
    public BigDecimal add(BigDecimal a, BigDecimal b) { return a.add(b); }
}
```

**Overloading resolution rules** (important for interviews):
1. Exact match first
2. Widening primitive conversion (`int` → `long` → `float` → `double`)
3. Autoboxing/unboxing (`int` ↔ `Integer`)
4. Varargs (`int...`) — lowest priority

```java
void process(long x)     { System.out.println("long"); }
void process(Integer x)  { System.out.println("Integer"); }
void process(int... x)   { System.out.println("varargs"); }

process(5);
// Calls: process(long) — widening beats autoboxing beats varargs
```

**Overloading pitfalls**:
```java
// Confusing overloads with autoboxing
void remove(int index)    { /* remove by index */ }
void remove(Object obj)   { /* remove by value */ }

List<Integer> list = List.of(1, 2, 3);
list.remove(1);           // Calls remove(int) — removes at index 1, not value 1!
list.remove(Integer.valueOf(1)); // Calls remove(Object) — removes value 1
```

### Runtime (Dynamic) Polymorphism — Method Overriding

The JVM determines the actual method to call at runtime based on the object's actual type, not the reference type.

```java
Shape shape = new Circle(5); // Reference type: Shape, Actual type: Circle
shape.area();                // Calls Circle.area() — runtime dispatch

// This is the basis of programming to interfaces
List<String> list = new ArrayList<>(); // Code to the interface
// Could swap to LinkedList without changing calling code
```

**How it works internally — Virtual Method Table (vtable)**:

Each class has a vtable — an array of method pointers. When a method is called, the JVM looks up the actual object's class vtable to find the correct implementation.

```
Shape vtable:         Circle vtable:         Rectangle vtable:
┌──────────────┐     ┌──────────────┐       ┌──────────────┐
│ area() → 0   │     │ area() → Circle.area  │ area() → Rect.area
│ perimeter()  │     │ perimeter() → Circle  │ perimeter() → Rect
└──────────────┘     │ radius() → Circle     └──────────────┘
                     └──────────────┘
```

`shape.area()` → JVM checks actual type (Circle) → looks up Circle's vtable → calls `Circle.area()`.

**`final` methods skip vtable dispatch** — the JVM knows the exact implementation at compile time, enabling inlining.

### Polymorphism with Interfaces — Multiple Type Identity

```java
public interface Serializable { }
public interface Comparable<T> { int compareTo(T o); }
public interface Cloneable { }

// A single object has MULTIPLE types
public class Product implements Serializable, Comparable<Product>, Cloneable {
    @Override
    public int compareTo(Product other) {
        return this.name.compareTo(other.name);
    }
}

Product p = new Product("Widget");
Serializable s = p;    // ✅ — Product IS-A Serializable
Comparable<?> c = p;   // ✅ — Product IS-A Comparable
Object o = p;          // ✅ — Product IS-A Object
```

### Pattern Matching & Type Checking (Modern Java)

```java
// Old way — verbose
if (shape instanceof Circle) {
    Circle c = (Circle) shape;  // Redundant cast
    System.out.println(c.radius());
}

// Java 16+ — pattern matching for instanceof
if (shape instanceof Circle c) {
    System.out.println(c.radius()); // c already cast and scoped
}

// Java 21+ — pattern matching switch (with sealed types)
sealed interface Shape permits Circle, Rectangle, Triangle {}
record Circle(double radius) implements Shape {}
record Rectangle(double width, double height) implements Shape {}
record Triangle(double base, double height) implements Shape {}

String describe(Shape shape) {
    return switch (shape) {
        case Circle c when c.radius() > 100 -> "Large circle: r=" + c.radius();
        case Circle c                        -> "Circle: r=" + c.radius();
        case Rectangle r                     -> "Rect: " + r.width() + "×" + r.height();
        case Triangle t                      -> "Tri: base=" + t.base();
        // Exhaustive — no default needed with sealed types
    };
}
```

### Guarded Patterns and Deconstruction (Java 21+)

```java
// Record patterns — deconstruct nested records
record Address(String city, String country) {}
record Customer(String name, Address address) {}

String greet(Object obj) {
    return switch (obj) {
        case Customer(String name, Address(String city, String country))
            when country.equals("US") -> "Hey " + name + " from " + city + "!";
        case Customer(String name, _) -> "Hello " + name;
        default -> "Welcome";
    };
}
```

---

## 5. Encapsulation

### Beyond Getters and Setters

Encapsulation is NOT just about making fields private and adding get/set. It's about hiding internal representation and exposing meaningful operations.

```java
// BAD — exposes internals, allows invalid state
public class BankAccount {
    private BigDecimal balance;

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    // Anyone can set negative balance!
}

// GOOD — meaningful operations, invariants enforced
public class BankAccount {
    private BigDecimal balance;

    public BankAccount(BigDecimal initialDeposit) {
        if (initialDeposit.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Initial deposit must be non-negative");
        this.balance = initialDeposit;
    }

    public BigDecimal getBalance() {
        return balance; // Read-only access is fine
    }

    public void deposit(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        requirePositive(amount);
        if (amount.compareTo(balance) > 0)
            throw new InsufficientFundsException(balance, amount);
        this.balance = this.balance.subtract(amount);
    }
    // No setBalance() — state only changes through valid operations
}
```

### Defensive Copying

Prevent callers from modifying internal state via mutable references:

```java
public class Order {
    private final List<OrderItem> items;
    private final Date createdAt; // Mutable!

    public Order(List<OrderItem> items, Date createdAt) {
        // Defensive copy on input
        this.items = new ArrayList<>(items);
        this.createdAt = new Date(createdAt.getTime());
    }

    public List<OrderItem> getItems() {
        // Defensive copy on output (or use unmodifiable)
        return Collections.unmodifiableList(items);
    }

    public Date getCreatedAt() {
        return new Date(createdAt.getTime()); // Copy, not original
    }
}

// Modern approach: use immutable types
public record Order(
    List<OrderItem> items,
    Instant createdAt  // Instant is immutable (unlike Date)
) {
    public Order {
        items = List.copyOf(items); // Unmodifiable copy in compact constructor
    }
}
```

### Information Hiding — Package-Private & Modules

```java
// Package-private class — hidden from outside the package
class InternalOrderValidator {  // No 'public' → package-private
    boolean validate(Order order) { ... }
}

// Only OrderService (same package) can use InternalOrderValidator
public class OrderService {
    private final InternalOrderValidator validator = new InternalOrderValidator();
}

// Java 9+ Module System — stronger encapsulation
// module-info.java
module com.app.orders {
    exports com.app.orders.api;          // Public API — visible to other modules
    // com.app.orders.internal is NOT exported — truly hidden
    requires com.app.payments;            // Dependency declaration
    provides OrderProcessor with DefaultOrderProcessor; // SPI
}
```

---

## 6. Sealed Classes & Interfaces (Java 17+)

Controlled inheritance — you define exactly which classes can extend/implement.

```java
// Only these three can implement Shape
public sealed interface Shape permits Circle, Rectangle, Triangle {}

// Subclasses must be: final, sealed, or non-sealed
public record Circle(double radius) implements Shape {}         // final (records are implicitly final)
public final class Rectangle implements Shape { ... }           // explicitly final
public non-sealed class Triangle implements Shape { ... }       // open for further extension

// non-sealed breaks the seal for Triangle's subtypes
public class EquilateralTriangle extends Triangle { ... }       // OK
public class RightTriangle extends Triangle { ... }             // OK
```

**Three modifiers for `permits` targets**:

| Modifier | Semantics | Use Case |
|----------|-----------|----------|
| `final` | No further subclassing allowed | Leaf in the hierarchy (most common; records are implicitly final) |
| `sealed` | Further controlled inheritance | Intermediate nodes in a multi-level ADT |
| `non-sealed` | Open extension (classic inheritance resumes) | Framework integration, plugin seams |

**Same-module rule**: If no explicit `permits` clause is written, the compiler infers it from the same compilation unit (file). `permits` is required when subtypes live in other files. All permitted subtypes must live in the **same module** (or same unnamed module / package when unmodular).

**Why sealed types matter**:
1. **Exhaustive pattern matching**: Compiler knows all subtypes → no `default` needed in switch; adding a new subtype triggers compile errors in every non-exhaustive switch — a feature, not a bug.
2. **Domain modeling**: Express "a Payment is either Card, BankTransfer, or Crypto — nothing else."
3. **Algebraic Data Types**: Enables sum types in Java (like Rust enums, Kotlin sealed classes, Haskell `data`).
4. **API design**: Tell callers "handle every case" at compile time. See JEP 409 and the amber design notes "Data Oriented Programming for Java."

### Algebraic Data Types: Records (Product) + Sealed (Sum)

Modern Java has full ADT support:

- **Product types** (`record`) = AND: a `Point` has an `x` AND a `y`.
- **Sum types** (`sealed interface`) = OR: a `Result` is a `Success` OR a `Failure`.
- Combine them and you get rich, exhaustively-matchable domain models.

```java
// Result<T> — Either/Result monad, Java-style
public sealed interface Result<T> permits Result.Success, Result.Failure {
    record Success<T>(T value) implements Result<T> {}
    record Failure<T>(String message, Throwable cause) implements Result<T> {}

    default <R> Result<R> map(Function<T, R> fn) {
        return switch (this) {
            case Success<T>(T v)       -> new Success<>(fn.apply(v));
            case Failure<T>(var m, var c) -> new Failure<>(m, c);
        };
    }
}

// Domain modeling with sealed types — exhaustive + type-safe
public sealed interface PaymentMethod {
    record CreditCard(String token, String last4) implements PaymentMethod {}
    record BankTransfer(String iban) implements PaymentMethod {}
    record Crypto(String walletAddress, CryptoType type) implements PaymentMethod {}
}

// Compiler guarantees every case handled — adding a new subtype fails this switch to compile
BigDecimal fee(PaymentMethod method) {
    return switch (method) {
        case CreditCard(_, var last4) when last4.startsWith("4") -> VISA_FEE;
        case CreditCard cc       -> DEFAULT_FEE;
        case BankTransfer(var iban) -> BANK_FEE;
        case Crypto(_, var type) -> type == CryptoType.BTC ? BTC_FEE : CRYPTO_FEE;
    };
}
```

**Sealed vs `enum`**: Use `enum` for a closed set of *values without varying state*. Use sealed interface + records when each case carries **different data** (payload-varying sum types). Enums are the degenerate case where every variant has zero payload.

**The Visitor pattern is obsolete**: Pre-Java 17, emulating exhaustive dispatch required the verbose Visitor pattern (double dispatch). With sealed + `switch`, the Visitor pattern collapses to a single switch expression — the compiler enforces exhaustiveness. Keep Visitor only when you cannot modify the hierarchy (third-party types).

---

## 7. The Diamond Problem & Interface Conflicts

```java
interface A {
    default void greet() { System.out.println("A"); }
}

interface B extends A {
    default void greet() { System.out.println("B"); }
}

interface C extends A {
    default void greet() { System.out.println("C"); }
}

// Diamond: D implements both B and C, which both override A.greet()
class D implements B, C {
    // COMPILE ERROR without explicit override — ambiguous
    @Override
    public void greet() {
        B.super.greet(); // Explicitly choose B's implementation
    }
}
```

**Resolution rules**:
1. Class methods win over interface default methods
2. More specific interface wins (B extends A → B's default wins over A's)
3. If still ambiguous → compile error, must override explicitly

---

## 8. Enums as Polymorphic Types

Enums are full classes in Java and support polymorphism:

```java
public enum OrderStatus {
    PENDING {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == CONFIRMED || next == CANCELLED;
        }
    },
    CONFIRMED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == SHIPPED || next == CANCELLED;
        }
    },
    SHIPPED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return next == DELIVERED;
        }
    },
    DELIVERED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return false; // Terminal state
        }
    },
    CANCELLED {
        @Override
        public boolean canTransitionTo(OrderStatus next) {
            return false; // Terminal state
        }
    };

    public abstract boolean canTransitionTo(OrderStatus next);

    // Shared method
    public void transitionTo(OrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                "Cannot transition from " + this + " to " + next);
        }
    }
}
```

**Enum capabilities**: Constructors (private only), fields, methods (abstract/concrete), implement interfaces, singleton per constant.

---

## 9. Records & Immutability (Java 16+)

Records are immutable data carriers with auto-generated `equals()`, `hashCode()`, `toString()`, accessors, and constructor.

```java
public record Money(BigDecimal amount, Currency currency) {
    // Compact constructor — validation
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
        }
    }

    // Custom methods
    public Money add(Money other) {
        if (!this.currency.equals(other.currency))
            throw new CurrencyMismatchException(this.currency, other.currency);
        return new Money(this.amount.add(other.amount), currency);
    }

    // Static factory
    public static Money usd(double amount) {
        return new Money(BigDecimal.valueOf(amount), Currency.getInstance("USD"));
    }
}
```

**Record restrictions**:
- Implicitly `final` — cannot be extended
- Cannot extend other classes (implicitly extend `Record`)
- CAN implement interfaces
- All fields are `final` — truly immutable (if field types are immutable)
- Cannot declare additional instance fields
- CAN have static fields and methods

**Risk**: Records holding mutable objects (List, Date, arrays) are NOT truly immutable:
```java
// DANGEROUS — mutable list leaks
record Team(String name, List<String> members) {}
Team t = new Team("A", new ArrayList<>(List.of("Alice")));
t.members().add("Bob"); // Mutates the record's internal state!

// FIX — defensive copy in compact constructor
record Team(String name, List<String> members) {
    public Team {
        members = List.copyOf(members); // Unmodifiable
    }
}
```

### Compact vs Canonical vs Explicit Constructors

| Form | Signature | When to Use |
|------|-----------|-------------|
| **Compact** | `public Team { ... }` (no params) | Validate / normalize — compiler assigns fields after your code |
| **Canonical (explicit)** | `public Team(String name, List<String> members) { ... }` | Rare — only when you need to change assignment semantics |
| **Additional** | `public Team(String name) { this(name, List.of()); }` | Convenience factories; MUST delegate to canonical via `this(...)` |

**Best-practice rules for records**:
1. Validate in the compact constructor; throw `IllegalArgumentException` (or domain exception) so invalid instances cannot exist.
2. Normalize values (trim strings, round `BigDecimal`, `List.copyOf`) in the compact constructor — you may reassign parameter variables, they become the field values.
3. Never override an accessor to return mutable internals — if you *must* override, return a defensive copy.
4. Use static factories (`Money.usd(10.00)`) for readable call sites; keep the canonical constructor for framework/serialization code.
5. Prefer `Instant`, `LocalDate`, `UUID`, `BigDecimal`, `List.copyOf`, `Set.copyOf`, `Map.copyOf` — all immutable, record-safe.
6. Serialization: records have a *custom* serialization protocol — fields are serialized via accessors and reconstructed via the canonical constructor, so validation is re-run on deserialize (unlike classes, which bypass constructors). This eliminates an entire category of `readObject` bugs.

```java
// Canonical form — validate + normalize + derive
public record Email(String address) {
    private static final Pattern RFC = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {                                // Compact
        Objects.requireNonNull(address, "address");
        address = address.trim().toLowerCase();   // Normalize
        if (!RFC.matcher(address).matches())
            throw new IllegalArgumentException("Invalid email: " + address);
    }
    public String domain() { return address.substring(address.indexOf('@') + 1); }
}
```

---

## 10. Liskov Substitution Principle (LSP) in Modern Java

**LSP formal**: If `S <: T`, then an object of type `T` may be replaced with an object of type `S` without altering the desirable properties of the program (Barbara Liskov, 1987).

**Behavioural requirements for subtypes**:
- Preconditions cannot be *strengthened* in the subtype (can only require the same or less)
- Postconditions cannot be *weakened* in the subtype (must guarantee the same or more)
- Invariants of the supertype must be preserved
- History constraint: state changes allowed by the subtype must be allowed by the supertype
- Method must not throw *new* checked exceptions not declared in the supertype

### Canonical LSP Violations

**1. The Square/Rectangle trap** (aliasing invariants):
```java
class Rectangle {
    protected int width, height;
    public void setWidth(int w) { this.width = w; }
    public void setHeight(int h) { this.height = h; }
    public int area() { return width * height; }
}
class Square extends Rectangle {
    @Override public void setWidth(int w) { this.width = w; this.height = w; }
    @Override public void setHeight(int h) { this.width = h; this.height = h; }
}
// Client code using Rectangle contract:
void grow(Rectangle r) {
    r.setWidth(5); r.setHeight(4);
    assert r.area() == 20;   // Passes for Rectangle, FAILS for Square (area == 16)
}
```
Square *is-a* Rectangle mathematically, but Square *is-not-a* Rectangle behaviourally. Model it as a sealed ADT instead:
```java
sealed interface Shape permits Square, Rectangle {}
record Square(int side) implements Shape { public int area() { return side * side; } }
record Rectangle(int width, int height) implements Shape { public int area() { return width * height; } }
```

**2. `UnsupportedOperationException` anti-pattern**:
```java
class ImmutableList<E> extends ArrayList<E> {
    @Override public boolean add(E e) { throw new UnsupportedOperationException(); }
}
// Any code expecting ArrayList.add() to return true silently breaks.
```
This is why `Collections.unmodifiableList` returns a *type-compatible wrapper* that is documented to throw — and why modern Java moved to `List.of()` / `List.copyOf()` returning an unnamed implementation of `List`, not a subclass of `ArrayList`.

**3. Strengthening preconditions**:
```java
class Parent { void process(String s) { ... } }                // Accepts any String
class Child extends Parent {
    @Override void process(String s) {
        if (s.length() < 5) throw new IllegalArgumentException(); // NEW precondition!
        ...
    }
}
```
A caller holding a `Parent` reference has no way to know the stricter rule — LSP violated.

**4. Covariant arrays** (built-in JDK violation):
```java
Integer[] ints = {1, 2, 3};
Number[] nums = ints;      // Legal — arrays are covariant
nums[0] = 3.14;            // Compiles! Throws ArrayStoreException at runtime.
```
This is why `List<Integer>` is *not* a `List<Number>` — generics are invariant by design to close this hole.

### Modern LSP Enforcement Tools

- **`final` / `sealed`** — prevent unauthorized subclasses that might violate invariants
- **Records** — immutable, `equals`/`hashCode` from components, cannot violate state invariants
- **`@Override`** — compiler verifies signature compatibility (does not verify *behaviour*, but catches typos)
- **Contract tests** — if you publish a class meant to be subclassed, ship an abstract JUnit test suite that subclasses must pass (example: `AbstractListTest`)

---

## 11. Effective Java — Modern Recommendations for OOP

Condensed senior-level checklist drawn from Bloch's *Effective Java 3e* and the amber/data-oriented-programming design notes, updated for Java 25:

| Item | Modern Guidance |
|------|----------------|
| **15. Minimize accessibility** | Default to `private`; `package-private` for intra-package use; `public` only when the class is a documented API. Use modules (`exports`) for hard boundaries. |
| **16. Use accessors, not public fields** | Records make this ergonomic — auto-generated accessors, no boilerplate. |
| **17. Minimize mutability** | Records + immutable collections by default. Mutable objects are an opt-in, not a default. |
| **18. Favor composition over inheritance** | Confirmed stronger than ever with sealed types and records. Inherit only from classes you control. |
| **19. Design for inheritance or prohibit it** | Modern default: `final` unless `sealed permits ...` explicitly opens the door. |
| **20. Prefer interfaces to abstract classes** | Enhanced with `default` and `private` methods; pair with a skeletal implementation when useful. |
| **21. Design interfaces for posterity** | `default` methods are a compatibility tool, not a free extension — think about every implementer before adding one. |
| **22. Use interfaces only to define types** | Not for constants — use an enum or `public static final` in a class. |
| **23. Prefer class hierarchies to tagged classes** | *Revised*: prefer **sealed hierarchies with records** to tagged classes *and* to deep abstract hierarchies. |
| **24. Favor static member classes** | Avoid non-static inner classes unless you need the enclosing-instance reference. |
| **25. Limit source files to a single top-level class** | Still correct; records and sealed subtypes may nest within their defining interface for co-location. |
| **NEW: Use data-oriented programming** | Amber's DOP design notes: model data with records + sealed + pattern matching; keep behaviour in separate services/functions. Avoid mixing "data that is" and "logic that does." |
| **NEW: Make illegal states unrepresentable** | Use sealed ADTs, records with compact-constructor validation, and enums to push correctness into the type system. |
| **NEW: Prefer `switch` expressions over `if/else` chains on type** | Pattern matching makes dispatch exhaustive and refactor-safe. |

### Interface Evolution Safely

`default` methods were added for library authors to evolve interfaces without breaking existing implementers — not as a general tool. When adding a default method:

1. Make it *purely* derivable from other interface methods (e.g., `isEmpty() → size() == 0`)
2. Document it as a "reasonable default" that implementers should override for performance/correctness
3. Never rely on a default method for security-critical logic — an implementer can override it unsafely

```java
public interface Cache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);

    // Safe default — derived from get()
    default boolean contains(K key) { return get(key).isPresent(); }

    // Private helper for default methods (Java 9+)
    private Duration defaultTtl() { return Duration.ofMinutes(5); }
}
```

---

## 12. Common Senior Interview Questions

**Q: Explain the difference between `IS-A` and `HAS-A` relationships.**
`IS-A` = inheritance (`Dog IS-A Animal`). `HAS-A` = composition (`Car HAS-A Engine`). Favor HAS-A (composition) over IS-A (inheritance) because it's more flexible, avoids the fragile base class problem, and enables swapping implementations at runtime.

**Q: Why does Java not support multiple class inheritance?**
The diamond problem: if class D extends both B and C, and both override a method from A, which version does D inherit? Java avoids this ambiguity for classes. Interfaces allow multiple inheritance of TYPE (implementing multiple interfaces) and since Java 8, multiple inheritance of BEHAVIOR (default methods) with explicit conflict resolution rules.

**Q: Can you override a static method?**
No. Static methods are resolved at compile time based on the reference type (early binding). You can HIDE a static method by declaring the same signature in a subclass, but it's not polymorphic — the method called depends on the reference type, not the object type. This is why `@Override` on a static method causes a compile error.

**Q: What is the difference between method hiding and method overriding?**
Overriding (instance methods): Runtime dispatch based on actual object type. Hiding (static methods): Compile-time resolution based on reference type. Hiding also applies to fields — a subclass field with the same name as a parent field hides (doesn't override) the parent field.

```java
class Parent {
    static void staticMethod() { System.out.println("Parent static"); }
    void instanceMethod() { System.out.println("Parent instance"); }
}

class Child extends Parent {
    static void staticMethod() { System.out.println("Child static"); }  // HIDES
    @Override void instanceMethod() { System.out.println("Child instance"); } // OVERRIDES
}

Parent ref = new Child();
ref.staticMethod();    // "Parent static"  — hiding, resolved by reference type
ref.instanceMethod();  // "Child instance" — overriding, resolved by object type
```

**Q: What is the `equals` and `hashCode` contract and why does it matter for inheritance?**
If `a.equals(b)` then `a.hashCode() == b.hashCode()`. Breaking this → objects "lost" in HashMaps. With inheritance, maintaining symmetry (`a.equals(b)` ↔ `b.equals(a)`) is hard:

```java
class Point {
    final int x, y;
    @Override public boolean equals(Object o) {
        if (!(o instanceof Point p)) return false;
        return x == p.x && y == p.y;
    }
}

class ColorPoint extends Point {
    final Color color;
    @Override public boolean equals(Object o) {
        if (!(o instanceof ColorPoint cp)) return false;
        return super.equals(cp) && color == cp.color;
    }
}

Point p = new Point(1, 2);
ColorPoint cp = new ColorPoint(1, 2, RED);
p.equals(cp);  // true  (Point ignores color)
cp.equals(p);  // false (ColorPoint checks color) → SYMMETRY BROKEN
```

**Solution**: Use `getClass()` check instead of `instanceof`, OR use composition instead of inheritance. Records solve this automatically — they generate correct `equals`/`hashCode` and are `final`.

**Q: What is the Liskov Substitution Principle violation with mutable collections?**
`List<Dog>` is NOT a `List<Animal>` even though `Dog IS-A Animal`. If it were, you could add a `Cat` to a `List<Dog>` through the `List<Animal>` reference:

```java
List<Dog> dogs = new ArrayList<>();
List<Animal> animals = dogs; // COMPILE ERROR — and for good reason
animals.add(new Cat());      // Would corrupt the dog list!

// Use wildcards for safe polymorphic access:
List<? extends Animal> readOnly = dogs;  // Can read as Animal, can't add
List<? super Dog> writeOnly = animals;   // Can add Dog, reads as Object
```

**Q: When would you use an abstract class with no abstract methods?**
To prevent direct instantiation while providing a complete base implementation. Example: `AbstractList` has default implementations for most methods. Also used as a marker to signal "this is meant to be extended" or to provide a protected constructor with validation logic. In modern Java, also consider whether a `sealed` interface with record implementations expresses the intent better.

**Q: When do you use a record vs a class vs a sealed interface?**
- **Record** — pure data carrier, value-like, no mutable state, identity doesn't matter. DTOs, value objects, intermediate computation tuples, API payloads.
- **Sealed interface** — closed set of related types where each carries different data. Domain events, result/error types, tagged unions, state machine states.
- **Regular class** — when you need mutable state, complex lifecycle, resource ownership, or thread-safe mutation (e.g., a connection pool, a cache, a stateful service).
- **Abstract class** — rarely; only when multiple concrete classes truly share state + constructor logic that can't be expressed through interface defaults and composition.

**Q: How does pattern matching with sealed types improve API design?**
The compiler enforces exhaustiveness: every `switch` on a sealed type must handle every permitted subtype. Adding a new subtype triggers compile errors at every call site, so you cannot silently forget to handle the new case. This shifts correctness from "hope the test suite catches it" to "the code won't compile if wrong." It also eliminates the Visitor pattern for closed hierarchies — a single `switch` expression replaces the double-dispatch boilerplate.

**Q: Why are records implicitly final?**
To prevent LSP violations and preserve the value-type semantics. Records derive `equals`/`hashCode` from their components; if a subclass added fields, it would either break symmetry (two records with different subclass fields being `equals`) or the subclass would have to re-implement everything. Making records `final` closes this gap. If you need a closed hierarchy of records, use a sealed interface that permits them.

**Q: What's the difference between `sealed`, `non-sealed`, and `final` subtypes?**
Every direct subtype of a sealed parent must explicitly pick one:
- `final` — no further subclassing (leaf in the hierarchy).
- `sealed ... permits ...` — continues controlled inheritance (intermediate node).
- `non-sealed` — opens extension again (framework plug-in seam).
Records are implicitly `final`. `non-sealed` is useful when integrating with frameworks that need to generate proxies/subclasses (Spring, Hibernate), but it intentionally forfeits exhaustiveness guarantees.

**Q: Compact constructor in a record — what actually happens?**
The compact constructor has no parameter list; the compiler provides parameters named after the record components. Inside the body you can validate and reassign the parameter variables (not `this.x`, just `x`). The compiler then inserts `this.x = x; this.y = y; ...` at the end of your body. This makes it the canonical spot for normalization (trim, lowercase, `List.copyOf`) and validation (throw on invalid state).

**Q: Why prefer sealed interface + records over a class hierarchy with `instanceof` checks?**
1. **Exhaustiveness** — compiler enforces every case handled.
2. **Immutability** — records give you final fields and value semantics free.
3. **Deconstruction** — record patterns let you pull out components in the same line that matches the type.
4. **Refactoring safety** — renaming or adding a subtype surfaces all affected switches at compile time.
5. **Less code** — no abstract method overrides, no visitor boilerplate, no defensive `if (x instanceof Foo f)` cascades.

**Q: Can default methods break backwards compatibility?**
Yes, in subtle ways. If two interfaces independently add a `default` method with the same signature, classes implementing both now get a compile error (diamond conflict). If a default method name collides with an existing method in an implementer's superclass, the class method wins — which may silently change behaviour. Before adding a default method to a published interface, audit known implementations.
