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

### Meeting Rooms II

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

## Sorting Algorithms to Know

| Algorithm      | Best    | Average | Worst   | Space  | Stable |
|----------------|---------|---------|---------|--------|--------|
| Quick Sort     | O(n lg n)| O(n lg n)| O(n²) | O(lg n)| No     |
| Merge Sort     | O(n lg n)| O(n lg n)| O(n lg n)| O(n)| Yes    |
| Heap Sort      | O(n lg n)| O(n lg n)| O(n lg n)| O(1)| No     |
| Counting Sort  | O(n+k)  | O(n+k)  | O(n+k)  | O(k) | Yes    |
| Radix Sort     | O(nk)   | O(nk)   | O(nk)   | O(n+k)| Yes    |
| Tim Sort (Java)| O(n)    | O(n lg n)| O(n lg n)| O(n)| Yes    |

### Java Sorting

```java
// Primitives: Dual-Pivot Quicksort — NOT stable
Arrays.sort(intArray);

// Objects: TimSort — stable
Arrays.sort(objectArray);
Arrays.sort(arr, (a, b) -> a[0] - b[0]); // custom comparator
Arrays.sort(arr, Comparator.comparingInt(a -> a[0])); // safer, no overflow

// Partial sort: use PriorityQueue for top-K (O(n log k))
```

## Greedy Patterns

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

**Hard:** Employee Free Time, The Skyline Problem, Minimum Interval to Include Each Query.

## Tips and Pitfalls

- **Sort by start or end?** Merge/insert → sort by start. Scheduling/selection → sort by end.
- **Comparator overflow:** `(a, b) -> a[0] - b[0]` overflows for extreme values. Use `Integer.compare(a[0], b[0])`.
- **Line sweep technique:** Convert intervals to events (start +1, end -1), sort, and sweep. Great for "max overlap" problems.
- **Greedy needs proof.** In interviews, briefly explain *why* the greedy choice works (exchange argument or staying ahead).
- **Greedy vs DP:** If greedy doesn't work (can't prove optimality), fall back to DP.
