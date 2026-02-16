# Java OOP — Abstraction, Inheritance, Polymorphism & Encapsulation

---

## 1. The Four Pillars of OOP

### Overview

| Pillar | Purpose | Java Mechanism |
|--------|---------|----------------|
| **Abstraction** | Hide complexity, expose essentials | Abstract classes, interfaces |
| **Encapsulation** | Protect internal state, control access | Access modifiers, getters/setters |
| **Inheritance** | Reuse and extend behavior | `extends`, `implements` |
| **Polymorphism** | One interface, many implementations | Method overriding, overloading, generics |

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

**Rule of thumb**: Prefer interfaces. Use abstract classes when you need shared state or a substantial template of behavior. Many designs use both: interface for the public contract, abstract class as a skeleton implementation.

```java
// Classic JDK example: both interface and abstract class
public interface List<E> extends Collection<E> { ... }        // Contract
public abstract class AbstractList<E> implements List<E> { ... } // Skeleton
public class ArrayList<E> extends AbstractList<E> { ... }        // Concrete
```

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

**Why sealed types matter**:
1. **Exhaustive pattern matching**: Compiler knows all subtypes → no `default` needed in switch
2. **Domain modeling**: Express "a Payment is either Card, BankTransfer, or Crypto — nothing else"
3. **Algebraic Data Types**: Enables sum types in Java (like Rust enums, Kotlin sealed classes)

```java
// Domain modeling with sealed types
public sealed interface PaymentMethod {
    record CreditCard(String token, String last4) implements PaymentMethod {}
    record BankTransfer(String iban) implements PaymentMethod {}
    record Crypto(String walletAddress, CryptoType type) implements PaymentMethod {}
}

// Exhaustive handling guaranteed by compiler
BigDecimal fee(PaymentMethod method) {
    return switch (method) {
        case CreditCard cc  -> cc.last4().startsWith("4") ? VISA_FEE : DEFAULT_FEE;
        case BankTransfer _ -> BANK_FEE;
        case Crypto _       -> CRYPTO_FEE;
    };
}
```

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

---

## 10. Common Senior Interview Questions

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
To prevent direct instantiation while providing a complete base implementation. Example: `AbstractList` has default implementations for most methods. Also used as a marker to signal "this is meant to be extended" or to provide a protected constructor with validation logic.
