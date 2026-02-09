# Binary Search

## Overview

Binary search is deceptively simple yet one of the most error-prone algorithms to implement correctly. It applies far beyond sorted arrays — any problem with a monotonic predicate (a condition that flips from false to true at some point) can be solved with binary search.

## Core Templates

### Template 1: Exact Match

```java
public int binarySearch(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2; // avoids overflow
        if (nums[mid] == target) return mid;
        else if (nums[mid] < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}
```

### Template 2: Lower Bound (First True / leftmost insertion point)

Find the **first index where condition is true**. This is the most versatile template.

```java
// Returns the first index where nums[i] >= target
public int lowerBound(int[] nums, int target) {
    int lo = 0, hi = nums.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] >= target) hi = mid;  // condition is true — search left
        else lo = mid + 1;                   // condition is false — search right
    }
    return lo; // first position where condition holds
}
```

### Template 3: Upper Bound

```java
// Returns the first index where nums[i] > target
public int upperBound(int[] nums, int target) {
    int lo = 0, hi = nums.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > target) hi = mid;
        else lo = mid + 1;
    }
    return lo;
}
```

### Java Built-in

```java
int idx = Arrays.binarySearch(nums, target);
// Returns index if found, otherwise -(insertion point) - 1
// For collections:
int idx = Collections.binarySearch(list, target);
```

## Essential Patterns

### 1. Search in Rotated Sorted Array

```java
public int search(int[] nums, int target) {
    int lo = 0, hi = nums.length - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] == target) return mid;

        if (nums[lo] <= nums[mid]) { // left half is sorted
            if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
            else lo = mid + 1;
        } else { // right half is sorted
            if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
            else hi = mid - 1;
        }
    }
    return -1;
}
```

### 2. Binary Search on Answer (Minimizing / Maximizing)

When the problem asks "find the minimum X such that condition(X) is true."

```java
// Koko Eating Bananas — minimum speed to eat all bananas in h hours
public int minEatingSpeed(int[] piles, int h) {
    int lo = 1, hi = Arrays.stream(piles).max().getAsInt();
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (canFinish(piles, mid, h)) hi = mid;
        else lo = mid + 1;
    }
    return lo;
}

private boolean canFinish(int[] piles, int speed, int h) {
    int hours = 0;
    for (int p : piles) hours += (p + speed - 1) / speed; // ceil division
    return hours <= h;
}
```

### 3. Find First and Last Position

```java
public int[] searchRange(int[] nums, int target) {
    int first = lowerBound(nums, target);
    if (first == nums.length || nums[first] != target) return new int[]{-1, -1};
    int last = upperBound(nums, target) - 1;
    return new int[]{first, last};
}
```

### 4. Search in 2D Matrix

```java
// Treat m×n matrix as a sorted 1D array
public boolean searchMatrix(int[][] matrix, int target) {
    int m = matrix.length, n = matrix[0].length;
    int lo = 0, hi = m * n - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        int val = matrix[mid / n][mid % n];
        if (val == target) return true;
        else if (val < target) lo = mid + 1;
        else hi = mid - 1;
    }
    return false;
}
```

### 5. Peak Element

```java
public int findPeakElement(int[] nums) {
    int lo = 0, hi = nums.length - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (nums[mid] > nums[mid + 1]) hi = mid;
        else lo = mid + 1;
    }
    return lo;
}
```

## Common Interview Problems

**Easy:** Binary Search, First Bad Version, Sqrt(x), Search Insert Position.

**Medium:** Search in Rotated Sorted Array, Find First and Last Position, Find Peak Element, Search a 2D Matrix, Koko Eating Bananas, Capacity to Ship Packages, Minimum in Rotated Sorted Array, Single Element in a Sorted Array, Time Based Key-Value Store.

**Hard:** Median of Two Sorted Arrays, Split Array Largest Sum, Find in Mountain Array.

## Tips and Pitfalls

- **Use `lo + (hi - lo) / 2`** instead of `(lo + hi) / 2` to prevent integer overflow.
- **`lo <= hi` vs `lo < hi`:** Use `lo <= hi` when searching for an exact match. Use `lo < hi` when converging to a boundary.
- **Infinite loops:** If `lo = mid` (not `mid + 1`), you can loop forever when `lo + 1 == hi`. Always ensure the search space shrinks.
- **Binary search on answer:** If the problem asks "find the minimum/maximum value such that...", binary search the answer space and check feasibility.
- **Off-by-one:** The most common bug. Test with arrays of size 0, 1, and 2 to catch it.
- **Monotonic condition is key:** Binary search works whenever you have a predicate that's false for some prefix and true for the rest (or vice versa).
