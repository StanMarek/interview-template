# Trees — Binary Trees and Binary Search Trees

## Overview

Trees are hierarchical data structures and the basis for a massive number of interview questions. You must be fluent in traversals, recursion on trees, and BST properties. Tree problems test your ability to think recursively and handle base cases cleanly.

## Core Concepts

### Node Structure

```java
class TreeNode {
    int val;
    TreeNode left, right;
    TreeNode(int val) { this.val = val; }
    TreeNode(int val, TreeNode left, TreeNode right) {
        this.val = val; this.left = left; this.right = right;
    }
}
```

### Binary Tree Properties

- **Full:** Every node has 0 or 2 children.
- **Complete:** All levels filled except possibly the last, which is filled left to right.
- **Perfect:** All internal nodes have 2 children and all leaves are at the same level. Nodes = 2^(h+1) - 1.
- **Balanced:** Height difference between left and right subtrees ≤ 1 at every node.
- **Height:** Longest path from root to a leaf. A single node has height 0.
- **Depth:** Distance from root to the node. Root has depth 0.

### Binary Search Tree (BST) Property

For every node: **all left descendants < node < all right descendants**.

This means an **inorder traversal of a BST yields sorted order**.

```java
// BST operations using Java's TreeMap (Red-Black Tree)
TreeMap<Integer, String> bst = new TreeMap<>();
bst.put(5, "five");
bst.firstKey();        // smallest key
bst.lastKey();         // largest key
bst.floorKey(4);       // greatest key ≤ 4
bst.ceilingKey(6);     // smallest key ≥ 6
bst.lowerKey(5);       // greatest key < 5
bst.higherKey(5);      // smallest key > 5
```

### Time Complexity

| Operation | BST (balanced) | BST (worst case) | TreeMap |
|-----------|----------------|-------------------|---------|
| Search    | O(log n)       | O(n) (skewed)     | O(log n)|
| Insert    | O(log n)       | O(n)              | O(log n)|
| Delete    | O(log n)       | O(n)              | O(log n)|
| Min/Max   | O(log n)       | O(n)              | O(log n)|

### Self-Balancing Trees: AVL vs Red-Black

- **AVL:** Strictly balanced (height diff ≤ 1). Faster lookups, more rotations on insert/delete. Good for read-heavy.
- **Red-Black:** Loosely balanced (longest path ≤ 2× shortest). Fewer rotations, faster mutations. **Java's `TreeMap`/`TreeSet` use Red-Black trees.** `HashMap` buckets also convert to Red-Black trees when a bucket exceeds 8 entries (treeify threshold).

## Tree Traversals

The foundation of all tree problems. Know all four by heart.

```java
// INORDER: Left → Root → Right (gives sorted order for BST)
public void inorder(TreeNode root) {
    if (root == null) return;
    inorder(root.left);
    process(root.val);
    inorder(root.right);
}

// PREORDER: Root → Left → Right (useful for serialization, copying trees)
public void preorder(TreeNode root) {
    if (root == null) return;
    process(root.val);
    preorder(root.left);
    preorder(root.right);
}

// POSTORDER: Left → Right → Root (useful for deletion, calculating sizes)
public void postorder(TreeNode root) {
    if (root == null) return;
    postorder(root.left);
    postorder(root.right);
    process(root.val);
}

// LEVEL ORDER (BFS): Level by level
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

### Iterative Traversals (Important — interviewers may ask)

```java
// Iterative Inorder using stack
public List<Integer> inorderIterative(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    Deque<TreeNode> stack = new ArrayDeque<>();
    TreeNode curr = root;
    while (curr != null || !stack.isEmpty()) {
        while (curr != null) {
            stack.push(curr);
            curr = curr.left;
        }
        curr = stack.pop();
        result.add(curr.val);
        curr = curr.right;
    }
    return result;
}

// Morris Inorder Traversal — O(1) space, no stack
public List<Integer> morrisInorder(TreeNode root) {
    List<Integer> result = new ArrayList<>();
    TreeNode curr = root;
    while (curr != null) {
        if (curr.left == null) {
            result.add(curr.val);
            curr = curr.right;
        } else {
            TreeNode pred = curr.left;
            while (pred.right != null && pred.right != curr) {
                pred = pred.right;
            }
            if (pred.right == null) {
                pred.right = curr; // create thread
                curr = curr.left;
            } else {
                pred.right = null; // remove thread
                result.add(curr.val);
                curr = curr.right;
            }
        }
    }
    return result;
}
```

## Essential Patterns

### 1. Recursive DFS with Return Values

Most tree problems follow this template:

```java
// Pattern: compute something for current node using results from subtrees
public int maxDepth(TreeNode root) {
    if (root == null) return 0;
    int left = maxDepth(root.left);
    int right = maxDepth(root.right);
    return Math.max(left, right) + 1;
}
```

### 2. Path Problems

```java
// Diameter of Binary Tree — longest path between any two nodes
int diameter = 0;

