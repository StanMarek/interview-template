# String Algorithms

## Overview

String problems cluster around four questions: "does pattern P occur in text T?", "where does it occur?", "what's the longest common/repeating/palindromic substructure?", and "how do I search many patterns at once?". The algorithms below cover the first three; for the fourth, see the Aho-Corasick note at the end and the trie material in `08-tries.md`.

Java specifics up front:

- `String` is immutable and has an internal `hash` cache; repeated `hashCode()` is free after the first call.
- `String.charAt(i)` is O(1) — it indexes a backing `byte[]`/`char[]`. `substring(i, j)` copies in modern JDKs (since Java 7u6), so it's O(j - i).
- Prefer `char[]` for inner loops when you'll do thousands of accesses; `s.toCharArray()` is one O(n) copy, then every read is a field-free array access.
- Build dynamic strings with `StringBuilder`. `+` in a loop is O(n²).
- Compare with `.equals` (never `==`), and when comparing lots of strings use `Objects.equals` or `String::equals` method references to avoid nulls.

## Core Concepts and Mental Model

### The Matching Problem

- **Text** `T` of length `n`, **pattern** `P` of length `m`.
- Naive matching: try each alignment, compare until mismatch. `O(nm)` worst case, `O(n + m)` average for random inputs.
- **KMP** precomputes a failure function on `P` so the text pointer never backtracks. `O(n + m)`.
- **Rabin-Karp** compares hashes of every length-`m` window. `O(n + m)` expected, `O(nm)` worst case with a single hash; `O(n + m)` with polynomial + double hashing and randomized bases.
- **Z-algorithm** computes, for each position `i` of a string, the length of the longest substring starting at `i` that matches a prefix. Used for pattern matching (run Z on `P + "#" + T`) and many other things.

### Palindromes

Every palindrome has a center: a character (odd length) or a gap between characters (even length). "Expand around center" handles both in `O(n²)`. **Manacher's algorithm** reuses work across centers to hit `O(n)`.

### Suffix Structures

- **Trie of suffixes** — `O(n²)` space, conceptually clean, useful for small strings.
- **Suffix Array** — sorted list of all suffix indices; supports pattern search in `O(m log n)`. Pair with **LCP array** for "longest common substring" and repeated substring queries. Constructible in `O(n log n)` (doubling) or `O(n)` (SA-IS, rarely asked to implement).
- **Suffix Automaton / Tree** — powerful but out of scope for most interviews.

### Rolling Hash

Treat a window of characters as digits of a base-`B` number modulo prime `M`:

```
h(s[0..m-1]) = s[0] * B^(m-1) + s[1] * B^(m-2) + ... + s[m-1]   (mod M)
```

Sliding the window by one character costs O(1): subtract the leaving character's contribution, multiply by `B`, add the new character. Use **two** hashes (different `B` and/or `M`) to kill adversarial collisions.

## Must-Know Operations and Patterns

### KMP — Failure (LPS) Function

For each prefix `P[0..i]`, `lps[i]` is the length of the longest proper prefix that is also a suffix. The trick is that when a mismatch happens, we shift `P` by `i - lps[i-1]` positions without moving `T`'s pointer.

```java
int[] buildLps(String p) {
    int m = p.length();
    int[] lps = new int[m];
    int len = 0;                   // length of current matched prefix
    for (int i = 1; i < m; ) {
        if (p.charAt(i) == p.charAt(len)) lps[i++] = ++len;
        else if (len > 0)                 len = lps[len - 1];
        else                               lps[i++] = 0;
    }
    return lps;
}

int kmpSearch(String t, String p) {   // returns first index or -1
    if (p.isEmpty()) return 0;
    int[] lps = buildLps(p);
    int i = 0, j = 0, n = t.length(), m = p.length();
    while (i < n) {
        if (t.charAt(i) == p.charAt(j)) { i++; j++; if (j == m) return i - m; }
        else if (j > 0) j = lps[j - 1];
        else            i++;
    }
    return -1;
}
```

### Rabin-Karp — Rolling Hash

```java
// Single polynomial hash — fine for most interviews; add a second hash in hostile conditions.
int rabinKarp(String t, String p) {
    int n = t.length(), m = p.length();
    if (m == 0) return 0;
    if (m > n) return -1;
    final long BASE = 131, MOD = 1_000_000_007L;
    long pHash = 0, tHash = 0, power = 1;
    for (int i = 0; i < m; i++) {
        pHash = (pHash * BASE + p.charAt(i)) % MOD;
        tHash = (tHash * BASE + t.charAt(i)) % MOD;
        if (i < m - 1) power = power * BASE % MOD;
    }
    for (int i = 0; i <= n - m; i++) {
        if (tHash == pHash && t.regionMatches(i, p, 0, m)) return i;  // verify!
        if (i < n - m) {
            tHash = ((tHash - t.charAt(i) * power % MOD + MOD * MOD) * BASE
                    + t.charAt(i + m)) % MOD;
        }
    }
    return -1;
}
```

