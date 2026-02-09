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

### 6. Serialize / Deserialize

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

## Common Interview Problems

### Easy
- Maximum Depth, Same Tree, Symmetric Tree, Invert Binary Tree
- Path Sum, Subtree of Another Tree, Merge Two Binary Trees
- Balanced Binary Tree, Minimum Depth

### Medium
- Validate BST, Kth Smallest Element in BST, BST Iterator
- Lowest Common Ancestor, Binary Tree Level Order Traversal
- Construct Binary Tree from Preorder and Inorder
- Binary Tree Right Side View, Flatten Binary Tree to Linked List
- Count Good Nodes, Path Sum II/III, Populating Next Right Pointers

### Hard
- Serialize and Deserialize Binary Tree, Binary Tree Maximum Path Sum
- Recover Binary Search Tree, Vertical Order Traversal

## Tips and Pitfalls

- **Base case first:** Always handle `root == null` as the first line. This prevents NullPointerException and is the recursion termination.
- **Think bottom-up vs top-down:** Bottom-up (postorder) returns values up the tree. Top-down (preorder) passes values down. Choose the right one for the problem.
- **Global variables for accumulation:** Use a class-level variable (like `diameter` above) when you need to track an answer across recursive calls but the recursive function returns something else.
- **BST + Inorder = Sorted:** Any problem asking about "kth smallest" or "successor" in a BST should trigger inorder traversal thinking.
- **Use `long` for BST validation bounds** to handle `Integer.MIN_VALUE` and `Integer.MAX_VALUE` edge cases.
- **Don't confuse depth and height:** Depth is from root down, height is from leaf up.
- **Recursive vs. iterative:** Be prepared to do both. Morris traversal gives O(1) space but is harder to code — know it exists and when to mention it.
