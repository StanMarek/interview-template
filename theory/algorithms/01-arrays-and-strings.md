# Arrays and Strings

## Overview

Arrays are the most fundamental data structure — a contiguous block of memory storing elements of the same type. Strings in Java are immutable character arrays backed by `byte[]` (since Java 9, JEP 254 Compact Strings); a coder flag marks LATIN1 vs UTF-16 content. Nearly every coding interview will involve at least one array or string problem.

## Core Concepts

### Arrays in Java

```java
// Declaration and initialization
int[] nums = new int[10];             // default values: 0
int[] nums = {1, 2, 3, 4, 5};        // inline init
int[][] matrix = new int[3][4];       // 2D array

// Key properties
nums.length;                          // size (field, not method)
Arrays.sort(nums);                    // O(n log n) — TimSort
Arrays.fill(nums, -1);               // fill with value
Arrays.copyOf(nums, nums.length);    // shallow copy
Arrays.equals(a, b);                 // content equality
```

### Strings in Java

```java
String s = "hello";
s.length();                           // 5 (method, not field)
s.charAt(0);                          // 'h'
s.substring(1, 3);                    // "el" (inclusive, exclusive)
s.indexOf("ll");                      // 2
s.toCharArray();                      // char[] {'h','e','l','l','o'}
s.equals("hello");                    // true (NEVER use ==)
s.compareTo("world");                // lexicographic comparison

// StringBuilder for mutable string operations — O(1) amortized append
StringBuilder sb = new StringBuilder();
sb.append("hello");
sb.insert(0, "x");
sb.delete(0, 1);
sb.reverse();
sb.toString();
```

### Modern String APIs (Java 11+)

```java
s.isBlank();                          // true if empty or only whitespace (Java 11)
s.strip();                            // Unicode-aware trim (Java 11)
"ab".repeat(3);                       // "ababab" (Java 11)
s.lines();                            // Stream<String> split on line terminators (Java 11)
s.chars();                            // IntStream of char codes (useful for frequency, filtering)
s.codePoints();                       // IntStream — correct for non-BMP / emoji
"%s is %d".formatted("x", 5);         // instance-form of String.format (Java 15)
// Text blocks (Java 15) — multi-line literals, incidental whitespace stripped
String json = """
        {"k": "v"}
        """;
```

### Arrays ↔ Streams (idiomatic conversions)

```java
int[] a = IntStream.rangeClosed(1, n).toArray();       // build [1..n]
int sum = Arrays.stream(nums).sum();                   // also .min/.max/.average/.summaryStatistics()
int[] sorted = Arrays.stream(nums).sorted().toArray();
List<Integer> boxed = Arrays.stream(nums).boxed().toList();   // Java 16+ immutable list

// Pitfall: Arrays.asList(int[]) returns List<int[]> of size 1 — use boxed stream or Arrays.stream
List<Integer> list = List.of(1, 2, 3);                 // immutable, disallows null
List<Integer> mutable = new ArrayList<>(List.of(1, 2, 3));
```

### Time Complexity

| Operation                           | Array     | ArrayList | String        | StringBuilder |
|-------------------------------------|-----------|-----------|---------------|---------------|
| Access by index                     | O(1)      | O(1)      | O(1)          | O(1)          |
| Search (unsorted)                   | O(n)      | O(n)      | O(n)          | O(n)          |
| Search (sorted)                     | O(log n)  | O(log n)  | —             | —             |
| Insert at end (ArrayList, not raw)  | O(1)*     | O(1)*     | O(n) (new)    | O(1)*         |
| Insert at middle                    | O(n)      | O(n)      | O(n)          | O(n)          |
| Delete                              | O(n)      | O(n)      | O(n)          | O(n)          |
| Concatenation                       | —         | —         | O(n+m)        | O(m)          |

*Amortized — may trigger resize/copy. Raw Java arrays are fixed-length; amortized O(1) append only applies to `ArrayList`/`ArrayDeque`.

## Essential Techniques and Patterns

### 1. Two Pointers

Used when you need to find pairs, partition arrays, or shrink a search window.

