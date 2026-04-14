# Dynamic Programming

## Overview

Dynamic Programming (DP) is an optimization technique for problems with **overlapping subproblems** and **optimal substructure**. DP problems are among the most frequently asked interview questions.

## When to Use DP

Recognize these signals:
- "Find the minimum/maximum..."
- "Count the number of ways..."
- "Is it possible to..." (decision problems)
- "Find the longest/shortest..."
- The problem can be broken into smaller subproblems that repeat.

## Two Approaches

**Top-Down (Memoization):** Recursive + cache. Start from the main problem and recurse down. Prefer an `int[][]` sentinel (e.g. `-1`) over `HashMap<String,...>` — string keys are 10–50x slower.

```java
int[][] memo; // initialize with Arrays.fill(row, -1)
public int solve(int i, int j) {
    if (/* base case */) return /* value */;
    if (memo[i][j] != -1) return memo[i][j];
    return memo[i][j] = /* recursive formula */;
}
```

**Bottom-Up (Tabulation):** Iterative + array. No recursion overhead, no stack-overflow risk, easier to space-optimize.

```java
int[] dp = new int[n + 1];
dp[0] = baseCase;
for (int i = 1; i <= n; i++) {
    dp[i] = /* formula using dp[i-1], dp[i-2], etc. */;
}
return dp[n];
```

**Pick top-down when** the state space is sparse (only a fraction reachable), recurrence order is awkward, or you want to bail on unreachable states. **Pick bottom-up when** all states are visited, you need space optimization (rolling rows), or recursion depth would blow the stack.

## DP Problem-Solving Framework

1. **Define the state:** What does `dp[i]` represent?
2. **Find the recurrence:** How does `dp[i]` relate to previous states?
3. **Identify base cases:** What are the trivial subproblems?
4. **Determine computation order:** Which states must be computed first?
5. **Optimize space** if possible (2D → 1D, or 1D → two variables).

## Essential Patterns

### 1. Linear DP

```java
// Climbing Stairs — dp[i] = ways to reach step i
public int climbStairs(int n) {
    if (n <= 2) return n;
    int prev2 = 1, prev1 = 2;
    for (int i = 3; i <= n; i++) {
        int curr = prev1 + prev2;
        prev2 = prev1;
        prev1 = curr;
    }
    return prev1;
}

// House Robber — dp[i] = max money robbing houses 0..i
public int rob(int[] nums) {
    int prev2 = 0, prev1 = 0;
    for (int num : nums) {
        int curr = Math.max(prev1, prev2 + num);
        prev2 = prev1;
        prev1 = curr;
    }
    return prev1;
}
```

### 2. Grid DP

```java
// Unique Paths — dp[i][j] = paths from (0,0) to (i,j)
public int uniquePaths(int m, int n) {
    int[][] dp = new int[m][n];
    for (int i = 0; i < m; i++) dp[i][0] = 1;
    for (int j = 0; j < n; j++) dp[0][j] = 1;
    for (int i = 1; i < m; i++)
        for (int j = 1; j < n; j++)
            dp[i][j] = dp[i-1][j] + dp[i][j-1];
    return dp[m-1][n-1];
}
```

### 3. Knapsack Variants

```java
// 0/1 Knapsack — each item used at most once
public int knapsack01(int[] weights, int[] values, int capacity) {
    int[] dp = new int[capacity + 1];
    for (int i = 0; i < weights.length; i++) {
        for (int w = capacity; w >= weights[i]; w--) { // backwards!
            dp[w] = Math.max(dp[w], dp[w - weights[i]] + values[i]);
        }
    }
    return dp[capacity];
}

// Unbounded Knapsack — Coin Change
public int coinChange(int[] coins, int amount) {
    int[] dp = new int[amount + 1];
    Arrays.fill(dp, amount + 1);
    dp[0] = 0;
    for (int i = 1; i <= amount; i++)
        for (int coin : coins)
            if (coin <= i)
                dp[i] = Math.min(dp[i], dp[i - coin] + 1);
    return dp[amount] > amount ? -1 : dp[amount];
}
```

**Variants:**
- **0/1:** each item at most once. Iterate `w` **backwards**.
- **Unbounded:** each item unlimited times. Iterate `w` **forwards**.
- **Bounded:** each item has count `c[i]`. Either expand into `c[i]` copies, or use binary decomposition (1, 2, 4, ..., remainder) to reduce to 0/1 in `O(log c)` items.
- **Coin Change — count ways:** outer loop coins, inner loop amount — order matters (outer amount, inner coins gives permutations, not combinations).

### 4. Two-Sequence DP

