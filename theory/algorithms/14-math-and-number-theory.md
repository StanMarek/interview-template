# Math and Number Theory

## Overview

Most "math" interview problems are really five things in disguise: GCD/LCM, modular arithmetic, fast exponentiation, primes (sieve + factorization), and combinatorics. The twist in Java is that `int` overflows silently at `2^31 - 1` and `long` at `2^63 - 1`, so half the battle is choosing the right type and avoiding `a * b` when `a * b` doesn't fit.

Leverage what the repo already has (see `CLAUDE.md`):

- `org.apache.commons.math3.util.ArithmeticUtils` — `gcd(long, long)`, `lcm(long, long)`, `pow(long, int)` with overflow checks.
- `org.apache.commons.math3.util.CombinatoricsUtils` — `binomialCoefficient`, `factorial`, `stirlingS2`.
- `org.apache.commons.math3.fraction.Fraction` / `BigFraction` — exact rationals.
- `com.google.common.math.IntMath` / `LongMath` — `gcd`, `pow`, `factorial`, `checkedMultiply`, `log2`, `isPrime` (Miller-Rabin).
- `java.math.BigInteger` — arbitrary precision, `modPow`, `modInverse`, `isProbablePrime`, `gcd`.

Prefer them in real code; for interviews, know the from-scratch versions below because the interviewer will ask.

## Core Concepts and Mental Model

### GCD and LCM

`gcd(a, 0) = a`. `gcd(a, b) = gcd(b, a mod b)` (Euclid). `lcm(a, b) = a / gcd(a, b) * b` (divide first to avoid overflow).

### Modular Arithmetic

Think of everything as classes in `Z / mZ`:

- `(a + b) mod m = ((a mod m) + (b mod m)) mod m`
- `(a * b) mod m = ((a mod m) * (b mod m)) mod m`
- Subtraction: `(a - b) mod m = ((a - b) % m + m) % m` — or just `Math.floorMod(a - b, m)`.
- Division is multiplication by the modular inverse; it only exists when `gcd(a, m) == 1`.

### Fast Exponentiation

Square-and-multiply computes `a^n` in `O(log n)` multiplications. The same idea with modular reduction gives `a^n mod m` without ever holding `a^n` in memory.

### Primes

A number `n > 1` is prime iff it has no divisor in `[2, sqrt(n)]`. Sieve of Eratosthenes precomputes primality up to `N` in `O(N log log N)` time and `O(N)` space. "Smallest prime factor" sieve also gives `O(log n)` factorization per query after `O(N log log N)` preprocessing.

### Combinatorics Basics

- Permutations: `P(n, k) = n! / (n-k)!`.
- Combinations: `C(n, k) = n! / (k!(n-k)!)`, Pascal's rule `C(n,k) = C(n-1,k-1) + C(n-1,k)`.
- Stars and bars: number of ways to put `n` identical balls into `k` distinct bins is `C(n + k - 1, k - 1)`.
- Catalan: `C_n = C(2n, n) / (n + 1)` — counts balanced parens, binary trees, monotonic lattice paths.

## Must-Know Operations and Identities

```java
// GCD — iterative Euclid on non-negatives. Handles a = 0 or b = 0.
long gcd(long a, long b) {
    a = Math.abs(a); b = Math.abs(b);
    while (b != 0) { long t = a % b; a = b; b = t; }
    return a;
}

// LCM — divide first, watch the zero case
long lcm(long a, long b) {
    if (a == 0 || b == 0) return 0;
    return Math.abs(a / gcd(a, b) * b);
}

// Extended Euclid — returns gcd(a, b) and fills x, y with ax + by = gcd
long extGcd(long a, long b, long[] xy) {
    if (b == 0) { xy[0] = 1; xy[1] = 0; return a; }
    long[] inner = new long[2];
    long g = extGcd(b, a % b, inner);
    xy[0] = inner[1];
    xy[1] = inner[0] - (a / b) * inner[1];
    return g;
}

// Modular pow — (base^exp) mod mod, all non-negative, mod > 1
long modPow(long base, long exp, long mod) {
    long result = 1 % mod;
    base %= mod;
    if (base < 0) base += mod;
    while (exp > 0) {
        if ((exp & 1) == 1) result = result * base % mod;
        base = base * base % mod;
        exp >>= 1;
    }
    return result;
}

// Modular inverse — works when mod is prime (Fermat) OR when gcd(a, mod) == 1 (ext Euclid)
long modInverseFermat(long a, long primeMod) { return modPow(a, primeMod - 2, primeMod); }

long modInverseExt(long a, long m) {
    long[] xy = new long[2];
    long g = extGcd(a, m, xy);
    if (g != 1) throw new ArithmeticException("no inverse");
    return Math.floorMod(xy[0], m);
}
```

