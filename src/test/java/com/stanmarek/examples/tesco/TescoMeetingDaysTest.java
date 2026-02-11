package com.stanmarek.examples.tesco;

import com.stanmarek.examples.tesco.TescoMeetingDays.Interval;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TescoMeetingDaysTest {

    @Test
    void example1_threeMeetingsTwoDays() {
        var intervals = List.of(
                new Interval(0, 40),
                new Interval(5, 10),
                new Interval(15, 20)
        );
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(2);
    }

    @Test
    void example2_singleMeeting() {
        var intervals = List.of(new Interval(4, 9));
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(1);
    }

    @Test
    void emptyInput() {
        assertThat(TescoMeetingDays.minDays(List.of())).isEqualTo(0);
    }

    @Test
    void touchingEndpointsNotConflict() {
        var intervals = List.of(
                new Interval(0, 8),
                new Interval(8, 10)
        );
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(1);
    }

    @Test
    void allOverlapping() {
        var intervals = List.of(
                new Interval(0, 10),
                new Interval(1, 9),
                new Interval(2, 8)
        );
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(3);
    }

    @Test
    void noOverlaps() {
        var intervals = List.of(
                new Interval(0, 5),
                new Interval(5, 10),
                new Interval(10, 15)
        );
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(1);
    }

    @Test
    void partialOverlap() {
        var intervals = List.of(
                new Interval(0, 10),
                new Interval(5, 15),
                new Interval(10, 20)
        );
        // At time 5-10: first two overlap (2 days), at time 10-15: second and third overlap (2 days)
        assertThat(TescoMeetingDays.minDays(intervals)).isEqualTo(2);
    }
}
