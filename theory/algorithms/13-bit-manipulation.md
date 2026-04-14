# Bit Manipulation

## Overview

Bit manipulation trades algebra on numbers for algebra on their binary representations. It shows up in interviews as "do it in O(1) extra space", "no arithmetic operators", "find the unique element", or as a compact encoding for DP state (subsets, visited sets). The core skill is fluency with `AND`, `OR`, `XOR`, `NOT`, and shifts plus a handful of canonical identities.

In Java the types you care about are `int` (32-bit, signed) and `long` (64-bit, signed). Shifts on `int` mask the count to 5 bits (`x << 33` == `x << 1`), and on `long` to 6 bits — a classic bug when you mix types.

## Core Concepts and Mental Model

### Operators

| Op | Name | Identity |
|----|------|----------|
| `&` | AND | `x & 0 = 0`, `x & -1 = x`, `x & x = x` |
| `|` | OR | `x | 0 = x`, `x | -1 = -1`, `x | x = x` |
| `^` | XOR | `x ^ 0 = x`, `x ^ x = 0`, `x ^ y ^ y = x`, commutative + associative |
| `~` | NOT | `~x = -x - 1` (two's complement) |
| `<<` | left shift | `x << k == x * 2^k` (mod 2^32) |
| `>>` | arithmetic right shift | sign-extends; `-1 >> 1 == -1` |
| `>>>` | logical right shift | fills with 0; Java-specific, use for unsigned treatment |

### Two's Complement — What You Actually Need

- `int` range is `[-2^31, 2^31 - 1]`. `Integer.MIN_VALUE = -Integer.MIN_VALUE` (overflow).
- Sign bit is the highest bit; `>>` preserves it, `>>>` does not.
- `-x == ~x + 1`. Equivalently, `x & -x` isolates the lowest set bit.

## Must-Know Identities and Tricks

```java
// Test, set, clear, toggle bit i (0-indexed from LSB)
boolean bit = ((x >> i) & 1) == 1;
int set    = x |  (1 << i);
int clear  = x & ~(1 << i);
int toggle = x ^  (1 << i);

// Lowest set bit (a.k.a. "rightmost 1")
int lsb     = x & -x;          // e.g. 0b0110 -> 0b0010; used in Fenwick trees
int clearLsb = x & (x - 1);    // Brian Kernighan — drops the lowest set bit

// Mask of lowest n bits
int maskN = (1 << n) - 1;      // careful: n = 32 overflows int; use ((1L << 32) - 1)

// Power of two check (x > 0)
boolean powOf2 = (x & (x - 1)) == 0 && x > 0;

// Is x a power of 4? power-of-2 AND set bit in even position
boolean powOf4 = powOf2 && (x & 0x55555555) != 0;

// Swap without temp (rarely useful — style points only)
a ^= b; b ^= a; a ^= b;

// Absolute value without branch (for int, not MIN_VALUE)
int abs = (x ^ (x >> 31)) - (x >> 31);

// Min / max without branch
int min = b ^ ((a ^ b) & -(a < b ? 1 : 0));

// Sign: -1, 0, 1
int sign = (x >> 31) | (-x >>> 31);

// Parity (even number of 1-bits?)
boolean evenParity = Integer.bitCount(x) % 2 == 0;

// Reverse bits (Integer util is fine; manual is "shift and OR")
int rev = Integer.reverse(x);
```

### XOR Algebra

- `a ^ a = 0`, `a ^ 0 = a`, commutative and associative.
- Running XOR of `[0, n]` has a closed form: `n % 4 == 0 -> n`, `1 -> 1`, `2 -> n+1`, `3 -> 0`.
- XOR of two numbers = bits where they differ.
- `a + b == (a ^ b) + 2 * (a & b)` — sum equals XOR plus twice the AND (carries).

## Common Interview Patterns

| Pattern | Cue | Tool |
|---------|-----|------|
| "Every element appears twice except one" | uniqueness + O(1) space | XOR everything |
| "Every element three times except one" | mod 3 | bitwise state machine, or count each bit mod 3 |
| "Two numbers appear once, rest twice" | two uniques | XOR all, split on any set bit of the XOR |
| "Enumerate all subsets of n ≤ 20" | power set | `for (mask = 0; mask < 1 << n; mask++)` |
| "Enumerate subsets of a subset" | submask iteration | `for (s = m; s > 0; s = (s - 1) & m)` |
| "Next integer with same popcount" | combinatorial iteration | Gosper's hack |
| "Count set bits fast" | popcount | `Integer.bitCount`, or Brian Kernighan loop |
| "Assignment / TSP DP" | n ≤ 20 states | `dp[mask]` or `dp[mask][i]` (see 09-dp) |
| "No +/− allowed" | implement add | half-adder with `^` and `&` shifted |
| "Check divisibility by 2^k" | cheap mod | `x & ((1 << k) - 1)` |

### Brian Kernighan's Popcount

Clears one set bit per iteration; loops `popcount(x)` times rather than 32.

```java
int popcount(int x) {
    int count = 0;
    while (x != 0) { x &= x - 1; count++; }
    return count;
}
```

### Gosper's Hack — Next Number with Same Popcount

Used to iterate all k-subsets of `{0,...,n-1}` in increasing bitmask order.

```java
// Given x > 0, returns the smallest y > x with Integer.bitCount(y) == bitCount(x)
int gospersNext(int x) {
    int c = x & -x;         // lowest set bit
    int r = x + c;           // bump the low block
    return (((r ^ x) >> 2) / c) | r;
}

// Iterate all k-subsets of [0, n)
int x = (1 << k) - 1, limit = 1 << n;
while (x < limit) {
    // use x
    x = gospersNext(x);
}
```

### Submask Enumeration

```java
for (int s = mask; s > 0; s = (s - 1) & mask) {
    // s runs over all non-empty submasks of mask
}
// Total work across all masks of size n: O(3^n) — the classic "subset sum over subsets" bound.
```

### Add Without `+`

```java
int add(int a, int b) {
    while (b != 0) {
        int carry = (a & b) << 1;
        a ^= b;
        b = carry;
    }
    return a;
}
```

## Java Snippets for Canonical Operations

```java
// Standard library shortcuts worth memorizing
Integer.bitCount(x);           // popcount
Integer.numberOfLeadingZeros(x);  // 32 for 0, else position of top bit
Integer.numberOfTrailingZeros(x); // 32 for 0, else position of low bit
Integer.highestOneBit(x);      // isolates top set bit (0 if x == 0)
Integer.lowestOneBit(x);       // equivalent to x & -x
Integer.reverse(x);
Integer.toBinaryString(x);     // for debugging; prints without leading zeros
Long.bitCount(mask);           // use Long when n > 31
```

```java
// Single Number (LeetCode 136) — every element twice except one
int singleNumber(int[] nums) {
    int x = 0;
    for (int n : nums) x ^= n;
    return x;
}

// Single Number II (LC 137) — every element three times except one
int singleNumberII(int[] nums) {
    int ones = 0, twos = 0;
    for (int n : nums) {
        ones = (ones ^ n) & ~twos;
        twos = (twos ^ n) & ~ones;
    }
    return ones;
}

// Single Number III (LC 260) — exactly two uniques
int[] singleNumberIII(int[] nums) {
    int xor = 0;
    for (int n : nums) xor ^= n;
    int diff = xor & -xor;          // a bit where the two answers differ
    int a = 0, b = 0;
    for (int n : nums) {
        if ((n & diff) == 0) a ^= n;
        else                 b ^= n;
    }
    return new int[]{a, b};
}

// Subsets via bitmask (n ≤ 20-ish)
List<List<Integer>> subsets(int[] nums) {
    int n = nums.length;
    List<List<Integer>> out = new ArrayList<>(1 << n);
    for (int mask = 0; mask < (1 << n); mask++) {
        List<Integer> cur = new ArrayList<>(Integer.bitCount(mask));
        for (int i = 0; i < n; i++)
            if ((mask & (1 << i)) != 0) cur.add(nums[i]);
        out.add(cur);
    }
    return out;
}

// Counting bits [0..n] — DP on x & (x-1)
int[] countBits(int n) {
    int[] dp = new int[n + 1];
    for (int i = 1; i <= n; i++) dp[i] = dp[i & (i - 1)] + 1;
    return dp;
}

// Missing Number — XOR of [0..n] against the array
int missingNumber(int[] nums) {
    int x = nums.length;
    for (int i = 0; i < nums.length; i++) x ^= i ^ nums[i];
    return x;
}

// Power of two / four
boolean isPowerOfTwo(int x)  { return x > 0 && (x & (x - 1)) == 0; }
boolean isPowerOfFour(int x) { return isPowerOfTwo(x) && (x & 0x55555555) != 0; }
```

## Bitmask DP — 60-Second Primer

When `n ≤ 20` and you need to track "which of these have I already used", make the set the state.

```java
// Assignment problem: minimum cost to assign n workers to n tasks
int assign(int[][] cost) {
    int n = cost.length, FULL = (1 << n) - 1;
    int[] dp = new int[1 << n];
    Arrays.fill(dp, Integer.MAX_VALUE / 2);
    dp[0] = 0;
    for (int mask = 0; mask < (1 << n); mask++) {
        int i = Integer.bitCount(mask);      // next worker to assign
        if (i == n) continue;
        for (int j = 0; j < n; j++) {
            if ((mask & (1 << j)) != 0) continue;
            int next = mask | (1 << j);
            dp[next] = Math.min(dp[next], dp[mask] + cost[i][j]);
        }
    }
    return dp[FULL];
}
```

See `09-dynamic-programming.md` (Bitmask DP, TSP) for the longer form.

## Ordered Practice Problems (easy → hard)

1. **Number of 1 Bits (LC 191)** — loop `x &= x - 1`.
2. **Missing Number (LC 268)** — XOR `[0..n]` against `nums`.
3. **Single Number (LC 136)** — XOR everything.
4. **Power of Two (LC 231)** — `x > 0 && (x & (x-1)) == 0`.
5. **Counting Bits (LC 338)** — DP with `dp[i] = dp[i & (i-1)] + 1`.
6. **Reverse Bits (LC 190)** — swap nibbles, bytes, halves, or loop 32 times.
7. **Sum of Two Integers (LC 371)** — half-adder until carry is zero.
8. **Single Number II (LC 137)** — two-register state machine `ones`/`twos`.
9. **Single Number III (LC 260)** — XOR all, split on a diff bit.
10. **Bitwise AND of Numbers Range (LC 201)** — drop common prefix with `Integer.numberOfLeadingZeros`.
11. **Maximum XOR of Two Numbers in Array (LC 421)** — trie of bits or greedy bit-by-bit with a `HashSet`.
12. **Shortest Path Visiting All Nodes (LC 847)** — BFS with state `(node, mask)` (hard bitmask BFS).

## Implement From Memory Drills

1. `popcount(int)` using Brian Kernighan — no library calls.
2. `subsetsBitmask(int[])` that returns the power set as `List<List<Integer>>`.
3. `gospersNext(int)` plus a loop that prints every 3-bit combination in `[0, 6)`.

## Oral Interview Questions

**Q: What's the difference between `>>` and `>>>` in Java?**
`>>` is arithmetic — it sign-extends, so `-1 >> 1 == -1`. `>>>` is logical — it fills with zeros, so `-1 >>> 1 == Integer.MAX_VALUE`. Use `>>>` when you're treating an `int` as unsigned (e.g., hashing, bit counting from the top).

**Q: How do you test whether `x` is a power of two?**
`x > 0 && (x & (x - 1)) == 0`. Subtracting 1 flips the lowest set bit and everything below it; ANDing with the original gives zero only if `x` has exactly one set bit. The `x > 0` guard is essential — otherwise `0` and negatives sneak through.

**Q: Why does XOR solve "find the unique number" problems?**
XOR is its own inverse and is commutative and associative, so pairs of equal values cancel regardless of order. Whatever's left after XORing everything is the value that appeared an odd number of times.

**Q: What's Brian Kernighan's algorithm?**
`x & (x - 1)` clears the lowest set bit of `x`. Looping this until `x == 0` counts set bits in `O(popcount(x))` instead of `O(word size)` — great when the word is sparsely populated.

**Q: When would you choose bitmask DP?**
When the state is "which subset of up to ~20 items have I already used" and the answer depends only on that subset (plus maybe a current position). Typical examples: TSP, assignment, "shortest path visiting all nodes", "minimum cost to cover all". Beyond ~20 you need smarter state.

## Edge Cases and Gotchas

- `1 << 31` is `Integer.MIN_VALUE`, not a positive number. Use `1L << 31` if you need the unsigned value.
- Shift counts are taken mod 32 (int) or 64 (long). `x << 32` equals `x`, not 0.
- `Math.abs(Integer.MIN_VALUE)` is still `Integer.MIN_VALUE` — two's complement has one more negative than positive.
- `~0 == -1`, not `0xFFFFFFFF` as a positive value. Java has no unsigned int.
- Mixing `int` and `long`: `1 << k` where `k >= 32` silently wraps. Write `1L << k`.
- `x & -x` on `Integer.MIN_VALUE` returns `Integer.MIN_VALUE` — the sign bit is itself the lowest set bit.
- XOR of a `long` and an `int`: the `int` is promoted, but watch sign extension on negative ints.
- `Integer.numberOfLeadingZeros(0)` is `32`, and `highestOneBit(0)` is `0` — guard the zero case.
- When reading bits from MSB to LSB, iterate `i` from `31` down to `0` — easy to reverse by accident.

## Complexity Summary

| Operation | Time | Space |
|-----------|------|-------|
| Single bit test / set / clear / toggle | O(1) | O(1) |
| `Integer.bitCount` | O(1) hardware popcnt, worst O(log w) | O(1) |
| Brian Kernighan popcount | O(popcount(x)) | O(1) |
| Enumerate all subsets of n bits | O(2^n) masks, O(n · 2^n) with per-bit work | O(1) extra |
| Enumerate submasks over all masks | O(3^n) total | O(1) extra |
| Gosper iteration over all k-subsets of n | O(C(n, k)) | O(1) extra |
| Bitmask DP (TSP-style) | O(2^n · n^2) | O(2^n · n) |
| XOR-based single-number scan | O(n) | O(1) |