```java
// Longest Common Subsequence
public int longestCommonSubsequence(String t1, String t2) {
    int m = t1.length(), n = t2.length();
    int[][] dp = new int[m + 1][n + 1];
    for (int i = 1; i <= m; i++)
        for (int j = 1; j <= n; j++)
            dp[i][j] = (t1.charAt(i-1) == t2.charAt(j-1))
                ? dp[i-1][j-1] + 1
                : Math.max(dp[i-1][j], dp[i][j-1]);
    return dp[m][n];
}

// Edit Distance
public int minDistance(String w1, String w2) {
    int m = w1.length(), n = w2.length();
    int[][] dp = new int[m + 1][n + 1];
    for (int i = 0; i <= m; i++) dp[i][0] = i;
    for (int j = 0; j <= n; j++) dp[0][j] = j;
    for (int i = 1; i <= m; i++)
        for (int j = 1; j <= n; j++)
            dp[i][j] = (w1.charAt(i-1) == w2.charAt(j-1))
                ? dp[i-1][j-1]
                : 1 + Math.min(dp[i-1][j-1], Math.min(dp[i-1][j], dp[i][j-1]));
    return dp[m][n];
}
```

### 5. Longest Increasing Subsequence

```java
// O(n^2)
public int lengthOfLIS(int[] nums) {
    int[] dp = new int[nums.length];
    Arrays.fill(dp, 1);
    int max = 1;
    for (int i = 1; i < nums.length; i++) {
        for (int j = 0; j < i; j++)
            if (nums[j] < nums[i])
                dp[i] = Math.max(dp[i], dp[j] + 1);
        max = Math.max(max, dp[i]);
    }
    return max;
}

// O(n log n) with patience sorting. `tails[k]` = smallest tail of an LIS of length k+1.
public int lengthOfLIS_fast(int[] nums) {
    int[] tails = new int[nums.length];
    int size = 0;
    for (int num : nums) {
        int pos = Arrays.binarySearch(tails, 0, size, num);
        if (pos < 0) pos = -(pos + 1);
        tails[pos] = num;
        if (pos == size) size++;
    }
    return size;
}
// Note: Java's binarySearch returns `-(insertion point) - 1` when absent.
// For strictly increasing LIS, duplicates overwrite (same length). For non-decreasing,
// replace binarySearch with a custom upper-bound that returns first index > num.
```

### 6. Interval / Palindrome DP

Iterate by **interval length**, pick a split point inside `[i, j]`. Outer loop is length, then left endpoint.

```java
// Longest Palindromic Substring
public String longestPalindrome(String s) {
    int n = s.length(), start = 0, maxLen = 1;
    boolean[][] dp = new boolean[n][n];
    for (int i = 0; i < n; i++) dp[i][i] = true;
    for (int len = 2; len <= n; len++) {
        for (int i = 0; i <= n - len; i++) {
            int j = i + len - 1;
            if (s.charAt(i) == s.charAt(j)) {
                dp[i][j] = (len == 2) || dp[i+1][j-1];
                if (dp[i][j] && len > maxLen) { start = i; maxLen = len; }
            }
        }
    }
    return s.substring(start, start + maxLen);
}

// Burst Balloons — max coins. dp[i][j] = best for open interval (i, j)
public int maxCoins(int[] nums) {
    int n = nums.length;
    int[] a = new int[n + 2];
    a[0] = a[n + 1] = 1;
    System.arraycopy(nums, 0, a, 1, n);
    int[][] dp = new int[n + 2][n + 2];
    for (int len = 2; len <= n + 1; len++) {
        for (int i = 0; i + len <= n + 1; i++) {
            int j = i + len;
            for (int k = i + 1; k < j; k++) // k = last balloon burst in (i, j)
                dp[i][j] = Math.max(dp[i][j], dp[i][k] + dp[k][j] + a[i] * a[k] * a[j]);
        }
    }
    return dp[0][n + 1];
}
```

### 7. State Machine DP

```java
// Best Time to Buy and Sell Stock with Cooldown
public int maxProfit(int[] prices) {
    int held = Integer.MIN_VALUE, sold = 0, rest = 0;
    for (int p : prices) {
        int prevSold = sold;
        sold = held + p;
        held = Math.max(held, rest - p);
        rest = Math.max(rest, prevSold);
    }
    return Math.max(sold, rest);
}
```

### 8. Bitmask DP (n ≤ 20)

State is a bitmask of "used" items. Classic for Traveling Salesman, assignment problems, subset enumeration.

```java
// TSP — min cost to visit all cities, end anywhere. O(n^2 · 2^n)
public int tsp(int[][] dist) {
    int n = dist.length, FULL = (1 << n) - 1;
    int[][] dp = new int[1 << n][n];
    for (int[] row : dp) Arrays.fill(row, Integer.MAX_VALUE / 2);
    dp[1][0] = 0; // start at city 0
    for (int mask = 1; mask <= FULL; mask++) {
        for (int u = 0; u < n; u++) {
            if ((mask & (1 << u)) == 0) continue;
            for (int v = 0; v < n; v++) {
                if ((mask & (1 << v)) != 0) continue;
                int next = mask | (1 << v);
                dp[next][v] = Math.min(dp[next][v], dp[mask][u] + dist[u][v]);
            }
        }
    }
    int ans = Integer.MAX_VALUE;
    for (int v = 0; v < n; v++) ans = Math.min(ans, dp[FULL][v]);
    return ans;
}
```