### Always Reach for These Java APIs

```java
Math.floorMod(a, m);        // true modulo; never negative when m > 0
Math.floorDiv(a, b);         // pairs with floorMod
Math.addExact(a, b);         // throws on overflow instead of wrapping
Math.multiplyExact(a, b);
Math.subtractExact(a, b);
Math.multiplyHigh(a, b);     // high 64 bits of 128-bit product (Java 9+)
Math.unsignedMultiplyHigh(a, b); // (Java 18+)
Long.parseUnsignedLong(s);
Integer.divideUnsigned(a, b);

// Guava / Commons Math (already on classpath — see CLAUDE.md)
com.google.common.math.LongMath.gcd(a, b);
com.google.common.math.LongMath.pow(base, exp);          // throws on overflow
com.google.common.math.LongMath.checkedMultiply(a, b);
com.google.common.math.LongMath.isPrime(n);              // Miller-Rabin, deterministic for 64-bit
org.apache.commons.math3.util.ArithmeticUtils.gcd(a, b);
org.apache.commons.math3.util.CombinatoricsUtils.binomialCoefficient(n, k);
```

## Common Interview Patterns

| Pattern | Cue | Tool |
|---------|-----|------|
| "Answer mod 10^9 + 7" | huge result | `modPow`, `modInverseFermat` (10^9+7 is prime) |
| "Count divisors" / "sum of divisors" | multiplicative function | factor via SPF sieve, then product formula |
| "Are two fractions equal?" / "simplify" | rational arithmetic | GCD reduce, or `Fraction` from commons-math |
| "nCk for large n" | combinatorics mod p | precompute factorials + inverse factorials |
| "Nth Fibonacci mod m" | linear recurrence | fast matrix exponentiation (O(log n)) |
| "Primes up to N" | lots of primality queries | sieve |
| "Prime factorization many times" | repeated factor queries | smallest-prime-factor sieve |
| "Sum of a geometric series mod p" | closed form | `(a^n - 1) * inv(a - 1)` mod p |
| "Rolling sum with large products" | potential overflow | `Math.multiplyExact` or switch to `BigInteger` |
| "Count pairs with coprime property" | divisibility | Möbius or inclusion-exclusion |

## Sieves and Factorization

```java
// Sieve of Eratosthenes — O(N log log N)
boolean[] sieve(int n) {
    boolean[] prime = new boolean[n + 1];
    Arrays.fill(prime, true);
    prime[0] = prime[1] = false;
    for (int i = 2; (long) i * i <= n; i++) {
        if (prime[i]) {
            for (int j = i * i; j <= n; j += i) prime[j] = false;
        }
    }
    return prime;
}

// Smallest Prime Factor sieve — gives O(log n) factorization per query
int[] spfSieve(int n) {
    int[] spf = new int[n + 1];
    for (int i = 2; i <= n; i++) {
        if (spf[i] == 0) {
            for (long j = i; j <= n; j += i) {
                if (spf[(int) j] == 0) spf[(int) j] = i;
            }
        }
    }
    return spf;
}

Map<Integer, Integer> factorize(int n, int[] spf) {
    Map<Integer, Integer> f = new HashMap<>();
    while (n > 1) {
        int p = spf[n];
        f.merge(p, 1, Integer::sum);
        n /= p;
    }
    return f;
}

// Trial division — O(sqrt(n)), enough when n fits in long and one-off
Map<Long, Integer> trialFactor(long n) {
    Map<Long, Integer> f = new LinkedHashMap<>();
    for (long p = 2; p * p <= n; p++) {
        while (n % p == 0) { f.merge(p, 1, Integer::sum); n /= p; }
    }
    if (n > 1) f.merge(n, 1, Integer::sum);
    return f;
}
```

## Combinatorics Mod Prime

Precompute factorials and their modular inverses once.

```java
static final int MOD = 1_000_000_007;
long[] fact, inv;

void buildFactorials(int n) {
    fact = new long[n + 1];
    inv  = new long[n + 1];
    fact[0] = 1;
    for (int i = 1; i <= n; i++) fact[i] = fact[i - 1] * i % MOD;
    inv[n] = modPow(fact[n], MOD - 2, MOD);
    for (int i = n - 1; i >= 0; i--) inv[i] = inv[i + 1] * (i + 1) % MOD;
}

long nCk(int n, int k) {
    if (k < 0 || k > n) return 0;
    return fact[n] * inv[k] % MOD * inv[n - k] % MOD;
}
```

## Overflow Handling in Java

The multiplication `a * b` for `long a, b` silently wraps on overflow. Three defences, in order of ceremony:

