# Stacks and Queues

## Overview

Stacks (LIFO) and Queues (FIFO) are foundational data structures that appear in a huge range of interview problems: expression parsing, BFS/DFS, monotonic patterns, and system design. Understanding when to reach for each one is critical.

## Core Concepts

### Stack (LIFO — Last In, First Out)

```java
// Use Deque interface, ArrayDeque implementation (preferred over Stack class)
Deque<Integer> stack = new ArrayDeque<>();
stack.push(1);          // push to top
stack.pop();            // remove and return top — throws if empty
stack.peek();           // view top — returns null if empty
stack.isEmpty();
stack.size();

// Legacy Stack class — avoid in new code
Stack<Integer> legacyStack = new Stack<>(); // synchronized, slower
```

> **Why ArrayDeque over Stack?** Java's `Stack` class extends `Vector`, making all operations synchronized (unnecessary overhead). `ArrayDeque` is faster, not synchronized, and is the recommended implementation.

### Queue (FIFO — First In, First Out)

```java
// Standard Queue
Queue<Integer> queue = new LinkedList<>();
queue.offer(1);         // enqueue — returns false if full (vs add which throws)
queue.poll();           // dequeue — returns null if empty (vs remove which throws)
queue.peek();           // view front — returns null if empty

// ArrayDeque as Queue (faster than LinkedList for most cases)
Queue<Integer> queue = new ArrayDeque<>();

// Deque — Double-ended queue (can act as both stack and queue)
Deque<Integer> deque = new ArrayDeque<>();
deque.offerFirst(1);    deque.offerLast(2);
deque.pollFirst();      deque.pollLast();
deque.peekFirst();      deque.peekLast();
```

### Priority Queue (Min-Heap by default)

```java
// Min-heap
PriorityQueue<Integer> minHeap = new PriorityQueue<>();

// Max-heap
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Comparator.reverseOrder());

// Custom comparator
PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);

minHeap.offer(3);       // O(log n)
minHeap.poll();         // O(log n) — removes min
minHeap.peek();         // O(1) — view min
```

### Time Complexity

| Operation  | Stack (ArrayDeque) | Queue (ArrayDeque) | PriorityQueue |
|------------|--------------------|--------------------|---------------|
| Push/Offer | O(1)*              | O(1)*              | O(log n)      |
| Pop/Poll   | O(1)               | O(1)               | O(log n)      |
| Peek       | O(1)               | O(1)               | O(1)          |
| Search     | O(n)               | O(n)               | O(n)          |

*Amortized.

## Essential Stack Patterns

### 1. Monotonic Stack

Maintains elements in sorted order. Used to find the **next greater/smaller element** efficiently.

```java
// Next Greater Element — for each element, find the first larger element to its right
public int[] nextGreaterElement(int[] nums) {
    int n = nums.length;
    int[] result = new int[n];
    Arrays.fill(result, -1);
    Deque<Integer> stack = new ArrayDeque<>(); // stores indices

    for (int i = 0; i < n; i++) {
        while (!stack.isEmpty() && nums[stack.peek()] < nums[i]) {
            result[stack.pop()] = nums[i];
        }
        stack.push(i);
    }
    return result;
}
```

**When to use:** "next greater element", "previous smaller element", histogram problems, stock span, daily temperatures.

### 2. Valid Parentheses / Bracket Matching

```java
public boolean isValid(String s) {
    Deque<Character> stack = new ArrayDeque<>();
    Map<Character, Character> map = Map.of(')', '(', ']', '[', '}', '{');

    for (char c : s.toCharArray()) {
        if (map.containsValue(c)) {
            stack.push(c);
        } else if (map.containsKey(c)) {
            if (stack.isEmpty() || stack.pop() != map.get(c)) return false;
        }
    }
    return stack.isEmpty();
}
```

### 3. Expression Evaluation

```java
// Basic Calculator — handles +, -, (, )
public int calculate(String s) {
    Deque<Integer> stack = new ArrayDeque<>();
    int result = 0, num = 0, sign = 1;

    for (char c : s.toCharArray()) {
        if (Character.isDigit(c)) {
            num = num * 10 + (c - '0');
        } else if (c == '+' || c == '-') {
            result += sign * num;
            num = 0;
            sign = (c == '+') ? 1 : -1;
        } else if (c == '(') {
            stack.push(result);
            stack.push(sign);
            result = 0;
            sign = 1;
        } else if (c == ')') {
            result += sign * num;
            num = 0;
            result *= stack.pop(); // sign before (
            result += stack.pop(); // result before (
        }
    }
    return result + sign * num;
}
```

