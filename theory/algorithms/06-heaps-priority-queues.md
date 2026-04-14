# Heaps and Priority Queues

## Overview

A heap is a complete binary tree where every parent satisfies the heap property (min-heap: parent ≤ children; max-heap: parent ≥ children). Heaps are the go-to structure when you need to efficiently track the min/max of a dynamic dataset, solve "top K" problems, or merge sorted sequences.

## Core Concepts

### Heap Properties

- **Structure:** Complete binary tree stored in an array.
- **Array indexing (0-based):** Parent of `i` = `(i-1)/2`. Left child = `2i+1`. Right child = `2i+2`.
- **Min-heap:** Root is the minimum. Java's `PriorityQueue` is a min-heap by default.
- **Max-heap:** Root is the maximum. Use `Comparator.reverseOrder()` or negate values.

### Java's PriorityQueue

```java
// Min-heap (default)
PriorityQueue<Integer> minHeap = new PriorityQueue<>();

// Max-heap
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder());

// Custom comparator — sort by second element (use comparingInt to avoid overflow)
PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[1]));

// Common operations
pq.offer(element);      // insert — O(log n)
pq.poll();              // remove and return min/max — O(log n)
pq.peek();              // view min/max — O(1)
pq.size();              // current size — O(1)
pq.isEmpty();
pq.remove(element);     // remove specific element — O(n)
pq.contains(element);   // check existence — O(n)

// Initialize from collection — O(n) heapify (bulk build, NOT n × O(log n))
PriorityQueue<Integer> pq = new PriorityQueue<>(Arrays.asList(3, 1, 4, 1, 5));
```

### Time Complexity

| Operation   | Time     | Notes                            |
|-------------|----------|----------------------------------|
| offer/add   | O(log n) | Sift up                          |
| poll/remove | O(log n) | Sift down                        |
| peek        | O(1)     | View root                        |
| remove(obj) | O(n)     | Linear search + O(log n) sift   |
| heapify     | O(n)     | Build heap from array            |
| contains    | O(n)     | No efficient search              |

**Space:** O(n)

## Essential Patterns

### 1. Top K Elements

Use a min-heap of size K for "top K largest" (counterintuitive but correct).

```java
// Kth Largest Element in an Array
public int findKthLargest(int[] nums, int k) {
    PriorityQueue<Integer> minHeap = new PriorityQueue<>();
    for (int num : nums) {
        minHeap.offer(num);
        if (minHeap.size() > k) {
            minHeap.poll(); // remove the smallest — keeps K largest
        }
    }
    return minHeap.peek(); // the Kth largest
}
// Time: O(n log k), Space: O(k)
```

```java
// Top K Frequent Elements
public int[] topKFrequent(int[] nums, int k) {
    Map<Integer, Integer> freq = new HashMap<>();
    for (int n : nums) freq.merge(n, 1, Integer::sum);

    PriorityQueue<Integer> minHeap = new PriorityQueue<>(
        Comparator.comparingInt(freq::get)
    );

    for (int key : freq.keySet()) {
        minHeap.offer(key);
        if (minHeap.size() > k) minHeap.poll();
    }

    int[] result = new int[k];
    for (int i = k - 1; i >= 0; i--) result[i] = minHeap.poll();
    return result;
}
```

**Rule of thumb:**
- Top K *largest* → min-heap of size K
- Top K *smallest* → max-heap of size K

### 2. Merge K Sorted Lists/Streams

```java
public ListNode mergeKLists(ListNode[] lists) {
    PriorityQueue<ListNode> pq = new PriorityQueue<>(
        Comparator.comparingInt(n -> n.val)
    );

    for (ListNode head : lists) {
        if (head != null) pq.offer(head);
    }

    ListNode dummy = new ListNode(0), curr = dummy;
    while (!pq.isEmpty()) {
        ListNode node = pq.poll();
        curr.next = node;
        curr = curr.next;
        if (node.next != null) pq.offer(node.next);
    }
    return dummy.next;
}
// Time: O(N log K) where N = total elements, K = number of lists
```

