package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The departure-delay rule, which is the one place the whole control tower decides what "late"
 * means.
 *
 * <p>Worth exhaustive tests because it is the only part of that screen that can be wrong in a way
 * nobody notices: a mis-classified shipment still renders, still has a number beside it, and still
 * looks like an answer. The cases below are written as the six situations an operator is in, not as
 * branches of the method, so the file keeps meaning something if the implementation is rewritten.
 */
class DepartureDelayTest {

    private static final OffsetDateTime PLANNED = OffsetDateTime.of(2026, 8, 21, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NINE = PLANNED.plusHours(1);

    @Nested
    @DisplayName("a trip that has already left")
    class Departed {

        @Test
        @DisplayName("is late by the gap between the plan and the departure")
        void lateByTheMeasuredGap() {
            DepartureDelay delay = DepartureDelay.of(
                    TripStatus.IN_TRANSIT, PLANNED, PLANNED.plusMinutes(37), NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.LATE);
            assertThat(delay.minutes()).isEqualTo(37L);
            assertThat(delay.isDelayed()).isTrue();
        }

        @Test
        @DisplayName("is late by one minute when it left one minute late, with no grace period")
        void noToleranceIsApplied() {
            DepartureDelay delay = DepartureDelay.of(
                    TripStatus.IN_TRANSIT, PLANNED, PLANNED.plusMinutes(1), NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.LATE);
            assertThat(delay.minutes()).isEqualTo(1L);
        }

        @Test
        @DisplayName("is on time when it left exactly on the planned instant")
        void departingExactlyOnPlanIsOnTime() {
            DepartureDelay delay = DepartureDelay.of(TripStatus.IN_TRANSIT, PLANNED, PLANNED, NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.ON_TIME);
            assertThat(delay.minutes()).isNull();
        }

        @Test
        @DisplayName("is on time, not early, when it left ahead of the plan")
        void earlyIsReportedAsOnTime() {
            DepartureDelay delay = DepartureDelay.of(
                    TripStatus.IN_TRANSIT, PLANNED, PLANNED.minusMinutes(20), NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.ON_TIME);
            assertThat(delay.minutes()).isNull();
        }

        @Test
        @DisplayName("keeps its verdict after completion, because both instants are recorded facts")
        void completionDoesNotChangeTheVerdict() {
            DepartureDelay delay = DepartureDelay.of(
                    TripStatus.COMPLETED, PLANNED, PLANNED.plusMinutes(45), NINE.plusHours(6));

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.LATE);
            assertThat(delay.minutes()).isEqualTo(45L);
        }
    }

    @Nested
    @DisplayName("a trip that has not left")
    class AwaitingDeparture {

        @Test
        @DisplayName("is overdue once its planned instant has passed, measured against now")
        void overdueGrowsWithTheClock() {
            DepartureDelay delay = DepartureDelay.of(TripStatus.READY_FOR_DISPATCH, PLANNED, null, NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.OVERDUE);
            assertThat(delay.minutes()).isEqualTo(60L);
            assertThat(delay.isDelayed()).isTrue();
        }

        @Test
        @DisplayName("is still scheduled before its planned instant")
        void beforeThePlanNothingIsWrong() {
            DepartureDelay delay = DepartureDelay.of(
                    TripStatus.CONFIRMED, PLANNED, null, PLANNED.minusMinutes(30));

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.SCHEDULED);
            assertThat(delay.minutes()).isNull();
            assertThat(delay.isDelayed()).isFalse();
        }

        @Test
        @DisplayName("is overdue while it is still a draft, which is the worst version of late")
        void draftsAreNotExemptFromBeingOverdue() {
            DepartureDelay delay = DepartureDelay.of(TripStatus.DRAFT, PLANNED, null, NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.OVERDUE);
        }

        @Test
        @DisplayName("names the same three states the aggregate count is asked for")
        void theAwaitingSetIsTheOneTheCountUses() {
            assertThat(DepartureDelay.AWAITING_DEPARTURE).containsExactlyInAnyOrder(
                    TripStatus.DRAFT, TripStatus.CONFIRMED, TripStatus.READY_FOR_DISPATCH);
        }
    }

    @Nested
    @DisplayName("a trip there is nothing to judge")
    class NotJudged {

        @Test
        @DisplayName("reports no schedule when no departure was ever planned")
        void withoutAPlanThereIsNoDelay() {
            DepartureDelay delay = DepartureDelay.of(TripStatus.DRAFT, null, null, NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.NOT_SCHEDULED);
            assertThat(delay.minutes()).isNull();
            assertThat(delay.isDelayed()).isFalse();
        }

        @Test
        @DisplayName("never calls a cancelled trip late, however long ago it was due")
        void cancellationEndsTheQuestion() {
            DepartureDelay delay = DepartureDelay.of(TripStatus.CANCELLED, PLANNED, null, NINE.plusDays(3));

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.NOT_APPLICABLE);
            assertThat(delay.minutes()).isNull();
            assertThat(delay.isDelayed()).isFalse();
        }

        @Test
        @DisplayName("refuses to grow a delay on a trip that is already out with no departure recorded")
        void anImpossibleRowIsNotDisplayedAsGrowingLateness() {
            // Trip.dispatch always writes actualDepartureAt, so this row cannot be produced by the
            // application. If one ever exists, the honest answer is "nothing to judge" rather than
            // a delay counter ticking upwards on a truck that is demonstrably on the road.
            DepartureDelay delay = DepartureDelay.of(TripStatus.IN_TRANSIT, PLANNED, null, NINE);

            assertThat(delay.timeliness()).isEqualTo(DepartureTimeliness.NOT_APPLICABLE);
            assertThat(delay.minutes()).isNull();
        }
    }

    @Nested
    @DisplayName("the vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("counts exactly the two situations a dispatcher acts on as delayed")
        void onlyLateAndOverdueAreProblems() {
            for (DepartureTimeliness timeliness : DepartureTimeliness.values()) {
                boolean expected = timeliness == DepartureTimeliness.LATE
                        || timeliness == DepartureTimeliness.OVERDUE;
                assertThat(timeliness.isDelayed()).as("%s", timeliness).isEqualTo(expected);
            }
        }
    }
}