```java
// Classic: Two Sum on a SORTED array
public int[] twoSum(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int sum = nums[lo] + nums[hi];
        if (sum == target) return new int[]{lo, hi};
        else if (sum < target) lo++;
        else hi--;
    }
    return new int[]{-1, -1};
}
```

**When to use:** sorted arrays, palindrome checks, container problems, removing duplicates in-place.

### 2. Sliding Window

Maintain a window `[left, right]` over contiguous elements. Expand right, shrink left.

```java
// Longest substring without repeating characters
public int lengthOfLongestSubstring(String s) {
    Map<Character, Integer> map = new HashMap<>();
    int maxLen = 0, left = 0;
    for (int right = 0; right < s.length(); right++) {
        char c = s.charAt(right);
        if (map.containsKey(c)) {
            left = Math.max(left, map.get(c) + 1);
        }
        map.put(c, right);
        maxLen = Math.max(maxLen, right - left + 1);
    }
    return maxLen;
}
```

**When to use:** subarray/substring problems asking for max/min length, fixed-size windows, anagram detection.

### 3. Prefix Sum

Precompute cumulative sums to answer range-sum queries in O(1).

```java
int[] prefix = new int[nums.length + 1];
for (int i = 0; i < nums.length; i++) {
    prefix[i + 1] = prefix[i] + nums[i];
}
// Sum of nums[i..j] inclusive = prefix[j+1] - prefix[i]
```

**When to use:** subarray sum equals K, range queries, 2D matrix sum queries.

### 4. In-Place Manipulation

Modify the array without extra space — swap, overwrite, or use the array itself as a hash map.

```java
// Move Zeroes to end — maintain insertion pointer
public void moveZeroes(int[] nums) {
    int insertPos = 0;
    for (int num : nums) {
        if (num != 0) nums[insertPos++] = num;
    }
    while (insertPos < nums.length) nums[insertPos++] = 0;
}
```

### 5. Kadane's Algorithm (Maximum Subarray)

```java
public int maxSubArray(int[] nums) {
    int maxSum = nums[0], curSum = nums[0];
    for (int i = 1; i < nums.length; i++) {
        curSum = Math.max(nums[i], curSum + nums[i]);
        maxSum = Math.max(maxSum, curSum);
    }
    return maxSum;
}
```

### 6. Dutch National Flag (3-Way Partition)

```java
// Sort Colors (0, 1, 2) in one pass
public void sortColors(int[] nums) {
    int lo = 0, mid = 0, hi = nums.length - 1;
    while (mid <= hi) {
        if (nums[mid] == 0) swap(nums, lo++, mid++);
        else if (nums[mid] == 1) mid++;
        else swap(nums, mid, hi--);
    }
}
```

### 7. Boyer–Moore Majority Vote

O(n) time, O(1) space — finds the element appearing > n/2 times (assumed to exist).

```java
public int majorityElement(int[] nums) {
    int candidate = 0, count = 0;
    for (int n : nums) {
        if (count == 0) candidate = n;
        count += (n == candidate) ? 1 : -1;
    }
    return candidate;   // verify with second pass if not guaranteed
}
```

**When to use:** majority/dominant element problems with O(1) space constraint. Generalizes to "elements appearing > n/3 times" with two candidates.

### 8. Cyclic Sort

When values are in range `[0..n]` or `[1..n]`, place each value at its target index via swaps — O(n) time, O(1) space.

```java
// Find the missing number in [0..n]
public int missingNumber(int[] nums) {
    int i = 0;
    while (i < nums.length) {
        if (nums[i] < nums.length && nums[i] != i) swap(nums, i, nums[i]);
        else i++;
    }
    for (int j = 0; j < nums.length; j++) if (nums[j] != j) return j;
    return nums.length;
}
```

**When to use:** "array contains numbers 1..n" with missing/duplicate/first-missing-positive problems requiring O(1) extra space.

### 9. Difference Array (Range Updates)

Apply many range updates `[l, r] += v` in O(1) each, then reconstruct the final array via prefix sum in O(n). Inverse of prefix sum.