### 3. Two Heaps (Median Finding)

Use a max-heap for the lower half and a min-heap for the upper half.

```java
class MedianFinder {
    PriorityQueue<Integer> lo = new PriorityQueue<>(Comparator.reverseOrder()); // max-heap
    PriorityQueue<Integer> hi = new PriorityQueue<>(); // min-heap

    public void addNum(int num) {
        lo.offer(num);
        hi.offer(lo.poll()); // balance: push max of lower half to upper
        if (hi.size() > lo.size()) {
            lo.offer(hi.poll()); // keep lo.size >= hi.size
        }
    }

    public double findMedian() {
        if (lo.size() > hi.size()) return lo.peek();
        return (lo.peek() + hi.peek()) / 2.0;
    }
    // For sliding window: combine with a "delete" HashMap<Integer,Integer>
    // and lazily purge stale roots on peek/poll. O(n log k).
}
// Each addNum: O(log n), findMedian: O(1)
```

### 4. Scheduling / Interval Problems

```java
// Meeting Rooms II — minimum meeting rooms required
public int minMeetingRooms(int[][] intervals) {
    Arrays.sort(intervals, Comparator.comparingInt(a -> a[0]));
    PriorityQueue<Integer> endTimes = new PriorityQueue<>(); // min-heap of end times

    for (int[] interval : intervals) {
        if (!endTimes.isEmpty() && endTimes.peek() <= interval[0]) {
            endTimes.poll(); // reuse a room
        }
        endTimes.offer(interval[1]);
    }
    return endTimes.size();
}
```

```java
// Task Scheduler — cooldown n between identical tasks (greedy + max-heap)
public int leastInterval(char[] tasks, int n) {
    int[] freq = new int[26];
    for (char c : tasks) freq[c - 'A']++;
    PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder());
    for (int f : freq) if (f > 0) maxHeap.offer(f);

    int time = 0;
    Deque<int[]> cooldown = new ArrayDeque<>(); // {remainingCount, availableAt}
    while (!maxHeap.isEmpty() || !cooldown.isEmpty()) {
        time++;
        if (!maxHeap.isEmpty()) {
            int left = maxHeap.poll() - 1;
            if (left > 0) cooldown.offer(new int[]{left, time + n});
        }
        if (!cooldown.isEmpty() && cooldown.peek()[1] == time)
            maxHeap.offer(cooldown.poll()[0]);
    }
    return time;
}
```

```java
// IPO — pick up to k projects to maximize capital (two heaps: min-cost + max-profit)
public int findMaximizedCapital(int k, int w, int[] profits, int[] capital) {
    int n = profits.length;
    PriorityQueue<int[]> minCost = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
    PriorityQueue<Integer> maxProfit = new PriorityQueue<>(Comparator.reverseOrder());
    for (int i = 0; i < n; i++) minCost.offer(new int[]{capital[i], profits[i]});

    while (k-- > 0) {
        while (!minCost.isEmpty() && minCost.peek()[0] <= w)
            maxProfit.offer(minCost.poll()[1]); // unlock affordable projects
        if (maxProfit.isEmpty()) break;
        w += maxProfit.poll();
    }
    return w;
}
```

### 5. K Closest Points / K-Way Merge

```java
// K Closest Points to Origin
public int[][] kClosest(int[][] points, int k) {
    // Max-heap by distance — keep K closest
    PriorityQueue<int[]> maxHeap = new PriorityQueue<>(
        Comparator.comparingInt((int[] a) -> -(a[0]*a[0] + a[1]*a[1]))
    );

    for (int[] p : points) {
        maxHeap.offer(p);
        if (maxHeap.size() > k) maxHeap.poll();
    }
    return maxHeap.toArray(new int[k][]);
}
```

