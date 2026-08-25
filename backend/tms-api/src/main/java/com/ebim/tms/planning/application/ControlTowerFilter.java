package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.TripStatus;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the control tower is looking at.
 *
 * <p>Company is deliberately absent, for the reason {@link TripFilter} gives: it comes from the
 * resolved {@code CompanyScope} and is never something a caller can widen.
 *
 * <p><b>The date is not optional in effect.</b> It may arrive null, and then it is the company's
 * own today ({@code CompanyScope.today()}) - never the server's date and never one the browser
 * computed, because a control tower opened at 23:30 in {@code America/Lima} must show the 23rd and
 * not the 24th that UTC already thinks it is. A control tower with no day is not a screen anybody
 * can act on: every number on it is "of a day", and a date range would turn "which trips are late"
 * into a report.
 *
 * @param status narrows the operational table only, never the KPI strip or the panels above it -
 *     see {@code ControlTowerService}. Filtering the headline numbers by state would make them
 *     answer a question nobody asked ("how many in-transit trips are in transit")
 */
public record ControlTowerFilter(
        LocalDate date,
        UUID originId,
        UUID carrierId,
        TripStatus status) {
}
