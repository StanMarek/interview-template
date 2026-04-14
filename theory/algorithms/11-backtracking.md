# Backtracking and Recursion

## Overview

Backtracking systematically explores all possible solutions by building candidates incrementally and abandoning ("pruning") branches that cannot lead to a valid solution. It's the go-to approach for combinatorial problems: permutations, combinations, subsets, constraint satisfaction, and puzzle solving.

The canonical pattern is **choose / explore / un-choose** — add a candidate, recurse, then undo. The undo step is what makes it "backtracking" as opposed to plain DFS.

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

### 5. Generate Parentheses

```java
// All valid combinations of n pairs — Catalan number C(n)
public List<String> generateParenthesis(int n) {
    List<String> result = new ArrayList<>();
    backtrack(result, new StringBuilder(), 0, 0, n);
    return result;
}

private void backtrack(List<String> result, StringBuilder sb,
                       int open, int close, int n) {
    if (sb.length() == 2 * n) { result.add(sb.toString()); return; }
    if (open < n) {                     // can still open
        sb.append('(');
        backtrack(result, sb, open + 1, close, n);
        sb.deleteCharAt(sb.length() - 1);
    }
    if (close < open) {                 // only close if unmatched open exists
        sb.append(')');
        backtrack(result, sb, open, close + 1, n);
        sb.deleteCharAt(sb.length() - 1);
    }
}
```

### 6. Palindrome Partitioning

```java
// Partition s so every substring is a palindrome
public List<List<String>> partition(String s) {
    List<List<String>> result = new ArrayList<>();
    backtrack(result, new ArrayList<>(), s, 0);
    return result;
}

private void backtrack(List<List<String>> result, List<String> curr,
                       String s, int start) {
    if (start == s.length()) { result.add(new ArrayList<>(curr)); return; }
    for (int end = start + 1; end <= s.length(); end++) {
        if (isPalindrome(s, start, end - 1)) {
            curr.add(s.substring(start, end));
            backtrack(result, curr, s, end);
            curr.remove(curr.size() - 1);
        }
    }
}
// Optional: precompute boolean[][] dp for O(1) palindrome checks → O(n·2^n).
```

### 7. Word Search (Grid Backtracking)

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

## Iterative Subsets via Bitmask

For `n <= 30`, skip recursion entirely — each integer `mask` in `[0, 2^n)` encodes one subset by bit position.

```java
public List<List<Integer>> subsetsBitmask(int[] nums) {
    int n = nums.length;
    List<List<Integer>> result = new ArrayList<>(1 << n);
    for (int mask = 0; mask < (1 << n); mask++) {
        List<Integer> sub = new ArrayList<>(Integer.bitCount(mask));
        for (int i = 0; i < n; i++)
            if ((mask & (1 << i)) != 0) sub.add(nums[i]);
        result.add(sub);
    }
    return result;
}
// O(n · 2^n) time, no recursion stack. Useful when n is small and fixed.
```

## Complexity — Recursion Tree Template

Estimate cost as **(branches per node) ^ depth × work per leaf**:

| Problem | Branching | Depth | Time | Space (stack + output) |
|---------|-----------|-------|------|------------------------|
| Subsets | 2 (take/skip) | n | O(n · 2^n) | O(n) recursion, O(n · 2^n) output |
| Permutations | n, n-1, … | n | O(n · n!) | O(n) recursion, O(n · n!) output |
| Combination Sum | up to k | target/min | O(N^(T/M + 1)) | O(T/M) |
| N-Queens | n per row | n | O(n!) (pruned) | O(n^2) board |
| Palindrome Partitioning | up to n | n | O(n · 2^n) | O(n) |
| Sudoku | up to 9 per cell | 81 | exp. (heavy prune) | O(1) |

Copying a length-k list into the result is O(k); multiply through if relevant.

## Pruning Strategies

- **Sort-then-prune:** sort candidates ascending; `break` when `candidates[i] > remain` (combination sum) or when a bound is exceeded.
- **Skip duplicates at the same tree level:** after sorting, `if (i > start && nums[i] == nums[i-1]) continue;` — avoids generating permutations/subsets that are equal as sets.
- **Constraint propagation (Sudoku, N-Queens):** keep row/col/diag/box bitmasks and test in O(1) instead of rescanning.
- **Most-constrained variable:** in Sudoku, pick the empty cell with the fewest legal digits next (MRV heuristic) to fail fast.
- **Symmetry breaking:** for N-Queens, only search half the columns in row 0 and mirror results.
- **Memoization on state:** when subtrees repeat (Word Break II, Expression problems), cache `(index, state) → result` — this is where backtracking meets DP.

## Common Interview Problems

**Easy:** Letter Combinations of a Phone Number (arguably medium).

**Medium:** Subsets, Subsets II, Permutations, Permutations II, Combination Sum (I, II, III), Palindrome Partitioning, Generate Parentheses, Word Search, Letter Combinations, Restore IP Addresses.

**Hard:** N-Queens, Sudoku Solver, Word Search II (trie + backtracking), Word Break II, Partition to K Equal Sum Subsets, Expression Add Operators, Remove Invalid Parentheses, Knight's Tour.

## Tips and Pitfalls

- **Always copy the list** when adding to results: `new ArrayList<>(current)`. Otherwise all results point to the same (empty) list.
- **Sort first** when dealing with duplicates, then skip `nums[i] == nums[i-1]`.
- **`start` index vs `used[]` array:** Use `start` for combinations (order doesn't matter). Use `used[]` for permutations (order matters).
- **Pruning is critical for performance.** Sort candidates and `break` early when remaining sum goes negative.
- **Time complexity:** Usually exponential — O(2^n) for subsets, O(n!) for permutations. That's expected; these problems have exponential solution spaces.
- **Grid backtracking:** Mark cells visited by modifying the grid directly (save and restore), avoiding extra `visited` array allocation.
- **Permutations II duplicate skip:** `nums[i] == nums[i-1] && !used[i-1]` — ensures identical values are consumed in fixed left-to-right order so each distinct permutation appears exactly once.
- **StringBuilder over String concatenation:** for Generate Parentheses, Restore IP Addresses, Letter Combinations — append/deleteCharAt instead of rebuilding strings, turning O(n) per step into O(1).
- **Return `boolean` for "find one" problems** (Sudoku, Word Search): short-circuit the recursion as soon as a solution is found instead of exploring the rest.