```java
// Kth Smallest Element in a Sorted Matrix — row-wise k-way merge
public int kthSmallest(int[][] matrix, int k) {
    int n = matrix.length;
    // {value, row, col} — min-heap seeded with first column
    PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a[0]));
    for (int r = 0; r < Math.min(n, k); r++) pq.offer(new int[]{matrix[r][0], r, 0});

    for (int i = 0; i < k - 1; i++) {
        int[] cur = pq.poll();
        if (cur[2] + 1 < n)
            pq.offer(new int[]{matrix[cur[1]][cur[2] + 1], cur[1], cur[2] + 1});
    }
    return pq.peek()[0];
}
// Time: O(min(n,k) + k log min(n,k))
```

### 6. Lazy Deletion Pattern

When `remove()` is O(n) but you need efficient removal, mark items as "deleted" and skip them on poll.

```java
Set<Integer> deleted = new HashSet<>();
PriorityQueue<Integer> pq = new PriorityQueue<>();

// "Delete" an element
deleted.add(element);

// Poll — skip deleted elements
while (!pq.isEmpty() && deleted.contains(pq.peek())) {
    deleted.remove(pq.poll());
}
int next = pq.poll();
```

### 7. Heap Variants (Know They Exist)

- **Indexed Priority Queue:** Pairs a heap with a `Map<Key, Index>` to support O(log n) `decreaseKey` / `update`. Not in the JDK — roll your own or simulate by re-inserting and using lazy deletion. Useful in Dijkstra/Prim when edges relax.
- **Fibonacci heap:** O(1) amortized `insert` and `decreaseKey`, O(log n) `extractMin`. Gives Dijkstra its optimal O(E + V log V) bound. Rarely implemented in practice — binary heap wins on constants. Mention it only if asked about theoretical optima.
- **Pairing / d-ary heaps:** Simpler alternatives with good practical performance; d-ary (e.g., 4-ary) reduces tree height vs binary.

## Common Interview Problems

### Easy
- Last Stone Weight, Kth Largest Element in a Stream
- Relative Ranks

### Medium
- Kth Largest Element in an Array, Top K Frequent Elements
- K Closest Points to Origin, Sort Characters by Frequency
- Task Scheduler, Reorganize String, Find Median from Data Stream
- Meeting Rooms II, Merge K Sorted Lists (also Hard)
- Furthest Building You Can Reach

### Hard
- Find Median from Data Stream, Sliding Window Median (two heaps + lazy deletion map)
- IPO, Trapping Rain Water II, Smallest Range Covering Elements from K Lists
- Kth Smallest Element in a Sorted Matrix, Find K Pairs with Smallest Sums

## Tips and Pitfalls

- **PriorityQueue is NOT sorted.** Iterating or converting to array does NOT give sorted order. Only `poll()` gives elements in order.
- **Comparator subtraction overflow:** `(a, b) -> a - b` can overflow for large values. Use `Integer.compare(a, b)` for safety.
- **Min-heap for top K largest, max-heap for top K smallest.** This is counterintuitive — think of it as "the heap guards the boundary."
- **Cannot efficiently search or update.** Use lazy deletion or a `TreeMap` if you need both ordered access and efficient removal by key.
- **heapify is O(n), not O(n log n).** Building a heap from n elements is linear (bottom-up sift-down). Inserting one at a time is O(n log n). Prefer `new PriorityQueue<>(collection)` over a loop of `offer`.
- **Custom objects:** Implement `Comparable` or pass a `Comparator`. Without ordering, `PriorityQueue` throws `ClassCastException`.
- **No decrease-key.** Java's `PriorityQueue` has no `decreaseKey`; `remove(obj)` is O(n). For Dijkstra, either re-insert with the new key and skip stale entries on poll, or use an indexed PQ.
- **Modern comparator style.** Prefer `Comparator.comparingInt(x -> x.field)` and `Integer.compare(a, b)` over `(a, b) -> a - b` to avoid overflow. Chain with `.thenComparing(...)` for tie-breakers.
- **API stable through Java 24.** No behavioral changes since Java 8 beyond generic signature refinements; records and lambdas interoperate cleanly with the comparator API.
- **Thread-safe alternative:** Use `PriorityBlockingQueue` if needed in concurrent code (rare in interviews).
