package com.stanmarek.examples.tesco;

import java.util.Arrays;
import java.util.List;

/**
 * Variation of Meeting Rooms II: find minimum number of days to schedule
 * all meetings without conflicts. Touching endpoints (e.g. (0,8),(8,10))
 * are NOT conflicts.
 *
 * Key insight: the minimum number of days equals the maximum number of
 * meetings happening at the same time — each concurrent meeting needs its own day.
 *
 * Algorithm: Sweep Line (event-based).
 * - Separate start and end times, sort them independently.
 * - Walk through events chronologically: a start means +1 concurrent meeting,
 *   an end means -1. Track the peak.
 * - Using < (strict) in the comparison handles the "touching is not a conflict" rule:
 *   if a meeting starts exactly when another ends, the end is processed first,
 *   so they don't count as overlapping.
 *
 * Why sweep line over a min-heap approach?
 * - Same O(n log n) time, but simpler code — no priority queue needed.
 * - Sorting two flat arrays is cache-friendly and fast in practice.
 *
 * Time: O(n log n) for sorting.  Space: O(n) for the two arrays.
 */
public class TescoMeetingDays {

    public record Interval(int start, int end) {}

    public static int minDays(List<Interval> intervals) {
        if (intervals.isEmpty()) return 0;

        int n = intervals.size();
        int[] starts = new int[n];
        int[] ends = new int[n];
        for (int i = 0; i < n; i++) {
            starts[i] = intervals.get(i).start();
            ends[i] = intervals.get(i).end();
        }

        // Sorting independently is valid because we only care about *how many*
        // meetings are active at each point, not *which* meetings they are.
        Arrays.sort(starts);
        Arrays.sort(ends);

        int maxOverlap = 0;
        int overlap = 0;
        int s = 0, e = 0;
        while (s < n) {
            if (starts[s] < ends[e]) {
                // A new meeting starts before the earliest running meeting ends — overlap grows.
                overlap++;
                maxOverlap = Math.max(maxOverlap, overlap);
                s++;
            } else {
                // A meeting ends before (or exactly when) the next one starts — overlap shrinks.
                // The >= case (starts[s] == ends[e]) means touching endpoints: not a conflict,
                // so we let the ending event fire first, reducing the count before the new start.
                overlap--;
                e++;
            }
        }
        return maxOverlap;
    }
}
