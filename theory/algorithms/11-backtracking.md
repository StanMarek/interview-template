# Backtracking and Recursion

## Overview

Backtracking systematically explores all possible solutions by building candidates incrementally and abandoning ("pruning") branches that cannot lead to a valid solution. It's the go-to approach for combinatorial problems: permutations, combinations, subsets, constraint satisfaction, and puzzle solving.

## Core Template

```java
public void backtrack(List<List<Integer>> result, List<Integer> current,
                      int[] candidates, int start) {
    // 1. Check if current state is a valid solution
    if (isComplete(current)) {
        result.add(new ArrayList<>(current)); // COPY the list!
        return;
    }

    // 2. Try all choices from this state
    for (int i = start; i < candidates.length; i++) {
        // 3. Prune: skip invalid choices early
        if (!isValid(candidates[i])) continue;

        current.add(candidates[i]);       // choose
        backtrack(result, current, candidates, i + 1); // explore
        current.remove(current.size() - 1); // un-choose (backtrack)
    }
}
```

## Essential Patterns

### 1. Subsets

```java
// All subsets (power set) — 2^n results
public List<List<Integer>> subsets(int[] nums) {
    List<List<Integer>> result = new ArrayList<>();
    backtrack(result, new ArrayList<>(), nums, 0);
    return result;
}

private void backtrack(List<List<Integer>> result, List<Integer> curr,
                       int[] nums, int start) {
    result.add(new ArrayList<>(curr)); // every state is a valid subset
    for (int i = start; i < nums.length; i++) {
        curr.add(nums[i]);
        backtrack(result, curr, nums, i + 1);
        curr.remove(curr.size() - 1);
    }
}

// Subsets with duplicates — sort + skip
public List<List<Integer>> subsetsWithDup(int[] nums) {
    Arrays.sort(nums); // sort first!
    List<List<Integer>> result = new ArrayList<>();
    backtrack2(result, new ArrayList<>(), nums, 0);
    return result;
}

private void backtrack2(List<List<Integer>> result, List<Integer> curr,
                        int[] nums, int start) {
    result.add(new ArrayList<>(curr));
    for (int i = start; i < nums.length; i++) {
        if (i > start && nums[i] == nums[i - 1]) continue; // skip dups
        curr.add(nums[i]);
        backtrack2(result, curr, nums, i + 1);
        curr.remove(curr.size() - 1);
    }
}
```

### 2. Permutations

```java
// All permutations — n! results
public List<List<Integer>> permute(int[] nums) {
    List<List<Integer>> result = new ArrayList<>();
    boolean[] used = new boolean[nums.length];
    backtrack(result, new ArrayList<>(), nums, used);
    return result;
}

private void backtrack(List<List<Integer>> result, List<Integer> curr,
                       int[] nums, boolean[] used) {
    if (curr.size() == nums.length) {
        result.add(new ArrayList<>(curr));
        return;
    }
    for (int i = 0; i < nums.length; i++) {
        if (used[i]) continue;
        if (i > 0 && nums[i] == nums[i-1] && !used[i-1]) continue; // skip dups
        used[i] = true;
        curr.add(nums[i]);
        backtrack(result, curr, nums, used);
        curr.remove(curr.size() - 1);
        used[i] = false;
    }
}
```

### 3. Combination Sum

```java
// Elements can be reused, find combos summing to target
public List<List<Integer>> combinationSum(int[] candidates, int target) {
    List<List<Integer>> result = new ArrayList<>();
    Arrays.sort(candidates);
    backtrack(result, new ArrayList<>(), candidates, target, 0);
    return result;
}

private void backtrack(List<List<Integer>> result, List<Integer> curr,
                       int[] candidates, int remain, int start) {
    if (remain == 0) { result.add(new ArrayList<>(curr)); return; }
    for (int i = start; i < candidates.length; i++) {
        if (candidates[i] > remain) break; // prune — sorted, so no need to continue
        curr.add(candidates[i]);
        backtrack(result, curr, candidates, remain - candidates[i], i); // i, not i+1 — reuse allowed
        curr.remove(curr.size() - 1);
    }
}
```

### 4. N-Queens

```java
public List<List<String>> solveNQueens(int n) {
    List<List<String>> result = new ArrayList<>();
    char[][] board = new char[n][n];
    for (char[] row : board) Arrays.fill(row, '.');
    solve(result, board, 0, n);
    return result;
}

private void solve(List<List<String>> result, char[][] board, int row, int n) {
    if (row == n) {
        List<String> snapshot = new ArrayList<>();
        for (char[] r : board) snapshot.add(new String(r));
        result.add(snapshot);
        return;
    }
    for (int col = 0; col < n; col++) {
        if (isValid(board, row, col, n)) {
            board[row][col] = 'Q';
            solve(result, board, row + 1, n);
            board[row][col] = '.';
        }
    }
}

private boolean isValid(char[][] board, int row, int col, int n) {
    for (int i = 0; i < row; i++) {
        if (board[i][col] == 'Q') return false;
        if (col - (row - i) >= 0 && board[i][col - (row - i)] == 'Q') return false;
        if (col + (row - i) < n && board[i][col + (row - i)] == 'Q') return false;
    }
    return true;
}
```

### 5. Word Search (Grid Backtracking)

```java
public boolean exist(char[][] board, String word) {
    for (int i = 0; i < board.length; i++)
        for (int j = 0; j < board[0].length; j++)
            if (dfs(board, word, i, j, 0)) return true;
    return false;
}

private boolean dfs(char[][] board, String word, int r, int c, int idx) {
    if (idx == word.length()) return true;
    if (r < 0 || r >= board.length || c < 0 || c >= board[0].length
        || board[r][c] != word.charAt(idx)) return false;

    char tmp = board[r][c];
    board[r][c] = '#'; // mark visited
    boolean found = dfs(board, word, r+1, c, idx+1)
                 || dfs(board, word, r-1, c, idx+1)
                 || dfs(board, word, r, c+1, idx+1)
                 || dfs(board, word, r, c-1, idx+1);
    board[r][c] = tmp; // restore
    return found;
}
```

## Common Interview Problems

**Easy:** Letter Combinations of a Phone Number (arguably medium).

**Medium:** Subsets, Subsets II, Permutations, Permutations II, Combination Sum (I, II, III), Palindrome Partitioning, Generate Parentheses, Word Search, Letter Combinations.

**Hard:** N-Queens, Sudoku Solver, Word Search II, Partition to K Equal Sum Subsets, Expression Add Operators.

## Tips and Pitfalls

- **Always copy the list** when adding to results: `new ArrayList<>(current)`. Otherwise all results point to the same (empty) list.
- **Sort first** when dealing with duplicates, then skip `nums[i] == nums[i-1]`.
- **`start` index vs `used[]` array:** Use `start` for combinations (order doesn't matter). Use `used[]` for permutations (order matters).
- **Pruning is critical for performance.** Sort candidates and `break` early when remaining sum goes negative.
- **Time complexity:** Usually exponential — O(2^n) for subsets, O(n!) for permutations. That's expected; these problems have exponential solution spaces.
- **Grid backtracking:** Mark cells visited by modifying the grid directly (save and restore), avoiding extra `visited` array allocation.