`regionMatches` is the cheap built-in character-by-character check — always verify a hash hit; collisions happen.

### Z-Algorithm

```java
int[] zFunction(String s) {
    int n = s.length();
    int[] z = new int[n];
    for (int i = 1, l = 0, r = 0; i < n; i++) {
        if (i < r) z[i] = Math.min(r - i, z[i - l]);
        while (i + z[i] < n && s.charAt(z[i]) == s.charAt(i + z[i])) z[i]++;
        if (i + z[i] > r) { l = i; r = i + z[i]; }
    }
    return z;
}

// Pattern matching: run z on p + '#' + t, find positions where z[i] == p.length().
int zSearch(String t, String p) {
    String glued = p + '\u0001' + t;
    int[] z = zFunction(glued);
    for (int i = p.length() + 1; i < glued.length(); i++) {
        if (z[i] >= p.length()) return i - p.length() - 1;
    }
    return -1;
}
```

### Expand Around Center — Palindromes

```java
String longestPalindrome(String s) {
    if (s == null || s.isEmpty()) return "";
    int start = 0, end = 0;
    for (int i = 0; i < s.length(); i++) {
        int lenOdd  = expand(s, i, i);
        int lenEven = expand(s, i, i + 1);
        int len = Math.max(lenOdd, lenEven);
        if (len > end - start) {
            start = i - (len - 1) / 2;
            end = i + len / 2;
        }
    }
    return s.substring(start, end + 1);
}
private int expand(String s, int l, int r) {
    while (l >= 0 && r < s.length() && s.charAt(l) == s.charAt(r)) { l--; r++; }
    return r - l - 1;
}
```

**Manacher's algorithm** (one-pass `O(n)`) is worth knowing by name; the idea is the same as Z — reuse the mirror of the current right-most palindrome. Code it only if you've had reps with it; otherwise the O(n²) expand-around-center almost always fits.

### Trie — Revisited for String Search

Tries excel when you need to match **many** patterns against text, or answer prefix queries. See `08-tries.md` for the class template. The short form:

- `insert(word)` — walk/create children, mark end-of-word on the final node.
- `search(word)` — walk; must land on a word node.
- `startsWith(prefix)` — walk; any node is fine.

Augmentations: store the word index at end nodes for Word Search II, or suffix links to build an **Aho-Corasick** automaton for multi-pattern search in `O(|text| + total pattern length + hits)`.

## Common Interview Patterns

| Pattern | Cue | Tool |
|---------|-----|------|
| "Does P occur in T?" — one pattern, one text | standard search | KMP (deterministic) or Rabin-Karp (easier to code) |
| "All occurrences of P in T" | all matches | KMP reports each `j == m` hit |
| "Longest palindromic substring" | palindrome | Expand around center; Manacher if O(n) needed |
| "Longest common substring of two strings" | two-string suffix | DP `O(nm)` or suffix array + LCP |
| "Repeated substring pattern" (LC 459) | periodicity | `s` occurs inside `s+s` minus endpoints; or check `n % (n - lps[n-1]) == 0` |
| "Shortest palindrome prepended" (LC 214) | prefix palindrome | KMP on `s + '#' + reverse(s)` |
| "Anagrams in a string" | fixed-size window | sliding window + count array |
| "Multiple patterns against one text" | `k` patterns | Aho-Corasick |
| "Longest repeated substring" | duplicate detection | Rabin-Karp + binary search on length, or suffix array |
| "Find / replace with wildcards" | `?` / `*` | regex DP (`09-dp`, wildcard matching) |
| "Minimum window with all chars of T" | sliding window | two-pointer + counts (see `01-arrays-and-strings`) |

## Java Snippets for Canonical Operations

