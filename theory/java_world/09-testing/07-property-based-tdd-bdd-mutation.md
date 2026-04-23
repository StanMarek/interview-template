# Part 7 — Property-Based Testing, TDD, BDD, Mutation Testing

## Property-Based Testing (jqwik)

### Example-Based vs. Property-Based

Example-based tests say:

> for this input, I expect this output

Property-based tests say:

> for any input in this domain, this invariant must hold

jqwik is the de-facto property-based testing library in the Java ecosystem and runs on the JUnit Platform.

```java
@Property
boolean reverseOfReverseIsOriginal(@ForAll List<Integer> list) {
    return reverse(reverse(list)).equals(list);
}

@Property(tries = 500)
void sortingProducesMonotonicList(@ForAll List<@IntRange(min = 0, max = 1000) Integer> xs) {
    List<Integer> sorted = MyQuickSort.sort(xs);
    assertThat(sorted).hasSameSizeAs(xs).isSorted();
    assertThat(sorted).containsExactlyInAnyOrderElementsOf(xs);
}
```

### Shrinking

The killer feature is **shrinking**:

- framework finds a failing random input
- then repeatedly simplifies it
- gives you the smallest failure it can find

That often turns a horrible random counterexample into something trivial like `[1, 0]`.

### Where It Pays Off

- parsers and formatters
- serializers and deserializers
- round-trip operations
- invariants on mutable aggregates
- algorithms with a trusted reference implementation

### Where It Does Not Replace Normal Tests

Property-based tests do not replace example-based business tests. You still want examples such as:

> premium customer gets 15% discount

Examples document intent. Properties explore the input space.

---

## TDD

TDD follows **Red → Green → Refactor**.

1. Write a failing test.
2. Write the minimum code to pass it.
3. Refactor with tests still green.

```java
@Test
void shouldReturnFizzForMultiplesOfThree() {
    assertThat(FizzBuzz.convert(3)).isEqualTo("Fizz");
    assertThat(FizzBuzz.convert(9)).isEqualTo("Fizz");
}

public class FizzBuzz {
    public static String convert(int n) {
        if (n % 3 == 0) return "Fizz";
        return String.valueOf(n);
    }
}
```

### Where TDD Shines

- well-understood business rules
- algorithms
- API design
- extracting logic from legacy code

### Where TDD Struggles

- exploratory UI work
- unclear requirements
- integration-heavy behavior where the seam is not yet obvious

TDD is strongest when the next behavior is concrete and testable.

---

## BDD

BDD frames behavior from the user's or business perspective. In Java teams this often means Cucumber plus Gherkin.

```gherkin
Feature: Shopping Cart Pricing

  Scenario: Apply bulk discount
    Given a shopping cart
    And the cart contains 50 units of "Widget" at $10.00 each
    When the invoice is generated
    Then the discount should be 10%
    And the total should be $450.00
```

```java
public class ShoppingCartSteps {

    private ShoppingCart cart;
    private Invoice invoice;

    @Given("a shopping cart")
    public void createCart() {
        cart = new ShoppingCart();
    }

    @When("the invoice is generated")
    public void generateInvoice() {
        invoice = new InvoiceService().generateInvoice(cart);
    }

    @Then("the discount should be {int}%")
    public void verifyDiscount(int expectedPercent) {
        assertThat(invoice.getDiscountPercent()).isEqualTo(expectedPercent);
    }
}
```

### TDD vs. BDD

| Aspect | TDD | BDD |
|--------|-----|-----|
| Audience | Developers | Developers + business stakeholders |
| Language | Code | Gherkin + code |
| Focus | Implementation correctness | External behavior and acceptance |
| Cost | Lower | Higher |

Practical answer in interviews:

- Use TDD for day-to-day design and implementation.
- Use BDD when business stakeholders actively shape executable acceptance criteria.

---

## Mutation Testing

### What It Measures

Code coverage tells you whether code executed.

Mutation testing tells you whether your tests would notice if the code changed in meaningful ways.

### How It Works

1. Tool mutates code, for example `>` becomes `>=`
2. Tests run against the mutant
3. If tests fail, mutant is **killed**
4. If tests pass, mutant **survives**

Surviving mutants usually mean your assertions are too weak.

### PIT (Pitest) Setup

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.23.0</version>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.2</version>
        </dependency>
    </dependencies>
    <configuration>
        <targetClasses>
            <param>com.example.myapp.service.*</param>
        </targetClasses>
        <targetTests>
            <param>com.example.myapp.service.*Test</param>
        </targetTests>
        <mutators>
            <mutator>DEFAULTS</mutator>
        </mutators>
        <outputFormats>
            <param>HTML</param>
        </outputFormats>
    </configuration>
</plugin>
```

Run:

```bash
mvn org.pitest:pitest-maven:mutationCoverage
```

### Common Mutators

| Mutator | Example |
|---------|---------|
| Conditionals boundary | `>` to `>=` |
| Negate conditionals | `==` to `!=` |
| Math | `+` to `-` |
| Return values | `true` to `false` |
| Void method calls | remove side-effecting call |

### Interpreting Results

```text
Generated 150 mutations
Killed: 135 (90%)
Survived: 12 (8%)
No coverage: 3 (2%)
```

- 90% is usually very strong
- survived mutants are the interesting part
- no-coverage mutants point to untested code paths

### Equivalent Mutants

Some mutants are impossible to kill because the mutated code is functionally equivalent.

That is why chasing 100% mutation score is usually wasteful.

### When Mutation Testing Is Worth It

- pricing and billing
- access control
- core domain algorithms
- shared libraries

Usually not worth it for:

- controllers
- CRUD glue
- rapidly changing scaffolding

Mutation testing is expensive. Aim it where false confidence is expensive too.