public int diameterOfBinaryTree(TreeNode root) {
    depth(root);
    return diameter;
}

private int depth(TreeNode node) {
    if (node == null) return 0;
    int left = depth(node.left);
    int right = depth(node.right);
    diameter = Math.max(diameter, left + right); // update answer
    return Math.max(left, right) + 1;            // return height
}
```

### 3. Validate BST

```java
public boolean isValidBST(TreeNode root) {
    return validate(root, Long.MIN_VALUE, Long.MAX_VALUE);
}

private boolean validate(TreeNode node, long min, long max) {
    if (node == null) return true;
    if (node.val <= min || node.val >= max) return false;
    return validate(node.left, min, node.val) &&
           validate(node.right, node.val, max);
}
```

### 4. Lowest Common Ancestor (LCA)

```java
// LCA in a general binary tree
public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
    if (root == null || root == p || root == q) return root;
    TreeNode left = lowestCommonAncestor(root.left, p, q);
    TreeNode right = lowestCommonAncestor(root.right, p, q);
    if (left != null && right != null) return root;
    return (left != null) ? left : right;
}

// LCA in a BST — use BST property
public TreeNode lcaBST(TreeNode root, TreeNode p, TreeNode q) {
    while (root != null) {
        if (p.val < root.val && q.val < root.val) root = root.left;
        else if (p.val > root.val && q.val > root.val) root = root.right;
        else return root;
    }
    return null;
}
```

### 5. Construct Trees from Traversals

```java
// Build tree from Preorder + Inorder
Map<Integer, Integer> inorderIndex = new HashMap<>();
int preIdx = 0;

public TreeNode buildTree(int[] preorder, int[] inorder) {
    for (int i = 0; i < inorder.length; i++) {
        inorderIndex.put(inorder[i], i);
    }
    return build(preorder, 0, inorder.length - 1);
}

private TreeNode build(int[] preorder, int left, int right) {
    if (left > right) return null;
    int rootVal = preorder[preIdx++];
    TreeNode root = new TreeNode(rootVal);
    root.left = build(preorder, left, inorderIndex.get(rootVal) - 1);
    root.right = build(preorder, inorderIndex.get(rootVal) + 1, right);
    return root;
}
```

### 6. BST Delete (with Inorder Successor)

```java
// Delete a node in a BST — replace with inorder successor when both children exist
public TreeNode deleteNode(TreeNode root, int key) {
    if (root == null) return null;
    if (key < root.val)       root.left  = deleteNode(root.left, key);
    else if (key > root.val)  root.right = deleteNode(root.right, key);
    else {                                      // found
        if (root.left == null)  return root.right;
        if (root.right == null) return root.left;
        TreeNode succ = root.right;             // inorder successor = leftmost of right
        while (succ.left != null) succ = succ.left;
        root.val = succ.val;
        root.right = deleteNode(root.right, succ.val);
    }
    return root;
}
```

### 7. Tree Views

```java
// Right Side View — last node at each level (BFS)
public List<Integer> rightSideView(TreeNode root) {
    List<Integer> res = new ArrayList<>();
    if (root == null) return res;
    Queue<TreeNode> q = new ArrayDeque<>();
    q.offer(root);
    while (!q.isEmpty()) {
        int sz = q.size();
        for (int i = 0; i < sz; i++) {
            TreeNode n = q.poll();
            if (i == sz - 1) res.add(n.val);    // rightmost
            if (n.left  != null) q.offer(n.left);
            if (n.right != null) q.offer(n.right);
        }
    }
    return res;
}

// Vertical Order — group by column index (col-1 left, col+1 right). Use TreeMap<col, List>.
// Boundary Traversal — root + left boundary (top-down) + leaves (L→R) + right boundary (bottom-up).
```

### 8. Tree DP / Path Sum Patterns

```java
// Max Path Sum (any-to-any) — classic "return best single branch, update global with both"
int maxSum = Integer.MIN_VALUE;
public int maxPathSum(TreeNode root) { gain(root); return maxSum; }
private int gain(TreeNode n) {
    if (n == null) return 0;
    int l = Math.max(0, gain(n.left));
    int r = Math.max(0, gain(n.right));
    maxSum = Math.max(maxSum, n.val + l + r);   // path through n
    return n.val + Math.max(l, r);              // path extending upward
}

