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

**Top-Down (Memoization):** Recursive + cache. Start from the main problem and recurse down.

```java
Map<String, Integer> memo = new HashMap<>();
public int solve(int i, int j) {
    String key = i + "," + j;
    if (memo.containsKey(key)) return memo.get(key);
    if (/* base case */) return /* value */;
    int result = /* recursive formula */;
    memo.put(key, result);
    return result;
}
```

**Bottom-Up (Tabulation):** Iterative + array. More efficient (no recursion overhead).

```java
int[] dp = new int[n + 1];
dp[0] = baseCase;
for (int i = 1; i <= n; i++) {
    dp[i] = /* formula using dp[i-1], dp[i-2], etc. */;
}
return dp[n];
```

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

// O(n log n) with patience sorting
public int lengthOfLIS_fast(int[] nums) {
    List<Integer> tails = new ArrayList<>();
    for (int num : nums) {
        int pos = Collections.binarySearch(tails, num);
        if (pos < 0) pos = -(pos + 1);
        if (pos == tails.size()) tails.add(num);
        else tails.set(pos, num);
    }
    return tails.size();
}
```

### 6. Interval / Palindrome DP

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

## Common Interview Problems

**Easy:** Climbing Stairs, House Robber, Maximum Subarray, Min Cost Climbing Stairs, Pascal's Triangle.

**Medium:** Coin Change, Longest Increasing Subsequence, Longest Common Subsequence, Word Break, Unique Paths, Decode Ways, Partition Equal Subset Sum, Target Sum, House Robber II, Jump Game, Edit Distance.

**Hard:** Regular Expression Matching, Wildcard Matching, Burst Balloons, Longest Valid Parentheses, Palindrome Partitioning II, Distinct Subsequences, Interleaving String.

## Tips and Pitfalls

- **Start with brute-force recursion**, then add memoization, then convert to bottom-up if needed.
- **State definition is everything.** If your DP doesn't work, you probably defined the state wrong.
- **Space optimization:** If `dp[i]` only depends on `dp[i-1]` and `dp[i-2]`, you only need two variables.
- **0/1 knapsack iterates capacity backwards** (to avoid using an item twice). Unbounded knapsack iterates forwards.
- **Watch for off-by-one errors** in base cases and loop bounds.
- **When in doubt, draw the table.** Fill in a small example by hand before coding.
- **DP on strings:** Almost always uses `dp[i][j]` where `i` and `j` index into the two strings.
- **Bitmask DP** for problems with small sets (n ≤ 20): `dp[mask]` where mask is a bitmask of selected items.
