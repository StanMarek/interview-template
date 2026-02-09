# Hash Maps and Hash Sets

## Overview

Hash-based data structures provide average O(1) lookup, insertion, and deletion. They are the single most useful tool in coding interviews for reducing brute-force O(n²) solutions to O(n). If you're stuck on any problem, ask yourself: "Can a HashMap help here?"

## Core Concepts

### How Hashing Works

1. A **hash function** converts a key to an integer (hash code).
2. The hash code is mapped to a **bucket index** (usually `hashCode % capacity`).
3. **Collisions** (multiple keys → same bucket) are resolved via chaining (linked list / tree) or open addressing.
4. When the **load factor** exceeds a threshold (default 0.75 in Java), the table **rehashes** — doubles capacity and redistributes entries.

### Java Implementations

```java
// HashMap — key-value pairs, allows one null key
Map<String, Integer> map = new HashMap<>();
map.put("a", 1);
map.get("a");                    // 1
map.getOrDefault("b", 0);       // 0
map.containsKey("a");           // true
map.containsValue(1);           // true (O(n))
map.remove("a");
map.size();
map.isEmpty();

// Iteration
for (Map.Entry<String, Integer> entry : map.entrySet()) {
    entry.getKey(); entry.getValue();
}
map.keySet();
map.values();

// Compute patterns — extremely useful in interviews
map.merge("a", 1, Integer::sum);            // increment or init to 1
map.computeIfAbsent("key", k -> new ArrayList<>()).add(value); // group by
map.putIfAbsent("key", defaultValue);

// HashSet — unique elements
Set<String> set = new HashSet<>();
set.add("a");
set.contains("a");   // true
set.remove("a");

// LinkedHashMap — preserves insertion order
Map<Integer, Integer> lhm = new LinkedHashMap<>();

// LinkedHashMap as LRU Cache
Map<Integer, Integer> lru = new LinkedHashMap<>(16, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
        return size() > CAPACITY;
    }
};

// TreeMap — sorted keys, O(log n) operations
TreeMap<Integer, String> tm = new TreeMap<>();
tm.firstKey();       // smallest
tm.lastKey();        // largest
tm.floorKey(5);      // greatest key ≤ 5
tm.ceilingKey(5);    // smallest key ≥ 5
tm.subMap(1, 10);    // keys in [1, 10)
```

### Time Complexity

| Operation     | HashMap  | TreeMap    | LinkedHashMap |
|---------------|----------|------------|---------------|
| put           | O(1)*    | O(log n)   | O(1)*         |
| get           | O(1)*    | O(log n)   | O(1)*         |
| remove        | O(1)*    | O(log n)   | O(1)*         |
| containsKey   | O(1)*    | O(log n)   | O(1)*         |
| Ordered       | No       | Yes (natural) | Yes (insertion) |

*Average case. Worst case is O(n) with bad hash functions (O(log n) since Java 8 with tree bins).

### Implementing `hashCode()` and `equals()`

When using custom objects as HashMap keys, **you must override both**:

```java
class Point {
    int x, y;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return x == p.x && y == p.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y); // or 31 * x + y
    }
}
```

**Contract:** If `a.equals(b)`, then `a.hashCode() == b.hashCode()`. Violating this breaks HashMap completely.

## Essential Techniques and Patterns

### 1. Frequency Counting

The most common HashMap pattern in interviews.

```java
// Count character frequencies
Map<Character, Integer> freq = new HashMap<>();
for (char c : s.toCharArray()) {
    freq.merge(c, 1, Integer::sum);
}

// Alternative with int array (faster for ASCII)
int[] count = new int[128];
for (char c : s.toCharArray()) count[c]++;

// Check if two strings are anagrams
public boolean isAnagram(String s, String t) {
    if (s.length() != t.length()) return false;
    int[] count = new int[26];
    for (int i = 0; i < s.length(); i++) {
        count[s.charAt(i) - 'a']++;
        count[t.charAt(i) - 'a']--;
    }
    for (int c : count) if (c != 0) return false;
    return true;
}
```

### 2. Two Sum Pattern (Index Lookup)

```java
public int[] twoSum(int[] nums, int target) {
    Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < nums.length; i++) {
        int complement = target - nums[i];
        if (map.containsKey(complement)) {
            return new int[]{map.get(complement), i};
        }
        map.put(nums[i], i);
    }
    return new int[]{-1, -1};
}
```

### 3. Grouping / Bucketing

```java
// Group Anagrams
public List<List<String>> groupAnagrams(String[] strs) {
    Map<String, List<String>> map = new HashMap<>();
    for (String s : strs) {
        char[] arr = s.toCharArray();
        Arrays.sort(arr);
        String key = new String(arr);
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    return new ArrayList<>(map.values());
}
```

### 4. Prefix Sum + HashMap

```java
// Subarray Sum Equals K — count subarrays with sum = k
public int subarraySum(int[] nums, int k) {
    Map<Integer, Integer> prefixCount = new HashMap<>();
    prefixCount.put(0, 1); // empty prefix
    int sum = 0, count = 0;
    for (int num : nums) {
        sum += num;
        count += prefixCount.getOrDefault(sum - k, 0);
        prefixCount.merge(sum, 1, Integer::sum);
    }
    return count;
}
```

### 5. HashSet for Deduplication and Existence Checks

```java
// Longest Consecutive Sequence — O(n)
public int longestConsecutive(int[] nums) {
    Set<Integer> set = new HashSet<>();
    for (int n : nums) set.add(n);

    int maxLen = 0;
    for (int n : set) {
        if (!set.contains(n - 1)) { // start of a sequence
            int len = 1;
            while (set.contains(n + len)) len++;
            maxLen = Math.max(maxLen, len);
        }
    }
    return maxLen;
}
```

### 6. Rolling Hash (Rabin-Karp)

```java
// Substring search using rolling hash
// hash = s[0]*p^(k-1) + s[1]*p^(k-2) + ... + s[k-1]
// Slide: remove s[i]*p^(k-1), multiply by p, add s[i+k]
long BASE = 31, MOD = 1_000_000_007;
```

## Common Interview Problems

### Easy
- Two Sum, Contains Duplicate, Valid Anagram
- Ransom Note, Isomorphic Strings, Word Pattern
- Intersection of Two Arrays, Happy Number

### Medium
- Group Anagrams, Top K Frequent Elements
- Subarray Sum Equals K, Longest Consecutive Sequence
- LRU Cache, Encode and Decode TinyURL
- Longest Substring Without Repeating Characters
- 4Sum II, Copy List with Random Pointer

### Hard
- Minimum Window Substring, Substring with Concatenation of All Words
- First Missing Positive (array-as-hashmap trick)

## Tips and Pitfalls

- **Always use `equals()` for String comparison**, never `==`. The `==` operator compares references, not content.
- **`Integer` cache:** Java caches `Integer` values -128 to 127. Beyond that range, `==` fails — always use `.equals()` or unbox to `int`.
- **Mutable keys are dangerous:** If you modify an object after inserting it as a key, the HashMap can't find it anymore. Use immutable keys.
- **`getOrDefault()` and `merge()`** eliminate most null-check boilerplate.
- **int[] vs HashMap for counting:** Use `int[26]` for lowercase letters, `int[128]` for ASCII. It's faster and cleaner.
- **Map as adjacency list:** `Map<Integer, List<Integer>>` is the standard graph representation in interviews.
- **HashSet from a list:** `new HashSet<>(Arrays.asList(arr))` or `Set.of(...)` for quick lookup tables.
- **Order matters?** Use `LinkedHashMap` (insertion order) or `TreeMap` (sorted order) instead of `HashMap`.
