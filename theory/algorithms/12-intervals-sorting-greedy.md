# Intervals, Sorting, and Greedy

## Overview

Interval problems and greedy algorithms appear frequently in interviews and often go hand-in-hand. The key insight is almost always: **sort first, then scan linearly** with a greedy decision at each step.

## Interval Problems

### Core Template: Sort by Start Time, Then Merge/Compare

```java
// Merge Intervals
public int[][] merge(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> a[0] - b[0]); // sort by start
    List<int[]> merged = new ArrayList<>();
    merged.add(intervals[0]);

    for (int i = 1; i < intervals.length; i++) {
        int[] last = merged.get(merged.size() - 1);
        if (intervals[i][0] <= last[1]) {
            last[1] = Math.max(last[1], intervals[i][1]); // merge
        } else {
            merged.add(intervals[i]);
        }
    }
    return merged.toArray(new int[0][]);
}
```

### Insert Interval

```java
public int[][] insert(int[][] intervals, int[] newInterval) {
    List<int[]> result = new ArrayList<>();
    int i = 0;
    // Add all intervals before newInterval
    while (i < intervals.length && intervals[i][1] < newInterval[0])
        result.add(intervals[i++]);
    // Merge overlapping intervals
    while (i < intervals.length && intervals[i][0] <= newInterval[1]) {
        newInterval[0] = Math.min(newInterval[0], intervals[i][0]);
        newInterval[1] = Math.max(newInterval[1], intervals[i][1]);
        i++;
    }
    result.add(newInterval);
    // Add remaining
    while (i < intervals.length) result.add(intervals[i++]);
    return result.toArray(new int[0][]);
}
```

### Non-Overlapping Intervals (Max intervals to keep)

```java
// Sort by END time — greedy: always pick the interval that ends earliest
public int eraseOverlapIntervals(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> a[1] - b[1]); // sort by end!
    int count = 0, prevEnd = Integer.MIN_VALUE;
    for (int[] interval : intervals) {
        if (interval[0] >= prevEnd) {
            prevEnd = interval[1]; // no overlap, keep it
        } else {
            count++; // overlap, remove this one
        }
    }
    return count;
}
```

### Meeting Rooms II (Minimum Platforms pattern)

```java
public int minMeetingRooms(int[][] intervals) {
    int[] starts = new int[intervals.length];
    int[] ends = new int[intervals.length];
    for (int i = 0; i < intervals.length; i++) {
        starts[i] = intervals[i][0];
        ends[i] = intervals[i][1];
    }
    Arrays.sort(starts);
    Arrays.sort(ends);

    int rooms = 0, endPtr = 0;
    for (int start : starts) {
        if (start < ends[endPtr]) rooms++;
        else endPtr++;
    }
    return rooms;
}
```

Alternative: **min-heap of end times** — push each start; if heap top ≤ current start, poll. Heap size = rooms. Same O(n log n).

### Sweep Line / Events (max concurrent intervals, car pooling)

