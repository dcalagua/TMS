package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * The one time rule a shipment has in V1: a planned departure belongs to the day its planning run
 * plans.
 *
 * <p>Worth stating because the two values are stored in different types and neither implies the
 * other. {@code tms.planning_run.planning_date} is a plain date - the service day the run is
 * planning - while {@code tms.trip.planned_departure_at} is an instant with an offset, typed that
 * way so a multi-country installation is not ambiguous. Nothing in the schema connects them, and
 * until this check existed a planner could open a run for the 20th and depart a trip on the 25th:
 * a shipment that the double-booking rule counts against the 20th, that eligible orders for the
 * 20th were loaded onto, and that leaves five days later.
 *
 * <p><b>Which day counts.</b> The company's own time zone
 * ({@code CompanyScope.timeZone}, from {@code tms.company.time_zone}), never the server's and
 * never the offset the client happened to send. A depot in {@code America/Lima} planning the 20th
 * means the 20th in Lima; the same instant is already the 21st in UTC, and judging it by UTC would
 * reject the last five hours of every planning day.
 *
 * <p>Refused as a 400 rather than a 409: a departure on the wrong day is a malformed request, not
 * a race with another planner.
 */
final class ShipmentTimeRules {

    private ShipmentTimeRules() {}

    /**
     * @param plannedDepartureAt may be null - a draft trip does not need a departure until it is
     *                           confirmed, and "not decided yet" is not an inconsistency
     * @param companyTimeZone    an IANA zone id; an unrecognised one falls back to the system zone
     *                           rather than failing the request, because a bad
     *                           {@code company.time_zone} is a master-data defect and must not
     *                           make planning unusable
     */
    static void requireDepartureOnPlanningDate(
            OffsetDateTime plannedDepartureAt, LocalDate planningDate, String companyTimeZone, String what) {
        if (plannedDepartureAt == null) {
            return;
        }
        LocalDate departureDay = plannedDepartureAt.atZoneSameInstant(zoneOf(companyTimeZone)).toLocalDate();
        if (!departureDay.equals(planningDate)) {
            throw new InvalidRequestException(String.format(Locale.ROOT,
                    "%s has a planned departure on %s, which is not the planning date of its run (%s).",
                    what, departureDay, planningDate));
        }
    }

    private static ZoneId zoneOf(String companyTimeZone) {
        if (companyTimeZone == null || companyTimeZone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(companyTimeZone);
        } catch (DateTimeException unknown) {
            return ZoneId.systemDefault();
        }
    }
}
