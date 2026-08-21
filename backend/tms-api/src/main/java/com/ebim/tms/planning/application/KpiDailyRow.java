package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One operating day of the range, as a chart column and as a row of the detail table
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "The daily series").
 *
 * <p><b>Every day in the range gets one, including the empty ones.</b> A series built only from the
 * days that produced rows would draw a quiet Sunday and a busy Monday at the same spacing and hide
 * the gap between them, which is the one thing an operations chart must not do. See
 * {@code KpiRange.dates}.
 *
 * <p><b>These are the same numbers the summary is made of.</b> The service sums this list to
 * produce the shipment, delivery and exception headlines rather than running a second set of
 * queries, so a total on a card and the column it came from cannot disagree - which is the failure
 * mode dashboards have.
 *
 * @param date                   the operating day (a shipment's planning date, an order's service
 *                               date - never when a row was typed)
 * @param trips                  shipments planned for the day, cancelled ones included
 * @param tripsCancelled         of those, withdrawn
 * @param tripsCompleted         of those, closed out
 * @param departuresMeasured     shipments carrying both a planned and an actual departure
 * @param departuresLate         of those, the ones that left late
 * @param onTimeDeparturePercent {@code (measured - late) / measured}, or null on a day nothing was
 *                               measured - which is every day in the future and every day the
 *                               operation did not record a departure
 * @param deliveriesRecorded     order-level outcomes recorded against the day's shipments
 * @param deliveriesDelivered    of those, handed over in full
 * @param deliverySuccessPercent {@code delivered / recorded}, or null when nothing was recorded
 * @param exceptions             problems raised against the day's shipments
 * @param exceptionsOpen         of those, still open
 */
public record KpiDailyRow(
        LocalDate date,
        long trips,
        long tripsCancelled,
        long tripsCompleted,
        long departuresMeasured,
        long departuresLate,
        BigDecimal onTimeDeparturePercent,
        long deliveriesRecorded,
        long deliveriesDelivered,
        BigDecimal deliverySuccessPercent,
        long exceptions,
        long exceptionsOpen) {
}