```java
int[] diff = new int[n + 1];
for (int[] u : updates) {           // u = {l, r, val}
    diff[u[0]] += u[2];
    diff[u[1] + 1] -= u[2];
}
int[] result = new int[n];
result[0] = diff[0];
for (int i = 1; i < n; i++) result[i] = result[i - 1] + diff[i];
```

**When to use:** many overlapping range updates with a single final query — e.g. "Range Addition", "Car Pooling", "Corporate Flight Bookings".

### 10. Reverse In-Place (Two Pointers)

```java
public void reverse(int[] a) {
    for (int lo = 0, hi = a.length - 1; lo < hi; lo++, hi--) swap(a, lo, hi);
}
// Rotate array right by k: reverse(0,n-1); reverse(0,k-1); reverse(k,n-1)
```

### 11. Palindromes — Expand Around Center / Manacher's

Standard interview approach: for each index, expand outward for both odd and even centers — O(n²) time, O(1) space.

```java
public String longestPalindrome(String s) {
    int start = 0, maxLen = 0;
    for (int i = 0; i < s.length(); i++) {
        int l1 = expand(s, i, i);       // odd length
        int l2 = expand(s, i, i + 1);   // even length
        int len = Math.max(l1, l2);
        if (len > maxLen) { maxLen = len; start = i - (len - 1) / 2; }
    }
    return s.substring(start, start + maxLen);
}
private int expand(String s, int l, int r) {
    while (l >= 0 && r < s.length() && s.charAt(l) == s.charAt(r)) { l--; r++; }
    return r - l - 1;
}
```

**Manacher's algorithm** solves longest palindromic substring in O(n) but is rarely expected in interviews — mention it as the optimal bound; expand-around-center is usually accepted.

## Common Interview Problems by Category

### Easy
- Two Sum, Best Time to Buy and Sell Stock, Contains Duplicate
- Valid Anagram, Valid Palindrome, Merge Sorted Array
- Majority Element, Missing Number, Reverse String

### Medium
- 3Sum, Product of Array Except Self, Container With Most Water
- Longest Substring Without Repeating Characters, Group Anagrams
- Rotate Image, Spiral Matrix, Set Matrix Zeroes, Next Permutation
- Subarray Sum Equals K, Minimum Window Substring

### Hard
- Trapping Rain Water, Median of Two Sorted Arrays
- Longest Consecutive Sequence, First Missing Positive
- Minimum Window Substring, Sliding Window Maximum

## Tips and Pitfalls

- **Off-by-one errors:** Always double-check loop bounds, especially `<` vs `<=` and inclusive vs exclusive indices.
- **Integer overflow:** Use `long` for sums or products, or `(lo + hi) >>> 1` for midpoint to avoid overflow. Note: the idiomatic overflow-safe form per Bloch/Sedgewick is `lo + (hi - lo) / 2`; both work, but the latter is more widely recognized.
- **String immutability:** Never concatenate strings in a loop — use `StringBuilder`. Each `+=` creates a new object → O(n²).
- **Empty/null checks:** Always handle `null`, empty arrays, and single-element edge cases first.
- **Sorting as preprocessing:** Many problems become simpler after sorting. Ask yourself: "Would sorting help here?"
- **Hashing vs. sorting:** HashMap gives O(n) but uses O(n) space; sorting gives O(n log n) with O(1) space.
- **Character arrays for fixed alphabet:** `int[26]` or `int[128]` is faster and cleaner than `HashMap<Character, Integer>`.
- **`Arrays.asList` vs `List.of`:** `Arrays.asList(arr)` on `int[]` yields `List<int[]>` (single element!) — always use `Arrays.stream(arr).boxed().toList()` to box primitives. `List.of(...)` returns an immutable list and throws on `null`.
- **`toList()` vs `Collectors.toList()`:** prefer `Stream.toList()` (Java 16+) — it's immutable and shorter. Use `collect(Collectors.toCollection(ArrayList::new))` if you need mutability.
- **Unicode correctness:** `s.charAt(i)` returns a UTF-16 code unit; for emoji / supplementary characters iterate via `s.codePoints()`.
- **Non-printable / ASCII assumptions:** verify the problem's alphabet — `int[26]` assumes lowercase-only; prefer `int[128]` for general ASCII.
