package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Resolving a stop's local service window to a real instant, and deciding how late it is.
 *
 * <p>The time-zone half is the part worth guarding: the window is a {@link LocalTime} with no date
 * and the arrival is an instant, so the whole answer depends on choosing the trip's planning date
 * in the company's zone. Get it wrong by one zone and every afternoon delivery in Peru reads as
 * breached, which is a screen nobody would trust twice.
 */
class StopServiceWindowTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);
    private static final LocalTime CLOSES_AT_14 = LocalTime.of(14, 0);

    /** 14:00 in Lima is 19:00 UTC - the offset the whole class turns on. */
    private static final OffsetDateTime WINDOW_END_UTC =
            OffsetDateTime.of(2026, 8, 21, 19, 0, 0, 0, ZoneOffset.UTC);

    @Nested
    @DisplayName("resolving the window")
    class Resolution {

        @Test
        @DisplayName("puts the local closing time on the trip's date, in the company's zone")
        void localTimeBecomesAnInstantInTheCompanyZone() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.PENDING, null, WINDOW_END_UTC.minusHours(2));

            assertThat(window.endsAt()).isEqualTo(WINDOW_END_UTC);
        }

        @Test
        @DisplayName("has no window, and therefore no lateness, when the stop declares none")
        void aStopWithoutAWindowCannotBeLate() {
            StopServiceWindow window = StopServiceWindow.of(DAY, null, LIMA,
                    StopExecutionStatus.PENDING, null, WINDOW_END_UTC.plusDays(2));

            assertThat(window.endsAt()).isNull();
            assertThat(window.minutesPastWindow()).isNull();
            assertThat(window.isPastWindow()).isFalse();
        }

        @Test
        @DisplayName("does not call a stop late merely because UTC has passed the local time")
        void theServerZoneNeverDecides() {
            // 15:00 UTC is 10:00 in Lima: four hours inside a window that closes at 14:00 local.
            OffsetDateTime now = OffsetDateTime.of(2026, 8, 21, 15, 0, 0, 0, ZoneOffset.UTC);

            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.PENDING, null, now);

            assertThat(window.isPastWindow()).isFalse();
        }
    }

    @Nested
    @DisplayName("a stop the vehicle has reached")
    class Arrived {

        @Test
        @DisplayName("is late by the gap between the window and the arrival")
        void latenessIsMeasuredToTheArrival() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.COMPLETED, WINDOW_END_UTC.plusMinutes(25),
                    WINDOW_END_UTC.plusHours(5));

            assertThat(window.minutesPastWindow()).isEqualTo(25L);
            assertThat(window.isPastWindow()).isTrue();
        }

        @Test
        @DisplayName("stops moving once the arrival is recorded, however late the screen is read")
        void aRecordedArrivalIsAFactAndDoesNotGrow() {
            StopServiceWindow atOnce = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.ARRIVED, WINDOW_END_UTC.plusMinutes(10),
                    WINDOW_END_UTC.plusMinutes(11));
            StopServiceWindow readLater = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.ARRIVED, WINDOW_END_UTC.plusMinutes(10),
                    WINDOW_END_UTC.plusDays(1));

            assertThat(atOnce.minutesPastWindow()).isEqualTo(10L);
            assertThat(readLater.minutesPastWindow()).isEqualTo(10L);
        }

        @Test
        @DisplayName("is not late when it arrived inside the window")
        void arrivingInsideTheWindowIsNotLateness() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.COMPLETED, WINDOW_END_UTC.minusHours(3),
                    WINDOW_END_UTC.plusHours(4));

            assertThat(window.endsAt()).isEqualTo(WINDOW_END_UTC);
            assertThat(window.minutesPastWindow()).isNull();
        }
    }

    @Nested
    @DisplayName("a stop nobody has reached")
    class NotArrived {

        @Test
        @DisplayName("is late against the clock once its window has closed")
        void anUnservedStopIsMeasuredAgainstNow() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.PENDING, null, WINDOW_END_UTC.plusMinutes(90));

            assertThat(window.minutesPastWindow()).isEqualTo(90L);
        }

        @Test
        @DisplayName("is not late while the window is still open")
        void beforeTheWindowClosesNothingIsWrong() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.IN_SERVICE, null, WINDOW_END_UTC.minusMinutes(1));

            assertThat(window.isPastWindow()).isFalse();
        }

        @Test
        @DisplayName("reports no lateness for a stop that was skipped without anybody going")
        void aSkippedStopHasNoLatenessToState() {
            StopServiceWindow window = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.SKIPPED, null, WINDOW_END_UTC.plusDays(1));

            assertThat(window.endsAt()).isEqualTo(WINDOW_END_UTC);
            assertThat(window.minutesPastWindow()).isNull();
        }

        @Test
        @DisplayName("reports no lateness for a failure nobody arrived at, and the gap for one they did")
        void failureIsMeasuredOnlyWhenSomebodyGotThere() {
            StopServiceWindow neverReached = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.FAILED, null, WINDOW_END_UTC.plusHours(2));
            StopServiceWindow turnedAway = StopServiceWindow.of(DAY, CLOSES_AT_14, LIMA,
                    StopExecutionStatus.FAILED, WINDOW_END_UTC.plusMinutes(15),
                    WINDOW_END_UTC.plusHours(2));

            assertThat(neverReached.minutesPastWindow()).isNull();
            assertThat(turnedAway.minutesPastWindow()).isEqualTo(15L);
        }
    }
}
