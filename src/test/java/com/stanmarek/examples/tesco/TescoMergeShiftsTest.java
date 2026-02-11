package com.stanmarek.examples.tesco;

import com.stanmarek.examples.tesco.TescoMergeShifts.Shift;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TescoMergeShiftsTest {

    @Test
    void exampleFromProblem() {
        var shifts = List.of(
                new Shift(10, 12),
                new Shift(12, 14),
                new Shift(15, 19)
        );
        var result = TescoMergeShifts.mergeShifts(shifts);
        assertThat(result).containsExactly(
                new Shift(10, 14),
                new Shift(15, 19)
        );
    }

    @Test
    void emptyInput() {
        assertThat(TescoMergeShifts.mergeShifts(List.of())).isEmpty();
    }

    @Test
    void singleShift() {
        var shifts = List.of(new Shift(9, 17));
        assertThat(TescoMergeShifts.mergeShifts(shifts)).containsExactly(new Shift(9, 17));
    }

    @Test
    void allContiguous() {
        var shifts = List.of(
                new Shift(8, 10),
                new Shift(10, 12),
                new Shift(12, 14)
        );
        assertThat(TescoMergeShifts.mergeShifts(shifts))
                .containsExactly(new Shift(8, 14));
    }

    @Test
    void overlappingShifts() {
        var shifts = List.of(
                new Shift(8, 13),
                new Shift(10, 15)
        );
        assertThat(TescoMergeShifts.mergeShifts(shifts))
                .containsExactly(new Shift(8, 15));
    }

    @Test
    void noOverlapOrTouch() {
        var shifts = List.of(
                new Shift(8, 10),
                new Shift(12, 14),
                new Shift(16, 18)
        );
        assertThat(TescoMergeShifts.mergeShifts(shifts)).containsExactly(
                new Shift(8, 10),
                new Shift(12, 14),
                new Shift(16, 18)
        );
    }

    @Test
    void unsortedInput() {
        var shifts = List.of(
                new Shift(15, 19),
                new Shift(10, 12),
                new Shift(12, 14)
        );
        assertThat(TescoMergeShifts.mergeShifts(shifts)).containsExactly(
                new Shift(10, 14),
                new Shift(15, 19)
        );
    }

    @Test
    void nestedShifts() {
        var shifts = List.of(
                new Shift(8, 18),
                new Shift(10, 12),
                new Shift(14, 16)
        );
        assertThat(TescoMergeShifts.mergeShifts(shifts))
                .containsExactly(new Shift(8, 18));
    }
}
