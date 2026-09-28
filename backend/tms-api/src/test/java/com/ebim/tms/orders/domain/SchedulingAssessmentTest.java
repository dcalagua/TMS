package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.CalendarVerdict;
import com.ebim.tms.shared.reference.RouteResolution;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ADR-014 sections 3-6: eligibility aggregation, frequency composition, cutoff, requiresOverride. */
class SchedulingAssessmentTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final LocalDate DISPATCH = LocalDate.of(2026, 9, 30);
    /** Two days before dispatch, 10:00 Lima: comfortably inside any window below unless stated. */
    private static final OffsetDateTime EARLY = OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.ofHours(-5));
    private static final OffsetDateTime LATE = OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.ofHours(-5));

    private static final RouteResolution RESOLVED = new RouteResolution(RouteResolution.Status.RESOLVED,
            List.of(new RouteResolution.ResolvedRoute(UUID.randomUUID(), "R-SUR", "Lima Sur", UUID.randomUUID(), 0)));

    private static final CalendarVerdict OFF = CalendarVerdict.NOT_CONFIGURED;

    @Test
    @DisplayName("nothing to say: ELIGIBLE with a resolved route and no calendars")
    void eligible() {
        SchedulingAssessment assessment = assess(facts(RESOLVED, OFF, OFF), EARLY);

        assertThat(assessment.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
        assertThat(assessment.reasons()).isEmpty();
        assertThat(assessment.releaseDeadline()).isNull();
        assertThat(assessment.releasableWithoutReason()).isTrue();
        assertThat(assessment.scheduledDispatchDate()).isEqualTo(DISPATCH);
    }

    @Nested
    @DisplayName("route resolution")
    class Routes {

        @Test
        @DisplayName("NOT_CONFIGURED is an informative WARNING that needs no reason")
        void notConfiguredIsInformative() {
            SchedulingAssessment assessment = assess(facts(RouteResolution.NOT_CONFIGURED, OFF, OFF), EARLY);

            assertThat(assessment.eligibility()).isEqualTo(Eligibility.WARNING);
            assertThat(codes(assessment)).containsExactly(SchedulingReasonCode.ROUTE_NOT_CONFIGURED);
            assertThat(assessment.requiresOverride()).isFalse();
            assertThat(assessment.releasableWithoutReason()).isTrue();
        }

        @Test
        @DisplayName("NOT_FOUND and AMBIGUOUS block")
        void notFoundAndAmbiguousBlock() {
            RouteResolution notFound = new RouteResolution(RouteResolution.Status.NOT_FOUND, List.of());
            RouteResolution ambiguous = new RouteResolution(RouteResolution.Status.AMBIGUOUS, List.of(
                    new RouteResolution.ResolvedRoute(UUID.randomUUID(), "R-A", "A", null, 0),
                    new RouteResolution.ResolvedRoute(UUID.randomUUID(), "R-B", "B", null, 2)));

            assertThat(codes(assess(facts(notFound, OFF, OFF), EARLY))).containsExactly(SchedulingReasonCode.ROUTE_NOT_FOUND);
            SchedulingAssessment ambiguity = assess(facts(ambiguous, OFF, OFF), EARLY);
            assertThat(ambiguity.eligibility()).isEqualTo(Eligibility.BLOCKED);
            assertThat(ambiguity.reasons().get(0).detail()).contains("R-A", "R-B");
            assertThat(ambiguity.releasableWithoutReason()).isFalse();
        }

        @Test
        @DisplayName("the route's frequency is ignored unless exactly one route resolved")
        void routeFrequencyOnlyWhenResolved() {
            CalendarVerdict idleRoute = CalendarVerdict.notRunning("FR-LMV");

            assertThat(codes(assess(facts(RouteResolution.NOT_CONFIGURED, OFF, idleRoute), EARLY)))
                    .containsExactly(SchedulingReasonCode.ROUTE_NOT_CONFIGURED);
        }
    }

    @Nested
    @DisplayName("blocking facts")
    class Blocking {

        @Test
        @DisplayName("missing origin, destination, capacity and an active hold all block, and blocking wins")
        void everyBlockingCode() {
            SchedulingAssessment.Facts facts = new SchedulingAssessment.Facts(DISPATCH, false, false, false, 2,
                    RouteResolution.NOT_CONFIGURED, OFF, OFF, LIMA);

            SchedulingAssessment assessment = SchedulingAssessment.assess(facts, EARLY);

            assertThat(assessment.eligibility()).isEqualTo(Eligibility.BLOCKED);
            assertThat(codes(assessment)).containsExactly(SchedulingReasonCode.MISSING_ORIGIN,
                    SchedulingReasonCode.MISSING_DESTINATION, SchedulingReasonCode.MISSING_CAPACITY,
                    SchedulingReasonCode.ACTIVE_BLOCKING_HOLD, SchedulingReasonCode.ROUTE_NOT_CONFIGURED);
            assertThat(assessment.reasons().get(3).detail()).contains("2 active blocking holds");
        }

        @Test
        @DisplayName("a missing destination's calendar is not judged on top of it")
        void missingDestinationSkipsItsCalendar() {
            SchedulingAssessment.Facts facts = new SchedulingAssessment.Facts(DISPATCH, true, false, true, 0,
                    RESOLVED, CalendarVerdict.notRunning(null), OFF, LIMA);

            assertThat(codes(SchedulingAssessment.assess(facts, EARLY)))
                    .containsExactly(SchedulingReasonCode.MISSING_DESTINATION);
        }
    }

    @Nested
    @DisplayName("frequency composition (section 5)")
    class Frequencies {

        @Test
        @DisplayName("destination only: its calendar decides")
        void destinationOnly() {
            assertThat(assess(facts(RESOLVED, CalendarVerdict.running("FR-LMV", null, null), OFF), EARLY).eligibility())
                    .isEqualTo(Eligibility.ELIGIBLE);
            SchedulingAssessment idle = assess(facts(RESOLVED, CalendarVerdict.notRunning("FR-LMV"), OFF), EARLY);
            assertThat(codes(idle)).containsExactly(SchedulingReasonCode.FREQUENCY_OVERRIDE);
            assertThat(idle.requiresOverride()).isTrue();
            assertThat(idle.locationFrequencyCode()).isEqualTo("FR-LMV");
        }

        @Test
        @DisplayName("route only: its frequency decides")
        void routeOnly() {
            SchedulingAssessment idle = assess(facts(RESOLVED, OFF, CalendarVerdict.notRunning("FR-MJ")), EARLY);

            assertThat(codes(idle)).containsExactly(SchedulingReasonCode.FREQUENCY_OVERRIDE);
            assertThat(idle.reasons().get(0).detail()).contains("route's frequency (FR-MJ)");
            assertThat(idle.routeFrequencyCode()).isEqualTo("FR-MJ");
            assertThat(assess(facts(RESOLVED, OFF, CalendarVerdict.running("FR-MJ", null, null)), EARLY).eligibility())
                    .isEqualTo(Eligibility.ELIGIBLE);
        }

        @Test
        @DisplayName("both: both must permit, and one refusal is enough")
        void bothMustPermit() {
            SchedulingAssessment oneIdle = assess(facts(RESOLVED, CalendarVerdict.running("FR-LMV", null, null),
                    CalendarVerdict.notRunning("FR-MJ")), EARLY);
            assertThat(codes(oneIdle)).containsExactly(SchedulingReasonCode.FREQUENCY_OVERRIDE);

            SchedulingAssessment bothIdle = assess(facts(RESOLVED, CalendarVerdict.notRunning("FR-LMV"),
                    CalendarVerdict.notRunning("FR-MJ")), EARLY);
            assertThat(codes(bothIdle)).containsExactly(SchedulingReasonCode.FREQUENCY_OVERRIDE);
            assertThat(bothIdle.reasons().get(0).detail()).contains("destination's calendar", "route's frequency");
        }

        @Test
        @DisplayName("none: serviceable with no deadline, so neither warning can arise")
        void none() {
            SchedulingAssessment assessment = assess(facts(RESOLVED, OFF, OFF), LATE.plusDays(30));

            assertThat(assessment.eligibility()).isEqualTo(Eligibility.ELIGIBLE);
            assertThat(assessment.releaseDeadline()).isNull();
        }
    }

    @Nested
    @DisplayName("cutoff (section 3)")
    class Cutoff {

        @Test
        @DisplayName("past the deadline is CUTOFF_MISSED, which requires a reason")
        void cutoffMissed() {
            CalendarVerdict destination = CalendarVerdict.running("FR-LMV", LocalTime.of(16, 0), 1);

            assertThat(assess(facts(RESOLVED, destination, OFF), EARLY).eligibility()).isEqualTo(Eligibility.ELIGIBLE);
            SchedulingAssessment late = assess(facts(RESOLVED, destination, OFF), LATE);
            assertThat(codes(late)).containsExactly(SchedulingReasonCode.CUTOFF_MISSED);
            assertThat(late.requiresOverride()).isTrue();
            assertThat(late.releasableWithoutReason()).isFalse();
            assertThat(late.releaseDeadline().at())
                    .isEqualTo(OffsetDateTime.of(2026, 9, 29, 16, 0, 0, 0, ZoneOffset.ofHours(-5)));
        }

        @Test
        @DisplayName("with two calendars the earlier deadline governs")
        void earlierDeadlineGoverns() {
            CalendarVerdict destination = CalendarVerdict.running("FR-LMV", LocalTime.of(18, 0), 0);
            CalendarVerdict route = CalendarVerdict.running("FR-MJ", LocalTime.of(9, 0), 1);

            SchedulingAssessment assessment = assess(facts(RESOLVED, destination, route), EARLY);

            assertThat(assessment.releaseDeadline().at())
                    .isEqualTo(OffsetDateTime.of(2026, 9, 29, 9, 0, 0, 0, ZoneOffset.ofHours(-5)));
            OffsetDateTime between = OffsetDateTime.of(2026, 9, 29, 12, 0, 0, 0, ZoneOffset.ofHours(-5));
            assertThat(codes(assess(facts(RESOLVED, destination, route), between)))
                    .containsExactly(SchedulingReasonCode.CUTOFF_MISSED);
        }

        @Test
        @DisplayName("a calendar that does not serve the date yields no deadline")
        void idleCalendarHasNoDeadline() {
            SchedulingAssessment assessment = assess(facts(RESOLVED, CalendarVerdict.notRunning("FR-LMV"), OFF), LATE);

            assertThat(assessment.releaseDeadline()).isNull();
            assertThat(codes(assessment)).containsExactly(SchedulingReasonCode.FREQUENCY_OVERRIDE);
        }

        @Test
        @DisplayName("an override warning and ROUTE_NOT_CONFIGURED together still require a reason")
        void mixedWarnings() {
            CalendarVerdict destination = CalendarVerdict.running("FR-LMV", LocalTime.of(16, 0), 1);

            SchedulingAssessment assessment = assess(facts(RouteResolution.NOT_CONFIGURED, destination, OFF), LATE);

            assertThat(assessment.eligibility()).isEqualTo(Eligibility.WARNING);
            assertThat(codes(assessment)).containsExactly(SchedulingReasonCode.ROUTE_NOT_CONFIGURED,
                    SchedulingReasonCode.CUTOFF_MISSED);
            assertThat(assessment.requiresOverride()).isTrue();
        }
    }

    private static SchedulingAssessment.Facts facts(RouteResolution route, CalendarVerdict destination,
            CalendarVerdict routeCalendar) {
        return new SchedulingAssessment.Facts(DISPATCH, true, true, true, 0, route, destination, routeCalendar, LIMA);
    }

    private static SchedulingAssessment assess(SchedulingAssessment.Facts facts, OffsetDateTime now) {
        return SchedulingAssessment.assess(facts, now);
    }

    private static List<SchedulingReasonCode> codes(SchedulingAssessment assessment) {
        return assessment.reasons().stream().map(SchedulingReason::code).toList();
    }
}