1. **Know the bound.** If `a, b < 10^9`, `a * b` fits in `long`. If `a, b < 2^31`, `(long) a * b` works if you cast first (`(long) a * b`, not `a * b * 1L`).
2. **`Math.multiplyExact` / `addExact`.** Throws `ArithmeticException` on overflow — convert to `BigInteger` in the catch block.
3. **`BigInteger` from the start** when inputs can be arbitrarily large. Slower but correct.

```java
// Safe modular multiplication when a, b, m all up to 2^62 (mulmod via Math.multiplyHigh)
long mulMod(long a, long b, long m) {
    // For m <= ~3e18 this blows up; use BigInteger for full range.
    long hi = Math.multiplyHigh(a, b);
    long lo = a * b;
    // Simplest safe fallback:
    return java.math.BigInteger.valueOf(a)
            .multiply(java.math.BigInteger.valueOf(b))
            .mod(java.math.BigInteger.valueOf(m))
            .longValueExact();
}

// Typical interview-scale: m <= 10^9, so m * m < 2^63 and plain long math is fine.
```

## Java Snippets for Canonical Operations

```java
// Power (integer) with overflow check
long intPow(long base, int exp) {
    long result = 1;
    for (int i = 0; i < exp; i++) result = Math.multiplyExact(result, base);
    return result;
}

// Binary exponentiation (no overflow check — caller vouches)
long fastPow(long base, long exp) {
    long result = 1;
    while (exp > 0) {
        if ((exp & 1) == 1) result *= base;
        base *= base;
        exp >>= 1;
    }
    return result;
}

// Nth Fibonacci mod m via matrix exponentiation — O(log n)
long[][] matMul(long[][] A, long[][] B, long mod) {
    long[][] C = new long[2][2];
    for (int i = 0; i < 2; i++)
        for (int j = 0; j < 2; j++)
            for (int k = 0; k < 2; k++)
                C[i][j] = (C[i][j] + A[i][k] * B[k][j]) % mod;
    return C;
}
long fibMod(long n, long mod) {
    if (n == 0) return 0;
    long[][] base = {{1, 1}, {1, 0}}, result = {{1, 0}, {0, 1}};
    while (n > 0) {
        if ((n & 1) == 1) result = matMul(result, base, mod);
        base = matMul(base, base, mod);
        n >>= 1;
    }
    return result[0][1];
}

// Count trailing zeros in n! — Legendre's formula
int trailingZerosFactorial(int n) {
    int count = 0;
    for (long p = 5; p <= n; p *= 5) count += n / p;
    return count;
}

// Digit sum and digital root
int digitSum(int n) { int s = 0; while (n > 0) { s += n % 10; n /= 10; } return s; }
int digitalRoot(int n) { return n == 0 ? 0 : 1 + (n - 1) % 9; }

// Reverse integer (watch overflow at MIN_VALUE)
int reverse(int x) {
    int r = 0;
    while (x != 0) {
        int d = x % 10;
        if (r > Integer.MAX_VALUE / 10 || (r == Integer.MAX_VALUE / 10 && d > 7)) return 0;
        if (r < Integer.MIN_VALUE / 10 || (r == Integer.MIN_VALUE / 10 && d < -8)) return 0;
        r = r * 10 + d;
        x /= 10;
    }
    return r;
}
```

## Ordered Practice Problems (easy → hard)

1. **GCD of Strings (LC 1071)** — prove `gcd(s, t)` exists iff `s+t == t+s`, then take prefix of length `gcd(|s|, |t|)`.
2. **Happy Number (LC 202)** — digit-square loop, cycle detection via set or Floyd.
3. **Reverse Integer (LC 7)** — watch the 32-bit overflow bounds.
4. **Count Primes (LC 204)** — Sieve of Eratosthenes.
5. **Pow(x, n) (LC 50)** — binary exponentiation; handle negative `n` and `Integer.MIN_VALUE`.
6. **Factorial Trailing Zeroes (LC 172)** — Legendre: count factors of 5.
7. **Excel Sheet Column Title / Number (LC 168 / 171)** — base-26 with 1-indexed alphabet (`A` = 1).
8. **Fraction to Recurring Decimal (LC 166)** — long division with remainder map; sign handling with `Math.floorDiv` / `floorMod`.
9. **Super Pow (LC 372)** — `a^b mod 1337` where `b` is a digit array; use `modPow` recursively on the digits.
10. **Ugly Number II (LC 264)** — 3-pointer DP on multiples of 2, 3, 5.
11. **Count of Integers Divisible by K in [1, N]** — inclusion-exclusion for multiple divisors.
12. **Nth Fibonacci mod m** — matrix exponentiation in `O(log n)`.

## Implement From Memory Drills

