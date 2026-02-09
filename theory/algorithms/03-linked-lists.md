# Linked Lists

## Overview

A linked list is a linear data structure where each element (node) contains a value and a pointer to the next node. Unlike arrays, elements are not stored contiguously in memory. Linked list problems are interview favorites because they test pointer manipulation, edge case handling, and in-place algorithm design.

## Core Concepts

### Node Structure

```java
// Singly Linked List
class ListNode {
    int val;
    ListNode next;
    ListNode(int val) { this.val = val; }
    ListNode(int val, ListNode next) { this.val = val; this.next = next; }
}

// Doubly Linked List
class DListNode {
    int val;
    DListNode prev, next;
    DListNode(int val) { this.val = val; }
}
```

### Java's Built-in `LinkedList`

```java
LinkedList<Integer> list = new LinkedList<>();
list.addFirst(1);        // O(1)
list.addLast(2);         // O(1)
list.removeFirst();      // O(1)
list.removeLast();       // O(1)
list.get(2);             // O(n) — random access is slow
list.peekFirst();        // O(1)
list.peekLast();         // O(1)
// Also implements Deque interface — can use as stack or queue
```

### Time Complexity

| Operation         | Singly | Doubly | ArrayList |
|--------------------|--------|--------|-----------|
| Access by index    | O(n)   | O(n)   | O(1)      |
| Insert at head     | O(1)   | O(1)   | O(n)      |
| Insert at tail     | O(n)*  | O(1)   | O(1)*     |
| Insert at middle   | O(n)   | O(n)   | O(n)      |
| Delete at head     | O(1)   | O(1)   | O(n)      |
| Delete at tail     | O(n)   | O(1)   | O(1)      |
| Search             | O(n)   | O(n)   | O(n)      |

*O(1) if you maintain a tail pointer.

## Essential Techniques and Patterns

### 1. Dummy Head Node

The single most important linked list technique. Eliminates all head-node edge cases.

```java
// Without dummy: need special cases for head changes
// With dummy: uniform logic for all nodes
ListNode dummy = new ListNode(0);
dummy.next = head;
// ... manipulate list ...
return dummy.next; // new head
```

### 2. Fast and Slow Pointers (Floyd's Tortoise and Hare)

```java
// Find middle of linked list
public ListNode middleNode(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }
    return slow; // middle (or second middle if even length)
}

// Detect cycle
public boolean hasCycle(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) return true;
    }
    return false;
}

// Find cycle start (Floyd's algorithm)
public ListNode detectCycle(ListNode head) {
    ListNode slow = head, fast = head;
    while (fast != null && fast.next != null) {
        slow = slow.next;
        fast = fast.next.next;
        if (slow == fast) {
            ListNode entry = head;
            while (entry != slow) {
                entry = entry.next;
                slow = slow.next;
            }
            return entry;
        }
    }
    return null;
}
```

### 3. Reverse a Linked List

The most fundamental linked list operation. Master both iterative and recursive versions.

```java
// Iterative — O(n) time, O(1) space
public ListNode reverseList(ListNode head) {
    ListNode prev = null, curr = head;
    while (curr != null) {
        ListNode next = curr.next;
        curr.next = prev;
        prev = curr;
        curr = next;
    }
    return prev;
}

// Recursive — O(n) time, O(n) space (call stack)
public ListNode reverseListRecursive(ListNode head) {
    if (head == null || head.next == null) return head;
    ListNode newHead = reverseListRecursive(head.next);
    head.next.next = head;
    head.next = null;
    return newHead;
}

// Reverse a portion [left, right]
public ListNode reverseBetween(ListNode head, int left, int right) {
    ListNode dummy = new ListNode(0, head);
    ListNode prev = dummy;
    for (int i = 0; i < left - 1; i++) prev = prev.next;

    ListNode curr = prev.next;
    for (int i = 0; i < right - left; i++) {
        ListNode next = curr.next;
        curr.next = next.next;
        next.next = prev.next;
        prev.next = next;
    }
    return dummy.next;
}
```

### 4. Merge Two Sorted Lists

```java
public ListNode mergeTwoLists(ListNode l1, ListNode l2) {
    ListNode dummy = new ListNode(0), curr = dummy;
    while (l1 != null && l2 != null) {
        if (l1.val <= l2.val) {
            curr.next = l1;
            l1 = l1.next;
        } else {
            curr.next = l2;
            l2 = l2.next;
        }
        curr = curr.next;
    }
    curr.next = (l1 != null) ? l1 : l2;
    return dummy.next;
}
```

### 5. Remove Nth Node from End

```java
public ListNode removeNthFromEnd(ListNode head, int n) {
    ListNode dummy = new ListNode(0, head);
    ListNode fast = dummy, slow = dummy;
    for (int i = 0; i <= n; i++) fast = fast.next; // n+1 steps ahead
    while (fast != null) {
        fast = fast.next;
        slow = slow.next;
    }
    slow.next = slow.next.next;
    return dummy.next;
}
```

### 6. Reorder List (L0→Ln→L1→Ln-1→...)

Combines three patterns: find middle, reverse second half, merge alternately.

```java
public void reorderList(ListNode head) {
    // 1. Find middle
    ListNode slow = head, fast = head;
    while (fast.next != null && fast.next.next != null) {
        slow = slow.next;
        fast = fast.next.next;
    }

    // 2. Reverse second half
    ListNode prev = null, curr = slow.next;
    slow.next = null;
    while (curr != null) {
        ListNode next = curr.next;
        curr.next = prev;
        prev = curr;
        curr = next;
    }

    // 3. Merge two halves alternately
    ListNode first = head, second = prev;
    while (second != null) {
        ListNode tmp1 = first.next, tmp2 = second.next;
        first.next = second;
        second.next = tmp1;
        first = tmp1;
        second = tmp2;
    }
}
```

## Common Interview Problems

### Easy
- Reverse Linked List, Merge Two Sorted Lists
- Linked List Cycle, Remove Duplicates from Sorted List
- Palindrome Linked List, Middle of the Linked List
- Intersection of Two Linked Lists

### Medium
- Add Two Numbers, Remove Nth Node From End of List
- Reorder List, Sort List (merge sort), Swap Nodes in Pairs
- Copy List with Random Pointer, Rotate List
- Flatten a Multilevel Doubly Linked List
- Reverse Linked List II, Partition List

### Hard
- Merge K Sorted Lists, Reverse Nodes in k-Group
- LRU Cache (doubly linked list + HashMap)
- LFU Cache

## Tips and Pitfalls

- **Always use a dummy node** when the head might change (deletions, insertions, merges).
- **Draw it out:** Sketch the pointer manipulations on paper before coding. This prevents 90% of bugs.
- **Save `next` before overwriting:** Anytime you do `curr.next = something`, save `curr.next` first if you still need it.
- **Null checks:** Always check `node != null` before accessing `node.next` or `node.val`.
- **Return `dummy.next`**, not `head` — head may have been removed or changed.
- **Recursive solutions** are elegant but use O(n) stack space. Interviewers may ask for iterative alternatives.
- **Test edge cases:** empty list (`null`), single node, two nodes, odd/even length.
- **In-place vs. creating new nodes:** Most interview problems expect in-place modification by rewiring pointers, not creating new nodes.
