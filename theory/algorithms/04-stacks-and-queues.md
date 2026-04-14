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

> **Why ArrayDeque over Stack?** Java's `Stack` class extends `Vector`, making all operations synchronized (unnecessary overhead). `ArrayDeque` is faster, not synchronized, and is the recommended implementation. Caveat: `ArrayDeque` does **not** allow `null` elements (throws NPE) — use a sentinel if you need to represent absence.

### Queue (FIFO — First In, First Out)

```java
// Standard Queue — prefer ArrayDeque over LinkedList (contiguous memory, better cache locality)
Queue<Integer> queue = new ArrayDeque<>();
queue.offer(1);         // enqueue — returns false if bounded queue is full (vs add which throws)
queue.poll();           // dequeue — returns null if empty (vs remove which throws)
queue.peek();           // view front — returns null if empty

// LinkedList also implements Queue — only pick it if you need nulls or List+Queue dual role
Queue<Integer> ll = new LinkedList<>();

// Deque — Double-ended queue (can act as both stack and queue)
Deque<Integer> deque = new ArrayDeque<>();
deque.offerFirst(1);    deque.offerLast(2);
deque.pollFirst();      deque.pollLast();
deque.peekFirst();      deque.peekLast();
```

> **Java 21+ `SequencedCollection` (JEP 431):** `Deque` now extends `SequencedCollection`, so you also get `getFirst()`, `getLast()`, `addFirst()`, `addLast()`, `removeFirst()`, `removeLast()`, and a `reversed()` view. These throw `NoSuchElementException` on empty collections (unlike `peekFirst`/`pollFirst` which return null). Same uniform API applies to `List`, `LinkedHashSet`, `NavigableSet`.

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
            if (stack.isEmpty() || !stack.pop().equals(map.get(c))) return false; // .equals, not !=
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

Classic monotonic (increasing) stack problem. Trick: append a sentinel `0` so the stack fully drains at the end. For each popped bar, it is the *shortest* bar across the rectangle; width spans from the new stack top (exclusive) to the current index (exclusive).

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

### 6. Trapping Rain Water (Stack)

Monotonic decreasing stack of indices. When a taller bar arrives, pop the bottom and compute horizontal water slab between the new top (left wall) and current bar (right wall).

```java
public int trap(int[] height) {
    Deque<Integer> stack = new ArrayDeque<>();
    int water = 0;
    for (int i = 0; i < height.length; i++) {
        while (!stack.isEmpty() && height[i] > height[stack.peek()]) {
            int bottom = stack.pop();
            if (stack.isEmpty()) break;
            int left = stack.peek();
            int w = i - left - 1;
            int h = Math.min(height[left], height[i]) - height[bottom];
            water += w * h;
        }
        stack.push(i);
    }
    return water;
}
```

### 7. Evaluate Reverse Polish Notation (Postfix)

```java
public int evalRPN(String[] tokens) {
    Deque<Integer> stack = new ArrayDeque<>();
    for (String t : tokens) {
        switch (t) {
            case "+", "-", "*", "/" -> {
                int b = stack.pop(), a = stack.pop();
                stack.push(switch (t) {
                    case "+" -> a + b;
                    case "-" -> a - b;
                    case "*" -> a * b;
                    default  -> a / b;
                });
            }
            default -> stack.push(Integer.parseInt(t));
        }
    }
    return stack.pop();
}
```

### 8. Shunting-Yard (Infix → Postfix)

Dijkstra's algorithm for converting infix to RPN using an operator stack and output queue. Pop operators whose precedence ≥ the incoming operator's (strict `>` for right-associative ops like `^`).

```java
// Sketch: output list + operator stack
// - Operand: append to output
// - Operator o1: while top o2 has precedence >= o1 (> for right-assoc), pop to output; push o1
// - '(': push. ')': pop until '('.
// - End: drain remaining operators to output.
```

### 9. Min Stack — Alternate (Two-Stack)

Keep a parallel `minStack`; on push, push `min(val, minStack.peek())`. Avoids per-node `int[]` wrapper — handy when interviewer asks for O(1) extra memory *per push when no new minimum*.

## Essential Queue Patterns

### 1. BFS with Queue

```java
// Level-order traversal of a binary tree
public List<List<Integer>> levelOrder(TreeNode root) {
    List<List<Integer>> result = new ArrayList<>();
    if (root == null) return result;

    Queue<TreeNode> queue = new ArrayDeque<>();
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

### 3. Circular Queue (Ring Buffer)

Fixed-capacity FIFO over an array using `head`, `tail`, and `size` (or just two indices with a sentinel slot). O(1) enqueue/dequeue, no shifting.

```java
class MyCircularQueue {
    int[] buf; int head = 0, tail = -1, size = 0, cap;
    public MyCircularQueue(int k) { buf = new int[k]; cap = k; }
    public boolean enQueue(int v) {
        if (size == cap) return false;
        tail = (tail + 1) % cap; buf[tail] = v; size++; return true;
    }
    public boolean deQueue() {
        if (size == 0) return false;
        head = (head + 1) % cap; size--; return true;
    }
    public int Front() { return size == 0 ? -1 : buf[head]; }
    public int Rear()  { return size == 0 ? -1 : buf[tail]; }
    public boolean isEmpty() { return size == 0; }
    public boolean isFull()  { return size == cap; }
}
```

### 4. Implement Queue using Stacks

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
- **Monotonic stack direction (left-to-right scan):** A *decreasing* stack (top is smallest) pops when a larger element arrives → finds **next greater**. An *increasing* stack (top is largest) pops when a smaller arrives → finds **next smaller**. The popped element is the one being *resolved* for its neighbor. Draw two examples to confirm before coding.
- **`ArrayDeque` disallows nulls** — throws `NullPointerException` on `offer(null)` / `push(null)`. If you must store absence, use `Optional` or a sentinel, or fall back to `LinkedList`.
- **Java 21+ `getFirst`/`getLast` throw on empty** (unlike `peek`/`peekFirst` which return null). Pick the right variant for your empty-handling style.
- **BFS always uses a queue.** DFS uses a stack (or recursion, which is an implicit stack).
- **Capture `queue.size()` before the inner loop** in BFS level-order — the queue grows during the loop.
- **Stack for DFS:** Any recursive DFS can be converted to iterative using an explicit stack. Interviewers sometimes ask for this.
- **Two stacks = queue:** Know the amortized O(1) proof — each element is pushed and popped at most twice total.
