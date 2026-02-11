package com.stanmarek.examples.tesco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Given an integer array and k, return the k most frequent elements.
 *
 * Algorithm: Bucket Sort by frequency.
 *
 * Step 1 — Count frequencies with a HashMap.                        O(n)
 * Step 2 — Place each number into a bucket indexed by its frequency. O(n)
 *   The bucket array has size (n+1) because the max possible frequency
 *   of any element is n (the entire array is one repeated value).
 * Step 3 — Walk the buckets from highest index down, collecting      O(n)
 *   elements until we have k.
 *
 * Why bucket sort instead of a min-heap?
 * - A heap approach is O(n log k), but bucket sort achieves O(n) because
 *   frequencies are bounded by n, giving us a finite range to "sort" into.
 * - This satisfies the follow-up requirement: "better than O(n log n)".
 *
 * Time: O(n).  Space: O(n) for the frequency map and buckets.
 */
public class TescoTopKFrequent {

    @SuppressWarnings("unchecked")
    public static List<Integer> topKFrequent(int[] nums, int k) {
        // Step 1: count how many times each number appears
        Map<Integer, Integer> freq = new HashMap<>();
        for (int num : nums) {
            freq.merge(num, 1, Integer::sum);
        }

        // Step 2: bucket[i] holds all numbers that appear exactly i times.
        // Index 0 is unused (no element has frequency 0 if it exists in freq map).
        List<Integer>[] buckets = new List[nums.length + 1];
        for (var entry : freq.entrySet()) {
            int f = entry.getValue();
            if (buckets[f] == null) buckets[f] = new ArrayList<>();
            buckets[f].add(entry.getKey());
        }

        // Step 3: collect from highest frequency bucket downward
        List<Integer> result = new ArrayList<>();
        for (int i = buckets.length - 1; i >= 0 && result.size() < k; i--) {
            if (buckets[i] != null) {
                result.addAll(buckets[i]);
            }
        }
        return result.subList(0, k);
    }
}