Bit tricks: `mask & (mask - 1)` clears lowest bit; `Integer.bitCount(mask)` counts set bits; iterate subsets of `mask` with `for (int s = mask; s > 0; s = (s - 1) & mask)`.

### 9. Tree DP

Post-order: compute each child, combine at parent. Often pair "include root" vs "exclude root" states.

```java
// House Robber III — rob houses on a tree, no parent+child together
public int rob(TreeNode root) {
    int[] r = dfs(root); // [withoutRoot, withRoot]
    return Math.max(r[0], r[1]);
}
private int[] dfs(TreeNode n) {
    if (n == null) return new int[2];
    int[] l = dfs(n.left), r = dfs(n.right);
    int without = Math.max(l[0], l[1]) + Math.max(r[0], r[1]);
    int with = n.val + l[0] + r[0];
    return new int[]{without, with};
}
```

### 10. Game Theory DP (Minimax + Memo)

Two players, both optimal. Current player maximizes; `dp[state]` = best score for the player to move.

```java
// Stone Game — pick from ends, return true if player 1 wins
Integer[][] memo;
public boolean stoneGame(int[] piles) {
    memo = new Integer[piles.length][piles.length];
    return score(piles, 0, piles.length - 1) > 0;
}
private int score(int[] p, int i, int j) { // net score for player to move
    if (i > j) return 0;
    if (memo[i][j] != null) return memo[i][j];
    int take_l = p[i] - score(p, i + 1, j);
    int take_r = p[j] - score(p, i, j - 1);
    return memo[i][j] = Math.max(take_l, take_r);
}
```

### 11. Digit DP

Count numbers ≤ N with some digit property. State: `(position, tight, started, extra)`.

```java
// Count numbers in [0, n] whose digits sum to target
int[] digits; int target; Integer[][][] memo;
public int count(int n, int target) {
    String s = Integer.toString(n);
    digits = new int[s.length()];
    for (int i = 0; i < s.length(); i++) digits[i] = s.charAt(i) - '0';
    this.target = target;
    memo = new Integer[s.length()][target + 1][2];
    return dp(0, 0, 1);
}
private int dp(int pos, int sum, int tight) {
    if (pos == digits.length) return sum == target ? 1 : 0;
    if (memo[pos][sum][tight] != null) return memo[pos][sum][tight];
    int lim = tight == 1 ? digits[pos] : 9, res = 0;
    for (int d = 0; d <= lim && sum + d <= target; d++)
        res += dp(pos + 1, sum + d, tight == 1 && d == lim ? 1 : 0);
    return memo[pos][sum][tight] = res;
}
```

## Common Interview Problems

**Easy:** Climbing Stairs, House Robber, Min Cost Climbing Stairs, Pascal's Triangle. (Kadane's — see `01-arrays-and-strings.md`.)

**Medium:** Coin Change, LIS, LCS, Word Break, Unique Paths, Decode Ways, Partition Equal Subset Sum, Target Sum, House Robber II/III, Jump Game, Edit Distance, Stone Game.

**Hard:** Regular Expression Matching, Wildcard Matching, Burst Balloons, Longest Valid Parentheses, Palindrome Partitioning II, Distinct Subsequences, Interleaving String, TSP (bitmask), Count Numbers With Unique Digits (digit DP).

## Tips and Pitfalls

- **Start with brute-force recursion**, then add memoization, then convert to bottom-up if needed.
- **State definition is everything.** If your DP doesn't work, you probably defined the state wrong.
- **Space optimization:** If `dp[i]` only depends on `dp[i-1]` and `dp[i-2]`, use two variables. For 2D `dp[i][j]` depending only on row `i-1`, roll into two 1D arrays (or one, iterating the inner index carefully).
- **0/1 knapsack iterates capacity backwards**; unbounded iterates forwards.
- **Watch off-by-one** in base cases and loop bounds. When in doubt, draw the table on a small example.
- **DP on strings:** almost always `dp[i][j]` indexing both strings; add a length-0 sentinel row/col.
- **Bitmask DP** for n ≤ 20: state is `dp[mask]` or `dp[mask][i]`. Complexity `O(2^n · n)` or `O(2^n · n^2)`.
- **Tree DP:** post-order recursion; return a small tuple from each child (e.g. `{with, without}`).
- **Game theory DP:** define `dp[state]` from the current player's perspective; flip sign on recursion.
- **Digit DP:** carry `tight` (still bounded by N) and `started` (handles leading zeros) flags.
- **Probability/expectation DP:** use `double[]` and iterate from known states outward; watch for unreachable states (divide-by-zero).
- **Prefer `int[][]` memo with a sentinel** over `HashMap<String,...>` — allocation and hashing kill performance.
- **Recursion depth:** top-down can hit ~10^4–10^5 stack frames; default JVM stack is ~512 KB. Run with `-Xss8m` or convert to bottom-up for large inputs.
