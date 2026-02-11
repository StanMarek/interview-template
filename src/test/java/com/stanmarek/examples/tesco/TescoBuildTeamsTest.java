package com.stanmarek.examples.tesco;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TescoBuildTeamsTest {

    @Test
    void exampleFromProblem() {
        int[] strengths = {4, 4, 2, 2, 5};
        int k = 2;
        int[] result = TescoBuildTeams.buildTeams(strengths, k);
        // Top 4 players: 5,4,4,2 → total=15
        // Best split: {5,2}=7 vs {4,4}=8 → diff=1
        assertThat(result[0]).isEqualTo(15);
        assertThat(result[1]).isEqualTo(1);
    }

    @Test
    void perfectSplit() {
        int[] strengths = {3, 3, 3, 3};
        int k = 2;
        int[] result = TescoBuildTeams.buildTeams(strengths, k);
        assertThat(result[0]).isEqualTo(12);
        assertThat(result[1]).isEqualTo(0);
    }

    @Test
    void singlePlayerTeams() {
        int[] strengths = {10, 5};
        int k = 1;
        int[] result = TescoBuildTeams.buildTeams(strengths, k);
        assertThat(result[0]).isEqualTo(15);
        assertThat(result[1]).isEqualTo(5);
    }

    @Test
    void selectsTopPlayers() {
        // Only top 4 (2*k) should be selected: 10,9,8,7
        int[] strengths = {1, 2, 7, 8, 9, 10};
        int k = 2;
        int[] result = TescoBuildTeams.buildTeams(strengths, k);
        // Top 4: {10,9,8,7}, total=34
        // Best split: {10,7}=17 vs {9,8}=17 → diff=0
        assertThat(result[0]).isEqualTo(34);
        assertThat(result[1]).isEqualTo(0);
    }

    @Test
    void oddTotalStrength() {
        int[] strengths = {5, 4, 3, 2};
        int k = 2;
        int[] result = TescoBuildTeams.buildTeams(strengths, k);
        // Top 4: {5,4,3,2}, total=14
        // Best: {5,2}=7 vs {4,3}=7 → diff=0
        assertThat(result[0]).isEqualTo(14);
        assertThat(result[1]).isEqualTo(0);
    }

    @Test
    void notEnoughPlayersThrows() {
        int[] strengths = {1, 2, 3};
        assertThatThrownBy(() -> TescoBuildTeams.buildTeams(strengths, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