Convert each interval to events: `(start, +1)` and `(end, -1)`. Sort by time (ties: `-1` before `+1` when touching ends don't overlap). Running sum = current overlap; max = answer.

```java
// Car Pooling: given trips [[passengers, from, to], ...], capacity
public boolean carPooling(int[][] trips, int capacity) {
    int[] diff = new int[1001]; // bucket diff array when range is small
    for (int[] t : trips) { diff[t[1]] += t[0]; diff[t[2]] -= t[0]; }
    int load = 0;
    for (int d : diff) { load += d; if (load > capacity) return false; }
    return true;
}
```

### The Skyline Problem (sweep line + max-heap)

Events: for each building `[L, R, H]` emit `(L, -H)` (start, negative for ordering) and `(R, H)` (end). Sort events; maintain a multiset (or `TreeMap<Integer,Integer>` of counts) of active heights. Whenever the current max height changes, emit `(x, newMax)`. O(n log n).

### Interval Tree (brief)

Augmented BST keyed on interval start; each node stores max end in its subtree. Supports "find any overlap" in O(log n) and "find all k overlaps" in O(k log n). Overkill for static problems (use sort + sweep); useful for dynamic insert/delete + overlap queries.

## Sorting Algorithms to Know

| Algorithm      | Best     | Average  | Worst    | Space   | Stable | In-place |
|----------------|----------|----------|----------|---------|--------|----------|
| Quick Sort     | O(n lg n)| O(n lg n)| O(n²)    | O(lg n) | No     | Yes      |
| Merge Sort     | O(n lg n)| O(n lg n)| O(n lg n)| O(n)    | Yes    | No       |
| Heap Sort      | O(n lg n)| O(n lg n)| O(n lg n)| O(1)    | No     | Yes      |
| Insertion Sort | O(n)     | O(n²)    | O(n²)    | O(1)    | Yes    | Yes      |
| Counting Sort  | O(n+k)   | O(n+k)   | O(n+k)   | O(k)    | Yes    | No       |
| Radix Sort     | O(d(n+k))| O(d(n+k))| O(d(n+k))| O(n+k)  | Yes    | No       |
| Bucket Sort    | O(n+k)   | O(n+k)   | O(n²)    | O(n+k)  | Yes    | No       |
| TimSort (Java) | O(n)     | O(n lg n)| O(n lg n)| O(n)    | Yes    | No       |

- **Counting sort:** integers in small range `[0, k]`. Not comparison-based; beats the Ω(n log n) bound.
- **Radix sort:** fixed-width keys (ints, strings); `d` = digits. Use LSD with a stable counting sort per digit.
- **Bucket sort:** uniformly distributed floats; scatter into `n` buckets, insertion-sort each.

### Java Sorting

```java
// Primitives: Dual-Pivot Quicksort (DualPivotQuicksort) — NOT stable, in-place
Arrays.sort(intArray);

// Objects / int[][]: TimSort (adaptive merge sort) — stable
Arrays.sort(objectArray);
Arrays.sort(arr, (a, b) -> a[0] - b[0]);              // beware overflow
Arrays.sort(arr, Comparator.comparingInt(a -> a[0])); // safer

// List.sort vs Collections.sort:
//   Collections.sort(list)  -> delegates to list.sort(null)
//   list.sort(cmp)          -> ArrayList overrides to sort backing array in-place
//                              (no copy → faster). Prefer list.sort.

// Parallel sort (fork-join, threshold ~8192): Arrays.parallelSort(arr);

// Partial sort / Kth largest: PriorityQueue of size K → O(n log k)
// Exact Kth: QuickSelect (see below) → O(n) average, O(n²) worst
```

### QuickSelect — Kth Order Statistic in O(n) avg

```java
// Returns the kth smallest (0-indexed) via Lomuto partition
public int quickSelect(int[] a, int k) {
    int lo = 0, hi = a.length - 1;
    Random rnd = new Random();
    while (lo < hi) {
        int p = partition(a, lo, hi, lo + rnd.nextInt(hi - lo + 1));
        if (p == k) return a[p];
        if (p < k) lo = p + 1; else hi = p - 1;
    }
    return a[lo];
}
private int partition(int[] a, int lo, int hi, int pivotIdx) {
    int pivot = a[pivotIdx]; swap(a, pivotIdx, hi);
    int i = lo;
    for (int j = lo; j < hi; j++) if (a[j] < pivot) swap(a, i++, j);
    swap(a, i, hi); return i;
}
```

Randomized pivot → O(n) expected. Median-of-medians gives O(n) worst case but is slower in practice.

### Comparator Cookbook

```java
// Multi-key sort: by length asc, then lexicographically
words.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));

// Reverse
arr.sort(Comparator.comparingInt(Foo::score).reversed());

// Null-safe
list.sort(Comparator.nullsLast(Comparator.naturalOrder()));

// Custom extraction with reverse on one key
people.sort(Comparator.comparing(Person::lastName)
                      .thenComparing(Person::age, Comparator.reverseOrder()));
```

Note: `reversed()` reverses the *whole* chain built so far; apply it to a specific key via `Comparator.reverseOrder()` inside `thenComparing`.

## Greedy Patterns

### Proving Greedy Correctness

Two standard proof techniques — expect to name one in interviews:

1. **Exchange argument.** Assume an optimal solution `OPT` differs from greedy `G` at the first position. Swap in `G`'s choice; show the result is no worse than `OPT`. Repeat → `G` itself is optimal. (Used for: activity selection / non-overlapping intervals, Huffman, scheduling to minimize lateness.)
2. **Greedy stays ahead.** Show that after step `k`, greedy's partial solution is at least as good as any other algorithm's. Induct to `n`. (Used for: jump game, gas station, interval covering.)

If you can't construct one of these, greedy probably doesn't work — fall back to DP.

### Jump Game

```java
public boolean canJump(int[] nums) {
    int maxReach = 0;
    for (int i = 0; i < nums.length; i++) {
        if (i > maxReach) return false;
        maxReach = Math.max(maxReach, i + nums[i]);
    }
    return true;
}
```

### Task Scheduler

```java
public int leastInterval(char[] tasks, int n) {
    int[] freq = new int[26];
    for (char t : tasks) freq[t - 'A']++;
    int maxFreq = Arrays.stream(freq).max().getAsInt();
    int maxCount = (int) Arrays.stream(freq).filter(f -> f == maxFreq).count();
    return Math.max(tasks.length, (maxFreq - 1) * (n + 1) + maxCount);
}
```

## Common Interview Problems

**Easy:** Meeting Rooms, Merge Sorted Array.

**Medium:** Merge Intervals, Insert Interval, Non-Overlapping Intervals, Meeting Rooms II, Interval List Intersections, Sort Colors, Minimum Number of Arrows to Burst Balloons, Jump Game, Task Scheduler, Gas Station, Partition Labels.

**Medium (add):** Car Pooling, Minimum Platforms, Kth Largest Element in an Array (QuickSelect).

**Hard:** Employee Free Time, The Skyline Problem, Minimum Interval to Include Each Query, Data Stream as Disjoint Intervals.

## Tips and Pitfalls

- **Sort by start or end?** Merge/insert → sort by start. Scheduling/selection (non-overlapping, arrows) → sort by end.
- **Open vs closed intervals.** `[1,2]` and `[2,3]`: touching ≠ overlapping if endpoints are exclusive. Clarify with interviewer; decide whether `<` or `<=` in the merge check.
- **Comparator overflow:** `(a, b) -> a[0] - b[0]` overflows for extreme values. Use `Integer.compare(a[0], b[0])` or `Comparator.comparingInt`.
- **Line sweep:** convert intervals to events `(t, +1)` / `(t, -1)`, sort, running sum = concurrent count. When timestamps are small integers, use a **difference array** for O(n + range).
- **Prefer `list.sort(cmp)` over `Collections.sort(list, cmp)`** — `ArrayList.sort` avoids the `toArray` + copy-back round-trip.
- **Stable sort matters** when you pre-sort by a secondary key and then re-sort by the primary: stability preserves the prior order. Primitives sort is **not** stable — box to `Integer[]` if you need that.
- **Greedy needs proof.** Briefly name the technique (exchange argument or staying ahead). If neither fits, fall back to DP.
