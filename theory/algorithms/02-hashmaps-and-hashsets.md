# Hash Maps and Hash Sets

## Overview

Hash-based data structures provide average O(1) lookup, insertion, and deletion. They are the single most useful tool in coding interviews for reducing brute-force O(n²) solutions to O(n). If you're stuck on any problem, ask yourself: "Can a HashMap help here?"

## Core Concepts

### How Hashing Works

1. A **hash function** converts a key to an integer (hash code).
2. The hash code is mapped to a **bucket index** via `(n - 1) & hash` where `n` is the power-of-2 table capacity, after an internal `hash ^ (hash >>> 16)` spread. NOT modulo — Java HashMap uses a power-of-2 capacity + bitmask.
3. **Collisions** (multiple keys → same bucket) are resolved via chaining (linked list / tree) or open addressing.
4. When the **load factor** exceeds a threshold (default 0.75 in Java), the table **rehashes** when `size > capacity * loadFactor` (threshold); table capacity doubles on resize.

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

// Immutable factories (Java 9+) — throw on null, reject duplicate keys
Map<String, Integer> m = Map.of("a", 1, "b", 2);         // up to 10 pairs
Map<String, Integer> big = Map.ofEntries(
    Map.entry("a", 1), Map.entry("b", 2));               // no limit
Set<Integer> s = Set.of(1, 2, 3);
Map<String, Integer> copy = Map.copyOf(m);               // defensive immutable copy

// SequencedMap / SequencedSet (Java 21, JEP 431)
// LinkedHashMap implements SequencedMap; LinkedHashSet implements SequencedSet
SequencedMap<String, Integer> sm = new LinkedHashMap<>();
sm.putFirst("a", 1);                     // insert at head
sm.putLast("z", 26);                     // insert at tail
sm.firstEntry(); sm.lastEntry();         // peek
sm.pollFirstEntry(); sm.pollLastEntry(); // remove
sm.reversed();                           // reverse-ordered view (no copy)
SequencedSet<Integer> ss = new LinkedHashSet<>();
ss.addFirst(0);                          // if already present, moves to head
```

### Time Complexity

| Operation     | HashMap  | TreeMap    | LinkedHashMap |
|---------------|----------|------------|---------------|
| put           | O(1)*    | O(log n)   | O(1)*         |
| get           | O(1)*    | O(log n)   | O(1)*         |
| remove        | O(1)*    | O(log n)   | O(1)*         |
| containsKey   | O(1)*    | O(log n)   | O(1)*         |
| Ordered       | No       | Yes (natural) | Yes (insertion) |

*Average case. Worst case is O(n) with bad hash functions (O(log n) since Java 8 with tree bins). Note: O(log n) worst case in tree bins only applies when keys are `Comparable`; non-Comparable keys fall back to O(n) list traversal even in tree bins.

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

public int strStr(String hay, String needle) {
    int n = hay.length(), k = needle.length();
    if (k == 0) return 0;
    if (k > n) return -1;
    long pow = 1;                                // BASE^(k-1) mod MOD
    for (int i = 0; i < k - 1; i++) pow = pow * BASE % MOD;
    long hNeedle = 0, hWin = 0;
    for (int i = 0; i < k; i++) {
        hNeedle = (hNeedle * BASE + needle.charAt(i)) % MOD;
        hWin    = (hWin    * BASE + hay.charAt(i))    % MOD;
    }
    for (int i = 0; i <= n - k; i++) {
        if (hWin == hNeedle && hay.regionMatches(i, needle, 0, k)) return i;
        if (i < n - k) {
            hWin = (hWin - hay.charAt(i) * pow % MOD + MOD * MOD) % MOD;
            hWin = (hWin * BASE + hay.charAt(i + k)) % MOD;
        }
    }
    return -1;
}
// Collision-resistant: use double hashing (two distinct BASE/MOD pairs).
```

### 7. LRU Cache via LinkedHashMap (access-order)

```java
// Third arg accessOrder=true reorders on get(); removeEldestEntry evicts.
class LRU<K, V> extends LinkedHashMap<K, V> {
    private final int cap;
    LRU(int cap) { super(cap, 0.75f, true); this.cap = cap; }
    @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) {
        return size() > cap;
    }
}
```

### 8. Guava Multimap / Multiset / BiMap (when available)

```java
// Multimap — Map<K, List<V>> without the boilerplate
ListMultimap<String, Integer> mm = ArrayListMultimap.create();
mm.put("a", 1); mm.put("a", 2);          // no computeIfAbsent needed
mm.get("a");                             // [1, 2]; empty list if absent

// Multiset — frequency counter with O(1) count()
Multiset<String> ms = HashMultiset.create();
ms.add("x"); ms.add("x"); ms.count("x"); // 2
// Top K by frequency: Multisets.copyHighestCountFirst(ms)

// BiMap — invertible; values must also be unique
BiMap<String, Integer> bi = HashBiMap.create();
bi.put("a", 1);
bi.inverse().get(1);                     // "a"
```

Eclipse Collections `ObjectIntHashMap<K>` avoids `Integer` boxing for counters (hot loops).

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
- **Java 21 sequenced API:** Prefer `getFirst()/getLast()/reversed()` over `iterator().next()` or manual reverse loops. Works on `List`, `Deque`, `LinkedHashSet`, `LinkedHashMap`, `SortedSet`, `SortedMap`.
- **Hash flooding (Java 8+):** Buckets become red-black trees once chain length ≥ 8 and capacity ≥ 64 — worst case is O(log n), not O(n), provided keys are `Comparable`.
- **Initial capacity:** `new HashMap<>(expectedSize)` only sizes the table; for guaranteed no-resize, pass `expectedSize / 0.75 + 1` (or use `HashMap.newHashMap(n)` in Java 19+).
- **System design adjacent:** Bloom filter (probabilistic membership, no false negatives), Count-Min Sketch (approximate frequency), Consistent Hashing (sharding with minimal remap), HyperLogLog (cardinality). Mention by name when discussing scale.