### 4. Min Stack

```java
class MinStack {
    Deque<int[]> stack = new ArrayDeque<>(); // [value, currentMin]

    public void push(int val) {
        int min = stack.isEmpty() ? val : Math.min(val, stack.peek()[1]);
        stack.push(new int[]{val, min});
    }
    public void pop() { stack.pop(); }
    public int top() { return stack.peek()[0]; }
    public int getMin() { return stack.peek()[1]; }
}
```

### 5. Largest Rectangle in Histogram

A classic monotonic stack problem.

```java
public int largestRectangleArea(int[] heights) {
    Deque<Integer> stack = new ArrayDeque<>();
    int maxArea = 0;

    for (int i = 0; i <= heights.length; i++) {
        int h = (i == heights.length) ? 0 : heights[i];
        while (!stack.isEmpty() && heights[stack.peek()] > h) {
            int height = heights[stack.pop()];
            int width = stack.isEmpty() ? i : i - stack.peek() - 1;
            maxArea = Math.max(maxArea, height * width);
        }
        stack.push(i);
    }
    return maxArea;
}
```

## Essential Queue Patterns

### 1. BFS with Queue

```java
// Level-order traversal of a binary tree
public List<List<Integer>> levelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new LinkedList<>();
    queue.offer(root);

    while (!queue.isEmpty()) {
        int size = queue.size();
        List<Integer> level = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TreeNode node = queue.poll();
            level.add(node.val);
            if (node.left != null) queue.offer(node.left);
            if (node.right != null) queue.offer(node.right);
        }
        result.add(level);
    }
    return result;
}
```

### 2. Sliding Window Maximum (Monotonic Deque)

```java
public int[] maxSlidingWindow(int[] nums, int k) {
    Deque<Integer> deque = new ArrayDeque<>(); // stores indices, front = max
    int[] result = new int[nums.length - k + 1];

    for (int i = 0; i < nums.length; i++) {
        // Remove elements outside window
        while (!deque.isEmpty() && deque.peekFirst() < i - k + 1) {
            deque.pollFirst();
        }
        // Remove smaller elements from back (they'll never be the max)
        while (!deque.isEmpty() && nums[deque.peekLast()] < nums[i]) {
            deque.pollLast();
        }
        deque.offerLast(i);
        if (i >= k - 1) {
            result[i - k + 1] = nums[deque.peekFirst()];
        }
    }
    return result;
}
```

### 3. Implement Queue using Stacks

```java
class MyQueue {
    Deque<Integer> in = new ArrayDeque<>();
    Deque<Integer> out = new ArrayDeque<>();

    public void push(int x) { in.push(x); }

    public int pop() {
        if (out.isEmpty()) {
            while (!in.isEmpty()) out.push(in.pop());
        }
        return out.pop();
    }

    public int peek() {
        if (out.isEmpty()) {
            while (!in.isEmpty()) out.push(in.pop());
        }
        return out.peek();
    }

    public boolean empty() { return in.isEmpty() && out.isEmpty(); }
}
// Amortized O(1) per operation
```

## Common Interview Problems

### Easy
- Valid Parentheses, Min Stack
- Implement Queue using Stacks, Implement Stack using Queues
- Moving Average from Data Stream

### Medium
- Daily Temperatures, Next Greater Element I/II
- Evaluate Reverse Polish Notation, Basic Calculator II
- Decode String, Asteroid Collision
- Sliding Window Maximum, Design Circular Queue
- Online Stock Span, Remove K Digits

### Hard
- Basic Calculator, Largest Rectangle in Histogram
- Maximal Rectangle, Trapping Rain Water (stack approach)
- Longest Valid Parentheses

## Tips and Pitfalls

- **Use `ArrayDeque`** over `Stack` and `LinkedList` — it's faster for both stack and queue operations.
- **`poll()` vs `pop()`/`remove()`:** `poll()` returns null when empty; `pop()`/`remove()` throw exceptions. In interviews, `poll()` with a null check is safer.
- **Monotonic stack direction:** Increasing stack finds *next smaller*, decreasing stack finds *next greater*. Draw a few examples to confirm.
- **BFS always uses a queue.** DFS uses a stack (or recursion, which is an implicit stack).
- **Capture `queue.size()` before the inner loop** in BFS level-order — the queue grows during the loop.
- **Stack for DFS:** Any recursive DFS can be converted to iterative using an explicit stack. Interviewers sometimes ask for this.
- **Two stacks = queue:** Know the amortized O(1) proof — each element is pushed and popped at most twice total.
