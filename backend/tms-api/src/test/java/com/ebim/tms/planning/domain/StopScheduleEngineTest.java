package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.domain.StopScheduleEngine.Leg;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The stop schedule (migration V43, ADR-011).
 *
 * <p>A pure function, so all of this runs without a database, a clock or a Spring context - which
 * is the point of building it that way. An arrival time that cannot be reproduced from its inputs
 * cannot be defended when somebody disputes it, and a test that needs Docker to check the
 * arithmetic is a test nobody runs while they are changing the arithmetic.
 *
 * <p>The three rules that matter each have their own nest below. The arithmetic gets one test.
 */
class StopScheduleEngineTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-09-07T13:00:00Z"); // 08:00 Lima

    private static Leg measured(int sequence, long travelMinutes, int serviceMinutes) {
        return new Leg(sequence, travelMinutes, EtaSource.MEASURED_ROUTE, serviceMinutes, null, null);
    }

    @Nested
    @DisplayName("the arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("each stop starts from the previous one's departure, service time included")
        void walksTheRun() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    measured(1, 60, 30),
                    measured(2, 45, 15)));

            assertThat(schedule.get(0).arrivalAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T14:00:00Z"));
            assertThat(schedule.get(0).departureAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T14:30:00Z"));
            // 14:30 + 45 minutes driving, not 14:00 + 45: the service time is part of the run.
            assertThat(schedule.get(1).arrivalAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T15:15:00Z"));
            assertThat(schedule.get(1).departureAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T15:30:00Z"));
        }

        @Test
        @DisplayName("a site with no service time configured has said nothing, and costs nothing")
        void zeroServiceTimeIsLegitimate() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(measured(1, 60, 0)));

            assertThat(schedule.getFirst().departureAt()).isEqualTo(schedule.getFirst().arrivalAt());
        }

        @Test
        @DisplayName("no legs, no schedule, no exception")
        void emptyRun() {
            assertThat(StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("rule 1: an unmeasurable leg ends the chain")
    class TheChain {

        /**
         * The rule this whole engine is judged by. A schedule that absorbed one missing leg would
         * show plausible arrival times of which several are wrong, and nothing on the board would
         * say which.
         */
        @Test
        @DisplayName("the unmeasurable stop and every stop after it have no estimate")
        void oneMissingLegLosesTheRest() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    measured(1, 60, 30),
                    new Leg(2, null, null, 20, null, null),
                    measured(3, 30, 10)));

            assertThat(schedule.get(0).isScheduled()).isTrue();
            assertThat(schedule.get(1).isScheduled()).isFalse();
            // Not "60 + 30 from stop 1": the vehicle's position in time is unknown from stop 2 on,
            // and a later measured leg does not restore knowledge of where it started.
            assertThat(schedule.get(2).isScheduled()).isFalse();
            assertThat(schedule.get(2).arrivalAt()).isNull();
        }

        @Test
        @DisplayName("an unscheduled stop carries no source and no window verdict either")
        void unscheduledSaysNothingElse() {
            StopSchedule unscheduled = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    new Leg(1, null, null, 0, LocalTime.of(6, 0), LocalTime.of(7, 0)))).getFirst();

            assertThat(unscheduled.source()).isNull();
            // False and not true: a stop with no estimate has not been shown to miss anything, and
            // a warning nobody can act on is worse than none.
            assertThat(unscheduled.missesWindow()).isFalse();
            assertThat(unscheduled.waitMinutes()).isZero();
        }

        @Test
        @DisplayName("a first leg that cannot be measured loses the whole run, without pretending otherwise")
        void brokenAtTheStart() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    new Leg(1, null, null, 0, null, null),
                    measured(2, 30, 0)));

            assertThat(schedule).allSatisfy(stop -> assertThat(stop.isScheduled()).isFalse());
        }
    }

    @Nested
    @DisplayName("rule 2: provenance degrades and never upgrades")
    class Provenance {

        @Test
        @DisplayName("every leg measured, every stop MEASURED_ROUTE")
        void allMeasured() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    measured(1, 60, 0), measured(2, 60, 0)));

            assertThat(schedule).allSatisfy(stop ->
                    assertThat(stop.source()).isEqualTo(EtaSource.MEASURED_ROUTE));
        }

        /**
         * The JOB 04 lesson, encoded. A straight-line leg early in the run is what stops 2 and 3
         * are genuinely built on, and a measured leg 3 does not repair the estimate it was added
         * to.
         */
        @Test
        @DisplayName("one straight-line leg makes every later stop FALLBACK, including measured ones")
        void fallbackPropagatesForward() {
            List<StopSchedule> schedule = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    measured(1, 60, 0),
                    new Leg(2, 60L, EtaSource.FALLBACK, 0, null, null),
                    measured(3, 60, 0)));

            assertThat(schedule.get(0).source()).isEqualTo(EtaSource.MEASURED_ROUTE);
            assertThat(schedule.get(1).source()).isEqualTo(EtaSource.FALLBACK);
            assertThat(schedule.get(2).source()).isEqualTo(EtaSource.FALLBACK);
        }

        @Test
        @DisplayName("a leg that names no source is treated as the weaker one")
        void unknownSourceIsFallback() {
            StopSchedule stop = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    new Leg(1, 60L, null, 0, null, null))).getFirst();

            assertThat(stop.source()).isEqualTo(EtaSource.FALLBACK);
        }
    }

    @Nested
    @DisplayName("rule 3: a window is never made to fit")
    class Windows {

        /** 08:00 Lima departure, 60 minutes driving, so 09:00 Lima at a site that opens at 10:00. */
        @Test
        @DisplayName("arriving early means waiting, and the wait pushes the next leg")
        void earlyArrivalWaits() {
            StopSchedule stop = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    new Leg(1, 60L, EtaSource.MEASURED_ROUTE, 30, LocalTime.of(10, 0), LocalTime.of(18, 0))))
                    .getFirst();

            // The arrival is reported as computed - the truck really is there at 09:00.
            assertThat(stop.arrivalAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T14:00:00Z"));
            assertThat(stop.waitMinutes()).isEqualTo(60);
            // Service starts when the site opens, so departure is 10:00 + 30 minutes, not 09:30.
            assertThat(stop.departureAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T15:30:00Z"));
            assertThat(stop.missesWindow()).isFalse();
        }

        @Test
        @DisplayName("arriving after closing is reported as computed, with the flag raised")
        void lateArrivalIsFlaggedNotMoved() {
            StopSchedule stop = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(
                    new Leg(1, 60L, EtaSource.MEASURED_ROUTE, 0, LocalTime.of(6, 0), LocalTime.of(8, 30))))
                    .getFirst();

            assertThat(stop.missesWindow()).isTrue();
            // NOT moved to the next morning, and not left blank. Quietly rescheduling would turn a
            // route that does not work into one that appears to.
            assertThat(stop.arrivalAt()).isEqualTo(OffsetDateTime.parse("2026-09-07T14:00:00Z"));
        }

        /**
         * Judged on the arrival, not on the wait-adjusted service start. A vehicle turning up after
         * closing has missed the window; waiting until tomorrow morning is not what a schedule
         * means.
         */
        @Test
        @DisplayName("a stop with no window can never miss one")
        void noWindowNoVerdict() {
            StopSchedule stop = StopScheduleEngine.schedule(DEPARTURE, LIMA, List.of(measured(1, 600, 0)))
                    .getFirst();

            assertThat(stop.missesWindow()).isFalse();
            assertThat(stop.waitMinutes()).isZero();
        }

        /**
         * The reason the window is resolved against the arrival's own local date rather than the
         * shipment's planning date: a run that crosses midnight compares against the stop's next
         * morning, and a planning date would put the window a day behind the truck.
         */
        @Test
        @DisplayName("a run that crosses midnight compares against the day it actually arrives")
        void windowFollowsTheVehicleAcrossMidnight() {
            OffsetDateTime lateDeparture = OffsetDateTime.parse("2026-09-08T03:00:00Z"); // 22:00 Lima, the 7th
            StopSchedule stop = StopScheduleEngine.schedule(lateDeparture, LIMA, List.of(
                    new Leg(1, 240L, EtaSource.MEASURED_ROUTE, 0, LocalTime.of(6, 0), LocalTime.of(18, 0))))
                    .getFirst();

            // Arrives 02:00 Lima on the 8th. The window is the 8th's 06:00-18:00, so it waits four
            // hours - it has not missed the 7th's window, which is what a planning-date comparison
            // would have concluded.
            assertThat(stop.missesWindow()).isFalse();
            assertThat(stop.waitMinutes()).isEqualTo(240);
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        /** No clock, no randomness, no repository. The same inputs give the same schedule, always. */
        @Test
        @DisplayName("the same inputs give the same answer")
        void isDeterministic() {
            List<Leg> legs = List.of(
                    measured(1, 60, 30),
                    new Leg(2, 45L, EtaSource.FALLBACK, 15, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                    new Leg(3, null, null, 0, null, null));

            assertThat(StopScheduleEngine.schedule(DEPARTURE, LIMA, legs))
                    .isEqualTo(StopScheduleEngine.schedule(DEPARTURE, LIMA, legs));
        }
    }
}