```java
// char[] is the workhorse — one allocation, then O(1) indexing
char[] a = s.toCharArray();

// Building strings
StringBuilder sb = new StringBuilder(capacityHint);
sb.append(ch).append(other).append(num);
String out = sb.toString();

// Fast comparison without substring allocation (Java has no named args — all positional).
// Signature: regionMatches(boolean ignoreCase, int thisOffset, String other, int otherOffset, int len)
s.regionMatches(false, offset, other, 0, length);

// Character bucket for lowercase letters (cheap frequency counter)
int[] count = new int[26];
for (char c : a) count[c - 'a']++;

// Split on a regex is expensive; for a single delimiter, prefer indexOf + substring
int idx = s.indexOf(':');
String head = s.substring(0, idx), tail = s.substring(idx + 1);

// String.join and chars() stream for functional-style pipelines
String joined = String.join(",", parts);
long vowels = s.chars().filter(c -> "aeiou".indexOf(c) >= 0).count();

// Repeated substring pattern trick (LC 459)
boolean repeated = (s + s).indexOf(s, 1) < s.length();

// Anagram check via sort
boolean anagram = Arrays.equals(sorted(s.toCharArray()), sorted(t.toCharArray()));
// Or via bucket counts — O(n), no sort needed.
```

### Suffix Array — O(n log² n) Simple Build

Straightforward version via sort of doubled keys; good enough for n up to ~10^5 in Java.

```java
int[] suffixArray(String s) {
    int n = s.length();
    Integer[] sa = new Integer[n];
    int[] rank = new int[n], tmp = new int[n];
    for (int i = 0; i < n; i++) { sa[i] = i; rank[i] = s.charAt(i); }
    for (int k = 1; k < n; k <<= 1) {
        final int kk = k, nn = n;
        final int[] r = rank;
        Arrays.sort(sa, (i, j) -> {
            if (r[i] != r[j]) return Integer.compare(r[i], r[j]);
            int ri = i + kk < nn ? r[i + kk] : -1;
            int rj = j + kk < nn ? r[j + kk] : -1;
            return Integer.compare(ri, rj);
        });
        tmp[sa[0]] = 0;
        for (int i = 1; i < n; i++) {
            tmp[sa[i]] = tmp[sa[i - 1]];
            int ai = sa[i], bi = sa[i - 1];
            int rai = ai + k < n ? rank[ai + k] : -1;
            int rbi = bi + k < n ? rank[bi + k] : -1;
            if (rank[ai] != rank[bi] || rai != rbi) tmp[sa[i]]++;
        }
        System.arraycopy(tmp, 0, rank, 0, n);
        if (rank[sa[n - 1]] == n - 1) break;
    }
    int[] out = new int[n];
    for (int i = 0; i < n; i++) out[i] = sa[i];
    return out;
}

// Kasai's LCP — O(n) given SA
int[] kasaiLcp(String s, int[] sa) {
    int n = s.length();
    int[] rank = new int[n], lcp = new int[n - 1];
    for (int i = 0; i < n; i++) rank[sa[i]] = i;
    int h = 0;
    for (int i = 0; i < n; i++) {
        if (rank[i] > 0) {
            int j = sa[rank[i] - 1];
            while (i + h < n && j + h < n && s.charAt(i + h) == s.charAt(j + h)) h++;
            lcp[rank[i] - 1] = h;
            if (h > 0) h--;
        } else h = 0;
    }
    return lcp;
}
```

## Ordered Practice Problems (easy → hard)

1. **Valid Anagram (LC 242)** — 26-bucket count or sort.
2. **Implement `strStr()` (LC 28)** — write KMP or Rabin-Karp; `indexOf` is cheating.
3. **Longest Common Prefix (LC 14)** — vertical scan.
4. **Group Anagrams (LC 49)** — sorted key or count-tuple key into a `Map<String, List<String>>`.
5. **Longest Palindromic Substring (LC 5)** — expand around center.
6. **Palindrome Partitioning (LC 131)** — backtracking; see `11-backtracking.md`.
7. **Repeated Substring Pattern (LC 459)** — `(s+s).indexOf(s, 1) < n`, or LPS-based.
8. **Find All Anagrams in a String (LC 438)** — sliding window with bucket counts.
9. **Longest Substring Without Repeating Characters (LC 3)** — two-pointer with last-seen map.
10. **Minimum Window Substring (LC 76)** — window + required/have counters.
11. **Shortest Palindrome (LC 214)** — KMP on `s + '#' + reverse(s)`.
12. **Regular Expression / Wildcard Matching (LC 10 / 44)** — DP over `(i, j)`; see `09-dynamic-programming.md`.

## Implement From Memory Drills

1. `buildLps(String p)` plus `kmpSearch(String t, String p)` returning the first match index or `-1`.
2. `rabinKarp(String t, String p)` with a rolling polynomial hash and `regionMatches` verification.
3. `longestPalindrome(String s)` using expand-around-center for both odd and even centers.

## Oral Interview Questions

