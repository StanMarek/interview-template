# Coding Interview Data Structures — Master Index

## Files Included

| # | File | Key Topics |
|---|------|------------|
| 01 | `01-arrays-and-strings.md` | Two pointers, sliding window, prefix sum, Kadane's, Dutch flag |
| 02 | `02-hashmaps-and-hashsets.md` | Frequency counting, two sum, grouping, prefix sum + map, rolling hash |
| 03 | `03-linked-lists.md` | Dummy head, fast/slow pointers, reversal, merge, cycle detection |
| 04 | `04-stacks-and-queues.md` | Monotonic stack, bracket matching, expression eval, BFS, sliding window max |
| 05 | `05-trees.md` | Traversals (all 4), DFS patterns, BST validation, LCA, serialize/deserialize |
| 06 | `06-heaps-priority-queues.md` | Top K, merge K sorted, two heaps (median), scheduling |
| 07 | `07-graphs.md` | BFS, DFS, topological sort, Dijkstra, Bellman-Ford, Union-Find, grid BFS |
| 08 | `08-tries.md` | Prefix trees, word search II, autocomplete, wildcard search |
| 09 | `09-dynamic-programming.md` | Linear DP, grid DP, knapsack, LCS, LIS, interval DP, state machine DP |
| 10 | `10-binary-search.md` | Templates, rotated array, binary search on answer, 2D matrix search |
| 11 | `11-backtracking.md` | Subsets, permutations, combination sum, N-Queens, grid backtracking |
| 12 | `12-intervals-sorting-greedy.md` | Merge intervals, meeting rooms, sorting algorithms, greedy patterns |
| 13 | `13-bit-manipulation.md` | XOR tricks, bit counting, masks, power-of-two, bitmask DP primer, Gosper's hack |
| 14 | `14-math-and-number-theory.md` | GCD/LCM, modular arithmetic & inverse, fast exponentiation, sieve, nCk mod p, overflow-safe Java |
| 15 | `15-string-algorithms.md` | KMP, Rabin-Karp rolling hash, Z-algorithm, expand-around-center, suffix array + Kasai LCP |

## Quick Problem → Pattern Lookup

| If the problem says... | Think about... |
|------------------------|----------------|
| "Sorted array" | Binary search, two pointers |
| "Top/bottom K" | Heap (PriorityQueue) |
| "Substring/subarray" | Sliding window, prefix sum + HashMap |
| "Tree" | DFS recursion, BFS level order |
| "Graph / connected / reachable" | BFS, DFS, Union-Find |
| "Permutations / combinations / subsets" | Backtracking |
| "Minimum/maximum with constraints" | DP or binary search on answer |
| "Parentheses / nested structure" | Stack |
| "Prefix / autocomplete / dictionary" | Trie |
| "Intervals / scheduling" | Sort + greedy or heap |
| "Shortest path (unweighted)" | BFS |
| "Shortest path (weighted)" | Dijkstra / Bellman-Ford |
| "Detect cycle" | DFS (directed) or Union-Find (undirected) |
| "In-place, O(1) space" | Two pointers, bit manipulation |

## Complexity Cheat Sheet

| Structure | Access | Search | Insert | Delete | Space |
|-----------|--------|--------|--------|--------|-------|
| Array | O(1) | O(n) | O(n) | O(n) | O(n) |
| HashMap | — | O(1)* | O(1)* | O(1)* | O(n) |
| TreeMap | — | O(log n) | O(log n) | O(log n) | O(n) |
| LinkedList | O(n) | O(n) | O(1)† | O(1)† | O(n) |
| Stack/Queue | O(n) | O(n) | O(1)* | O(1) | O(n) |
| Heap | — | O(n) | O(log n) | O(log n) | O(n) |
| BST (balanced) | — | O(log n) | O(log n) | O(log n) | O(n) |
| Trie | — | O(m) | O(m) | O(m) | O(N·M) |

*Amortized. †At head/tail with pointer.

## Java Collections Quick Reference

```java
// Lists
List<Integer> list = new ArrayList<>();    // dynamic array
List<Integer> list = new LinkedList<>();   // doubly linked

// Sets
Set<Integer> set = new HashSet<>();        // O(1) lookup, unordered
Set<Integer> set = new LinkedHashSet<>();  // insertion order
Set<Integer> set = new TreeSet<>();        // sorted, O(log n)

// Maps
Map<K,V> map = new HashMap<>();            // O(1), unordered
Map<K,V> map = new LinkedHashMap<>();      // insertion order
Map<K,V> map = new TreeMap<>();            // sorted keys, O(log n)

// Stack & Queue
Deque<Integer> stack = new ArrayDeque<>(); // use push/pop/peek
Queue<Integer> queue = new ArrayDeque<>(); // use offer/poll/peek
Deque<Integer> deque = new ArrayDeque<>(); // double-ended

// Heap
PriorityQueue<Integer> min = new PriorityQueue<>();
PriorityQueue<Integer> max = new PriorityQueue<>(Comparator.reverseOrder());
```
