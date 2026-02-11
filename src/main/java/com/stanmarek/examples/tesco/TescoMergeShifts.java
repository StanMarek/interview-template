package com.stanmarek.examples.tesco;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merge contiguous or overlapping colleague shifts.
 * Touching endpoints (e.g. 10-12 and 12-14) ARE merged (unlike the meeting days problem).
 *
 * Algorithm: Sort + Linear Scan.
 *
 * 1. Sort shifts by start time.
 *    After sorting, any shift that can be merged with the current running shift
 *    must be the immediate next one — no later shift can "bridge back" to an earlier one.
 *
 * 2. Walk through sorted shifts, maintaining a running [currentStart, currentEnd]:
 *    - If the next shift starts at or before currentEnd, it overlaps or touches —
 *      extend currentEnd to max(currentEnd, next.end).
 *    - Otherwise, the running shift is complete — emit it, start a new one.
 *
 * Why <= instead of < for the overlap check?
 *   The problem states that contiguous shifts (e.g. Bakery 10-12, Checkout 12-14)
 *   should merge. So start == currentEnd counts as touching, not a gap.
 *
 * Time: O(n log n) for sorting.  Space: O(n) for the sorted copy and result.
 */
public class TescoMergeShifts {

    public record Shift(int start, int end) {}

    public static List<Shift> mergeShifts(List<Shift> shifts) {
        if (shifts.isEmpty()) return List.of();

        // Sort by start time so overlapping/contiguous shifts become adjacent
        List<Shift> sorted = shifts.stream()
                .sorted(Comparator.comparingInt(Shift::start))
                .toList();

        List<Shift> merged = new ArrayList<>();
        int currentStart = sorted.getFirst().start();
        int currentEnd = sorted.getFirst().end();

        for (int i = 1; i < sorted.size(); i++) {
            Shift s = sorted.get(i);
            if (s.start() <= currentEnd) {
                // Overlapping or touching — extend the current shift.
                // Use max() because the current shift might already extend past this one
                // (e.g. current=[8,18], next=[10,12] — the nested shift doesn't extend anything).
                currentEnd = Math.max(currentEnd, s.end());
            } else {
                // Gap found — finalize the current shift and start a new one
                merged.add(new Shift(currentStart, currentEnd));
                currentStart = s.start();
                currentEnd = s.end();
            }
        }
        // Don't forget the last running shift
        merged.add(new Shift(currentStart, currentEnd));

        return merged;
    }
}
