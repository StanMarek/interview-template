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

// Custom comparator — sort by second element
PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);

// Common operations
pq.offer(element);      // insert — O(log n)
pq.poll();              // remove and return min/max — O(log n)
pq.peek();              // view min/max — O(1)
pq.size();              // current size — O(1)
pq.isEmpty();
pq.remove(element);     // remove specific element — O(n)
pq.contains(element);   // check existence — O(n)

// Initialize from collection — O(n) heapify
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
        (a, b) -> freq.get(a) - freq.get(b)
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
        (a, b) -> a.val - b.val
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
}
// Each addNum: O(log n), findMedian: O(1)
```

### 4. Scheduling / Interval Problems

```java
// Meeting Rooms II — minimum meeting rooms required
public int minMeetingRooms(int[][] intervals) {
    Arrays.sort(intervals, (a, b) -> a[0] - b[0]);
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

### 5. K Closest Points / K-Way Merge

```java
// K Closest Points to Origin
public int[][] kClosest(int[][] points, int k) {
    // Max-heap by distance — keep K closest
    PriorityQueue<int[]> maxHeap = new PriorityQueue<>(
        (a, b) -> (b[0]*b[0] + b[1]*b[1]) - (a[0]*a[0] + a[1]*a[1])
    );

    for (int[] p : points) {
        maxHeap.offer(p);
        if (maxHeap.size() > k) maxHeap.poll();
    }
    return maxHeap.toArray(new int[k][]);
}
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
- Find Median from Data Stream, Sliding Window Median
- IPO, Trapping Rain Water II, Smallest Range Covering Elements from K Lists

## Tips and Pitfalls

- **PriorityQueue is NOT sorted.** Iterating or converting to array does NOT give sorted order. Only `poll()` gives elements in order.
- **Comparator subtraction overflow:** `(a, b) -> a - b` can overflow for large values. Use `Integer.compare(a, b)` for safety.
- **Min-heap for top K largest, max-heap for top K smallest.** This is counterintuitive — think of it as "the heap guards the boundary."
- **Cannot efficiently search or update.** Use lazy deletion or a `TreeMap` if you need both ordered access and efficient removal by key.
- **heapify is O(n), not O(n log n).** Building a heap from n elements is linear. Inserting one at a time is O(n log n).
- **Custom objects:** Implement `Comparable` or pass a `Comparator`. Without ordering, `PriorityQueue` throws `ClassCastException`.
- **Thread-safe alternative:** Use `PriorityBlockingQueue` if needed in concurrent code (rare in interviews).
