package com.stanmarek.examples.tesco;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TescoTopKFrequentTest {

    @Test
    void example1() {
        int[] nums = {1, 1, 1, 2, 2, 3};
        var result = TescoTopKFrequent.topKFrequent(nums, 2);
        assertThat(result).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void example2_singleElement() {
        int[] nums = {1};
        var result = TescoTopKFrequent.topKFrequent(nums, 1);
        assertThat(result).containsExactly(1);
    }

    @Test
    void example3_tiedFrequencies() {
        int[] nums = {1, 2, 1, 2, 1, 2, 3, 1, 3, 2};
        var result = TescoTopKFrequent.topKFrequent(nums, 2);
        assertThat(result).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void allSameFrequency() {
        int[] nums = {1, 2, 3};
        var result = TescoTopKFrequent.topKFrequent(nums, 2);
        assertThat(result).hasSize(2);
        assertThat(result).isSubsetOf(1, 2, 3);
    }

    @Test
    void kEqualsUniqueCount() {
        int[] nums = {5, 5, 3, 3, 1};
        var result = TescoTopKFrequent.topKFrequent(nums, 3);
        assertThat(result).containsExactlyInAnyOrder(5, 3, 1);
    }

    @Test
    void negativeNumbers() {
        int[] nums = {-1, -1, -2, -2, -2, 3};
        var result = TescoTopKFrequent.topKFrequent(nums, 1);
        assertThat(result).containsExactly(-2);
    }
}
