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
// Returns index if found; otherwise -(insertionPoint) - 1
// insertionPoint = index of first element > target, or nums.length
// Recover it: if (idx < 0) int ins = -idx - 1;
// Array MUST be sorted; ties: any match may be returned.
int idx2 = Collections.binarySearch(list, target);          // List<T extends Comparable>
int idx3 = Arrays.binarySearch(nums, fromIdx, toIdx, target); // range overload
```

### TreeSet / TreeMap as Binary Search

For dynamic sorted sets, `NavigableSet`/`NavigableMap` methods are O(log n) and often cleaner than rolling your own:

```java
TreeSet<Integer> set = new TreeSet<>();
set.floor(x);    // greatest element <= x   (null if none)
set.ceiling(x);  // smallest element >= x
set.lower(x);    // greatest element <  x   (strict)
set.higher(x);   // smallest element >  x   (strict)
// TreeMap: floorKey/ceilingKey/floorEntry/ceilingEntry, subMap(from, to)
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

### 6. Canonical "Find Leftmost / Rightmost True"

Any monotone predicate `p` (false...false, true...true) reduces to:

```java
// Leftmost true in [lo, hi)  — returns hi if all false
int leftmostTrue(int lo, int hi) {
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (p(mid)) hi = mid;
        else        lo = mid + 1;
    }
    return lo;
}

// Rightmost true in [lo, hi) — returns lo - 1 if all false.
// Strategy: run the firstFalse search on the complement, then subtract one.
// Termination: lo == hi points one-past the last true index.
int rightmostTrue(int lo, int hi) {
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (p(mid)) lo = mid + 1; // keep expanding while predicate holds
        else        hi = mid;     // first false -> shrink right bound
    }
    return lo - 1; // -1 when no index satisfied the predicate (all false)
}
```

### 7. Median of Two Sorted Arrays — O(log min(m, n))

Partition the shorter array so that `left half size = (m + n + 1) / 2`; adjust so `A[i-1] <= B[j]` and `B[j-1] <= A[i]`.

```java
public double findMedianSortedArrays(int[] A, int[] B) {
    if (A.length > B.length) return findMedianSortedArrays(B, A);
    int m = A.length, n = B.length, half = (m + n + 1) / 2;
    int lo = 0, hi = m;
    while (lo <= hi) {
        int i = lo + (hi - lo) / 2, j = half - i;
        int aL = i == 0 ? Integer.MIN_VALUE : A[i - 1];
        int aR = i == m ? Integer.MAX_VALUE : A[i];
        int bL = j == 0 ? Integer.MIN_VALUE : B[j - 1];
        int bR = j == n ? Integer.MAX_VALUE : B[j];
        if (aL <= bR && bL <= aR) {
            if (((m + n) & 1) == 1) return Math.max(aL, bL);
            return (Math.max(aL, bL) + Math.min(aR, bR)) / 2.0;
        } else if (aL > bR) hi = i - 1;
        else                lo = i + 1;
    }
    return 0.0;
}
```

### 8. Binary Search on Answer — Extra Classics

- **Capacity to Ship Packages in D Days** — search capacity in `[max(weights), sum(weights)]`; feasibility: simulate days greedily.
- **Split Array Largest Sum** — identical to ship-packages; search the largest subarray sum.
- **Aggressive Cows / Magnetic Force** — maximize min-gap; search gap in `[1, max - min]`, greedy-place cows.

### 9. Exponential & Interpolation Search (brief)

- **Exponential search** — for unbounded/infinite streams: double `hi` until `a[hi] >= target`, then binary-search `[hi/2, min(hi, n-1)]`. O(log i) where i = target index.
- **Interpolation search** — for uniformly distributed sorted data: `mid = lo + (target - a[lo]) * (hi - lo) / (a[hi] - a[lo])`. O(log log n) average, O(n) worst. Rare in interviews; mention as an optimization.

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
- **Half-open `[lo, hi)`** with `hi = n` and `lo < hi` is the safest default for boundary searches — `lo` converges to the answer (or `n` if no index satisfies).
- **Rightmost-true needs biased mid** (`lo + (hi - lo) / 2 + 1`) when `lo = mid`, otherwise `lo + 1 == hi` loops forever.
- **Prefer `TreeSet.floor/ceiling/higher/lower`** over hand-rolled binary search on dynamic sorted data.
- **`Arrays.binarySearch` caveat:** if duplicates exist, it returns *some* matching index — not the first. Use `lowerBound` when you need a specific occurrence.