// Path Sum III — count downward paths summing to target. Prefix-sum + HashMap (O(n)).
// House Robber III — tree DP returning {rob, skip} pair at each node.
```

### 9. Serialize / Deserialize

```java
// Using preorder with "null" markers
public String serialize(TreeNode root) {
    if (root == null) return "null";
    return root.val + "," + serialize(root.left) + "," + serialize(root.right);
}

private int idx = 0;
public TreeNode deserialize(String data) {
    String[] nodes = data.split(",");
    return buildFromArray(nodes);
}

private TreeNode buildFromArray(String[] nodes) {
    if (nodes[idx].equals("null")) { idx++; return null; }
    TreeNode node = new TreeNode(Integer.parseInt(nodes[idx++]));
    node.left = buildFromArray(nodes);
    node.right = buildFromArray(nodes);
    return node;
}
```

## Advanced: Range Query Structures

Rarely required in standard interviews but surface in senior/competitive rounds.

### Fenwick Tree (Binary Indexed Tree)

Point update + prefix/range sum in O(log n). Compact (~10 lines). Good for inversion count, range-sum with updates.

```java
class BIT {
    int[] t;
    BIT(int n) { t = new int[n + 1]; }
    void update(int i, int delta) { for (; i < t.length; i += i & -i) t[i] += delta; }
    int query(int i) { int s = 0; for (; i > 0; i -= i & -i) s += t[i]; return s; } // prefix [1..i]
    int range(int l, int r) { return query(r) - query(l - 1); }
}
```

### Segment Tree

Range queries (sum/min/max/gcd) + range updates with lazy propagation. O(n) build, O(log n) per op. Use when queries aren't prefix-decomposable (e.g., range min).

### LCA via Binary Lifting

O(n log n) preprocessing, O(log n) per query — beats the naive recursive LCA for multiple queries on static trees. Precompute `up[v][k] = 2^k-th ancestor of v` plus `depth[v]`. Jump `u` up to match `v`'s depth, then lift both until parents match.

### Euler Tour + RMQ

Flatten the tree via DFS; LCA of (u,v) = node with min depth in the Euler array between their first occurrences. Reduces LCA → Range Minimum Query (sparse table: O(n log n) build, O(1) query).

## Common Interview Problems

### Easy
- Maximum Depth, Same Tree, Symmetric Tree, Invert Binary Tree
- Path Sum, Subtree of Another Tree, Merge Two Binary Trees
- Balanced Binary Tree, Minimum Depth

### Medium
- Validate BST, Kth Smallest Element in BST, BST Iterator, Delete Node in a BST
- Lowest Common Ancestor, Binary Tree Level Order Traversal, Zigzag Level Order
- Construct Binary Tree from Preorder and Inorder
- Binary Tree Right Side View, Flatten Binary Tree to Linked List
- Count Good Nodes, Path Sum II/III, Populating Next Right Pointers
- Diameter of Binary Tree, House Robber III, All Nodes Distance K

### Hard
- Serialize and Deserialize Binary Tree, Binary Tree Maximum Path Sum
- Recover Binary Search Tree, Vertical Order Traversal, Boundary of Binary Tree
- Range Sum Query - Mutable (segment tree / BIT), Count of Smaller Numbers After Self (BIT)

## Tips and Pitfalls

- **Base case first:** Always handle `root == null` as the first line. This prevents NullPointerException and is the recursion termination.
- **Think bottom-up vs top-down:** Bottom-up (postorder) returns values up the tree. Top-down (preorder) passes values down. Choose the right one for the problem.
- **Global variables for accumulation:** Use a class-level variable (like `diameter` above) when you need to track an answer across recursive calls but the recursive function returns something else.
- **BST + Inorder = Sorted:** Any problem asking about "kth smallest" or "successor" in a BST should trigger inorder traversal thinking.
- **Use `long` for BST validation bounds** to handle `Integer.MIN_VALUE` and `Integer.MAX_VALUE` edge cases.
- **Don't confuse depth and height:** Depth is from root down, height is from leaf up.
- **Recursive vs. iterative:** Be prepared to do both. Morris traversal gives O(1) space but is harder to code — know it exists and when to mention it.
- **BST kth smallest / successor:** Iterative inorder with early exit, or augment nodes with subtree size for O(log n) lookup.
- **Multi-query LCA:** Plain recursion is O(n) per query; switch to binary lifting (O(log n) after O(n log n) prep) when many queries hit the same tree.
- **Prefix sum on trees:** For "path sum equals K" style problems, carry a HashMap of prefix sums down the recursion and decrement on backtrack — analogue of the array prefix-sum trick.