1. `modPow(long base, long exp, long mod)` — binary exponentiation, non-negative exp, handles `base < 0` by normalizing.
2. `sieve(int n)` returning a `boolean[]` of primality plus a helper that turns it into a `List<Integer>` of primes ≤ n.
3. `nCkMod(int n, int k, int mod)` using precomputed factorials and Fermat's inverse (assume `mod` is prime).

## Oral Interview Questions

**Q: Why use `Math.floorMod` instead of `%` for modular arithmetic?**
Java's `%` returns a value with the same sign as the dividend, so `-7 % 3 == -1`. `Math.floorMod(-7, 3) == 2` — a non-negative remainder when the divisor is positive, which matches the mathematical definition and avoids surprise negatives when you index into arrays or compare residues.

**Q: When does a modular inverse exist, and how do you compute it?**
`a` has an inverse mod `m` iff `gcd(a, m) == 1`. If `m` is prime, Fermat's little theorem gives `a^(m-2) mod m` via fast exponentiation. If `m` is composite but coprime to `a`, use the extended Euclidean algorithm to solve `ax + my = 1`; `x mod m` is the inverse. `BigInteger.modInverse` does the general case for you.

**Q: How do you prevent overflow when computing `lcm(a, b)` for large longs?**
Divide first: `lcm = a / gcd(a, b) * b`. The division is exact by construction, so you multiply a smaller number by `b` instead of computing `a * b` first. If you still worry about overflow, use `Math.multiplyExact` to fail loudly, or fall back to `BigInteger`.

**Q: Explain the Sieve of Eratosthenes complexity.**
For each prime `p ≤ N`, we cross out roughly `N/p` multiples. Summing `N/p` over primes up to `N` is `N * (sum of 1/p for p prime ≤ N) ≈ N * log log N` by Mertens' theorem. Total time `O(N log log N)`, space `O(N)`. Starting the inner loop at `p*p` (not `2p`) is the optimization that keeps it tight.

**Q: Why is `10^9 + 7` the most common modulus?**
It's prime (so every non-zero residue has an inverse), it's large enough that random collisions are negligible, and `10^9 + 7 < 2^31` so `(a % m) * (b % m)` fits in a `long` without care. `998244353` shows up when the problem needs NTT-friendly moduli (it equals `119 * 2^23 + 1`).

## Edge Cases and Gotchas

- `Integer.MIN_VALUE / -1` overflows — the only division that overflows in Java.
- `Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE` — negation overflow. Same for `long`.
- `%` can return a negative result; use `Math.floorMod` when you need a canonical `[0, m)` residue.
- `a * b` for `int a, b` does `int` multiplication; cast one side to `long` first: `(long) a * b`.
- `1e9 * 1e9` as a `long` literal is `1_000_000_000_000_000_000L`, which is ~`2^59.8` — safe; `1e10 * 1e10` is not.
- Fast exponentiation with negative exponent: for integer results this is only 0 or ±1 unless you're in modular land with an inverse.
- Sieve size `int[n+1]` when `n` is at the `int` boundary: `new int[Integer.MAX_VALUE]` throws `OutOfMemoryError`.
- `BigInteger.modPow` handles negative bases by reducing first — do the same in hand-rolled code.
- Integer overflow when reading numbers from strings: `Integer.parseInt("2147483648")` throws; use `Long.parseLong` or `new BigInteger(s)`.
- Combinatorics without a modulus overflows fast: `C(67, 33)` already exceeds `long`. Use `CombinatoricsUtils.binomialCoefficientDouble` if you want an approximation or `BigInteger` for exact.
- Floating-point `Math.pow` is not exact; never use it to get an integer power. Use `LongMath.pow` or a hand-rolled loop.

## Complexity Summary

| Operation | Time | Space | Notes |
|-----------|------|-------|-------|
| `gcd(a, b)` | O(log min(a, b)) | O(1) iterative | Extended form same cost |
| `modPow(a, n, m)` | O(log n) | O(1) | O(log n) multiplications mod m |
| Sieve of Eratosthenes up to N | O(N log log N) | O(N) | Primes up to 10^7 comfortable |
| SPF sieve + factorize | O(N log log N) + O(log n) per query | O(N) | Great for batch factorization |
| Trial-division factorize | O(sqrt(n)) | O(log n) factors | One-off use |
| `LongMath.isPrime` (Miller-Rabin) | O(k · (log n)^3) | O(1) | Deterministic for 64-bit |
| Precomputed `nCk` mod prime | O(N) preprocess, O(1) query | O(N) | Requires prime modulus |
| Matrix exponentiation (k × k) | O(k^3 log n) | O(k^2) | Linear recurrences |
| `BigInteger` multiply of d-digit numbers | O(d · log d · log log d) | O(d) | Uses Toom-Cook / Karatsuba in JDK |