**Q: Why is KMP `O(n + m)` even though each pattern character can be revisited?**
The text pointer `i` never moves backwards — it only increases or stays put across the whole algorithm, at most `n` advances. The pattern pointer `j` can decrease via `lps[j-1]`, but each decrease is paid for by an earlier increase. Amortized, `j` also does `O(m)` work across the preprocessing plus `O(n)` across the search.

**Q: What's the failure mode of single-hash Rabin-Karp?**
Adversarial inputs can produce many hash collisions and degrade to `O(nm)`. Mitigations: use a large prime modulus, a random base chosen at runtime, or two independent hashes combined. Always verify a hash hit with a direct character comparison — treat the hash as a filter, not a proof.

**Q: When would you prefer expand-around-center over Manacher's algorithm?**
Almost always, unless `n` is very large (say `n ≥ 10^6`) and you genuinely need `O(n)`. Expand-around-center is `O(n²)` worst case but it's simple, trivial to get right, and has tiny constants. Manacher's is harder to write correctly under interview stress.

**Q: How does a suffix array answer "longest common substring of two strings"?**
Concatenate `s1 + '#' + s2` with a separator character that appears in neither. Build the suffix array and LCP array over the combined string. Walk the LCP; the answer is the maximum `lcp[i]` where adjacent suffixes come from different halves (one starts in `s1`, the other in `s2`). `O(n log n)` build, `O(n)` scan.

**Q: `String` vs `char[]` in Java — when do you switch?**
Reach for `char[]` when you'll do many random reads/writes in a hot loop — `toCharArray()` is one O(n) copy, and then you skip the method-call overhead of `charAt` and the bounds-check redundancy. Stay with `String` when you're composing, slicing, or just comparing, because `String` has the cached hash, `equals`/`hashCode`, and `regionMatches`. For mutation, `StringBuilder`; `char[]` is only for fixed-size in-place work.

## Edge Cases and Gotchas

- Empty pattern: define the semantics up front. Most libraries (`String.indexOf`) return `0` for empty needles.
- `substring` in modern Java copies; repeatedly peeling prefixes in a loop is `O(n²)`. Track indices instead.
- `+` in a loop allocates a new `String` each iteration. Use `StringBuilder`.
- Unicode: `String.length()` counts UTF-16 code units, not code points. For emoji or CJK beyond the BMP, use `s.codePointCount(0, s.length())` and iterate with `codePoints()`.
- `s.chars()` returns an `IntStream` of `char` values, not code points — fine for ASCII, wrong for surrogate pairs.
- Case sensitivity: `equalsIgnoreCase` and `compareToIgnoreCase` are locale-insensitive for ASCII; use `Collator` for locale-aware comparisons (Turkish `I` being the usual trap).
- KMP `lps` off-by-one: `lps[i]` is the length of the longest proper prefix-suffix of `P[0..i]`. `lps[0] = 0` always. When `j == m`, record a match and set `j = lps[m-1]` to keep searching.
- Rabin-Karp modular subtraction can go negative: always `((x - y) % M + M) % M` or `Math.floorMod(x - y, M)`.
- Palindrome with single character: expand-around-center must still return length 1, not 0.
- Splitting on `"."` with `String.split`: the argument is a regex, so `.` matches anything. Escape it (`"\\."`) or use `Pattern.quote(".")`.
- Manacher's algorithm needs a sentinel array (`#a#b#c#`); forgetting it breaks the even-length case.

## Complexity Summary

| Algorithm | Preprocess | Match | Space | Notes |
|-----------|-----------|-------|-------|-------|
| Naive search | — | O(nm) worst, O(n + m) avg | O(1) | Good enough for tiny inputs |
| KMP | O(m) | O(n) | O(m) | Deterministic linear |
| Rabin-Karp (single hash) | O(m) | O(n + m) expected, O(nm) worst | O(1) | Verify hits; double-hash to toughen |
| Z-algorithm | O(m) on pattern (+ sentinel) | O(n) | O(n + m) | Also used for many prefix/period problems |
| Aho-Corasick (k patterns, total len L) | O(L) | O(n + hits) | O(L · Σ) | Multi-pattern search |
| Expand around center | — | O(n²) | O(1) | Longest palindrome |
| Manacher | — | O(n) | O(n) | Longest palindrome, trickier code |
| Suffix array (doubling) | O(n log² n) or O(n log n) | O(m log n) per pattern | O(n) | Pair with LCP for many substring queries |
| Kasai LCP | O(n) given SA | — | O(n) | Adjacent-suffix LCPs |
| Trie insert / search | O(L) per op | — | O(Σ · total chars) | See `08-tries.md` |
