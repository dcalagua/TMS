package com.ebim.tms.orders.domain;

import com.ebim.tms.shared.reference.CalendarVerdict;
import com.ebim.tms.shared.reference.RouteResolution;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether one order may be released, and why (ADR-014 section 4) - a pure function of facts the
 * caller has already resolved, so every rule in the ADR is provable without a database
 * ({@code SchedulingAssessmentTest}). {@code OrderSchedulingService} loads the facts in batches.
 *
 * @param scheduledDispatchDate {@code service_date}, formalised as the dispatch date (section 2)
 * @param releaseDeadline       the governing deadline, or null when no calendar applies
 * @param locationFrequencyCode the destination frequency that serves the date, or a configured one
 *                              that does not
 * @param routeFrequencyCode    the resolved route's frequency, when it has one
 */
public record SchedulingAssessment(
        Eligibility eligibility,
        List<SchedulingReason> reasons,
        LocalDate scheduledDispatchDate,
        ReleaseDeadline releaseDeadline,
        RouteResolution routeResolution,
        String locationFrequencyCode,
        String routeFrequencyCode) {

    public SchedulingAssessment {
        reasons = List.copyOf(reasons);
    }

    /**
     * Everything the rule looks at.
     *
     * @param destinationCalendar the destination's calendar on the dispatch date
     * @param routeCalendar       the resolved route's frequency on that date;
     *                            {@link CalendarVerdict#NOT_CONFIGURED} when the route has none or
     *                            did not resolve
     */
    public record Facts(
            LocalDate serviceDate,
            boolean originUsable,
            boolean destinationUsable,
            boolean hasCapacity,
            long activeBlockingHolds,
            RouteResolution routeResolution,
            CalendarVerdict destinationCalendar,
            CalendarVerdict routeCalendar,
            ZoneId zone) {
    }

    public static SchedulingAssessment assess(Facts facts, OffsetDateTime now) {
        List<SchedulingReason> reasons = new ArrayList<>();

        if (!facts.originUsable()) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.MISSING_ORIGIN,
                    "The origin is not an active location with the ORIGIN role."));
        }
        if (!facts.destinationUsable()) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.MISSING_DESTINATION,
                    "The destination is not an active location with the DESTINATION role."));
        }
        if (!facts.hasCapacity()) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.MISSING_CAPACITY,
                    "Weight, volume and pallets are all unknown."));
        }
        if (facts.activeBlockingHolds() > 0) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.ACTIVE_BLOCKING_HOLD,
                    facts.activeBlockingHolds() == 1
                            ? "The order has an active blocking hold."
                            : "The order has " + facts.activeBlockingHolds() + " active blocking holds."));
        }

        RouteResolution route = facts.routeResolution() == null
                ? RouteResolution.NOT_CONFIGURED : facts.routeResolution();
        switch (route.status()) {
            case NOT_FOUND -> reasons.add(SchedulingReason.of(SchedulingReasonCode.ROUTE_NOT_FOUND,
                    "The origin has active routes, and none of them stops at this destination."));
            case AMBIGUOUS -> reasons.add(SchedulingReason.of(SchedulingReasonCode.ROUTE_AMBIGUOUS,
                    "Several active routes stop at this destination ("
                            + String.join(", ", route.candidates().stream().map(RouteResolution.ResolvedRoute::code)
                                    .toList())
                            + "); none is chosen automatically."));
            case NOT_CONFIGURED -> reasons.add(SchedulingReason.of(SchedulingReasonCode.ROUTE_NOT_CONFIGURED,
                    "The company has no active route from this origin; the order is released without one."));
            case RESOLVED -> {
                // Nothing to report: the route is the board's routeCode.
            }
        }

        // Section 5: the two calendars answer different questions and BOTH must permit the date.
        // A missing destination has no calendar worth judging - MISSING_DESTINATION already says it.
        CalendarVerdict destination = facts.destinationUsable() && facts.destinationCalendar() != null
                ? facts.destinationCalendar() : CalendarVerdict.NOT_CONFIGURED;
        // The route's frequency is consulted only when exactly one route resolved.
        CalendarVerdict routeCalendar = route.status() == RouteResolution.Status.RESOLVED && facts.routeCalendar() != null
                ? facts.routeCalendar() : CalendarVerdict.NOT_CONFIGURED;

        List<String> idle = new ArrayList<>();
        if (destination.configured() && !destination.runs()) {
            idle.add("the destination's calendar" + codeOf(destination));
        }
        if (routeCalendar.configured() && !routeCalendar.runs()) {
            idle.add("the route's frequency" + codeOf(routeCalendar));
        }
        if (!idle.isEmpty()) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.FREQUENCY_OVERRIDE,
                    capitalised(String.join(" and ", idle)) + " does not normally serve " + facts.serviceDate()
                            + ". Released anyway, it can be assigned by hand; automatic planning will not select it"
                            + " for that date."));
        }

        ReleaseDeadline deadline = ReleaseDeadline.earlier(deadlineOf(destination, facts), deadlineOf(routeCalendar, facts));
        if (deadline != null && deadline.isMissedAt(now)) {
            reasons.add(SchedulingReason.of(SchedulingReasonCode.CUTOFF_MISSED,
                    "The release deadline for dispatch on " + facts.serviceDate() + " was "
                            + deadline.at().atZoneSameInstant(facts.zone()).toLocalDateTime() + "."));
        }

        return new SchedulingAssessment(eligibilityOf(reasons), reasons, facts.serviceDate(), deadline, route,
                destination.configured() ? destination.frequencyCode() : null,
                routeCalendar.configured() ? routeCalendar.frequencyCode() : null);
    }

    /** BLOCKED if any reason blocks, WARNING if any warns, ELIGIBLE otherwise. */
    static Eligibility eligibilityOf(List<SchedulingReason> reasons) {
        if (reasons.stream().anyMatch(reason -> reason.severity() == Eligibility.BLOCKED)) {
            return Eligibility.BLOCKED;
        }
        return reasons.isEmpty() ? Eligibility.ELIGIBLE : Eligibility.WARNING;
    }

    /** Whether any warning needs a person's reason to release over it. */
    public boolean requiresOverride() {
        return reasons.stream().anyMatch(SchedulingReason::requiresOverride);
    }

    /**
     * Whether a release may proceed without a reason: not blocked, and no warning that requires one.
     * What the integration upsert asks, because a machine cannot give a reason.
     */
    public boolean releasableWithoutReason() {
        return eligibility != Eligibility.BLOCKED && !requiresOverride();
    }

    /** Only a calendar that serves the date yields a deadline; one that does not is FREQUENCY_OVERRIDE. */
    private static ReleaseDeadline deadlineOf(CalendarVerdict verdict, Facts facts) {
        if (!verdict.configured() || !verdict.runs()) {
            return null;
        }
        return ReleaseDeadline.of(facts.serviceDate(), verdict.leadTimeDays(), verdict.cutoffTime(), facts.zone());
    }

    private static String codeOf(CalendarVerdict verdict) {
        return verdict.frequencyCode() == null ? "" : " (" + verdict.frequencyCode() + ")";
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
