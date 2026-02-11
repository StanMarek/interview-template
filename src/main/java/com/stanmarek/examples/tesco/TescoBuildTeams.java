package com.stanmarek.examples.tesco;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Build two teams of size k from n players to maximize total strength
 * and minimize the strength difference between teams.
 * Returns [totalStrength, diffStrength].
 *
 * Two-phase approach:
 *
 * Phase 1 — Greedy selection (maximize total strength):
 *   Sort players by strength descending and pick the top 2k.
 *   Any other selection of 2k players would have a smaller or equal total,
 *   so this guarantees maximum total strength.
 *
 * Phase 2 — Balanced partition (minimize difference):
 *   Among the 2k selected players, split them into two groups of exactly k
 *   such that |sum(A) - sum(B)| is minimized.
 *   This is equivalent to: pick k elements whose sum is closest to total/2,
 *   because diff = |total - 2*sumA|.
 *
 *   We solve this with subset-sum DP:
 *   - dp[j] = set of all achievable sums using exactly j elements.
 *   - For each player value, iterate j from k down to 1 (0/1 knapsack trick
 *     to ensure each player is used at most once).
 *   - After processing all players, scan dp[k] for the sum closest to total/2.
 *
 * Why HashSet-based DP instead of a boolean[][] array?
 *   A boolean[k+1][totalSum+1] could be huge (strengths up to 1M, 500 players).
 *   HashSets store only reachable sums, which is much sparser in practice.
 *
 * Time: O(2k * k * |reachable sums|) — pseudo-polynomial, feasible for interview constraints.
 * Space: O(k * |reachable sums|).
 */
public class TescoBuildTeams {

    @SuppressWarnings("unchecked")
    public static int[] buildTeams(int[] strengths, int k) {
        int n = strengths.length;
        if (n < 2 * k) throw new IllegalArgumentException("Not enough players for two teams of size " + k);

        // Phase 1: pick the 2k strongest players (greedy — maximizes total)
        Integer[] indices = IntStream.range(0, n).boxed().toArray(Integer[]::new);
        Arrays.sort(indices, (a, b) -> Integer.compare(strengths[b], strengths[a]));

        int[] top = new int[2 * k];
        for (int i = 0; i < 2 * k; i++) {
            top[i] = strengths[indices[i]];
        }

        int total = 0;
        for (int s : top) total += s;

        // Phase 2: subset-sum DP to split 2k players into two balanced teams of k.
        // dp[j] = all achievable sums when picking exactly j players.
        Set<Integer>[] dp = new HashSet[k + 1];
        for (int j = 0; j <= k; j++) dp[j] = new HashSet<>();
        dp[0].add(0); // base case: 0 players selected, sum = 0

        for (int val : top) {
            // Iterate j downward (standard 0/1 knapsack trick):
            // ensures each player is counted at most once, because dp[j-1]
            // hasn't been updated with the current player yet when we read it.
            for (int j = k; j >= 1; j--) {
                for (int s : dp[j - 1].toArray(Integer[]::new)) {
                    dp[j].add(s + val);
                }
            }
        }

        // Find the team-A sum (of k players) that minimizes |total - 2*sumA|
        int bestDiff = Integer.MAX_VALUE;
        for (int s : dp[k]) {
            int diff = Math.abs(total - 2 * s);
            if (diff < bestDiff) {
                bestDiff = diff;
            }
        }

        return new int[]{total, bestDiff};
    }
}
