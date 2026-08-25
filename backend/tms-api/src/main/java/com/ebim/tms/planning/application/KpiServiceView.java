package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * What happened at the customer's door: whether the vehicle got there inside the promised window,
 * and whether the goods were actually handed over
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Service").
 *
 * <p><b>Two populations, not one.</b> The stop figures are about the <em>vehicle</em> and the
 * delivery figures are about the <em>goods</em> - the same distinction {@code DeliveryResult}'s
 * header draws against {@code StopExecutionStatus}. A stop serving three orders can be completed
 * with one of them rejected, so these numbers do not add up to each other and are not meant to.
 *
 * @param stops                 every stop on a non-cancelled shipment in the range
 * @param stopsCompleted        served and left
 * @param stopsSkipped          never attempted, by decision
 * @param stopsFailed           attempted and not served
 * @param serviceWindowsMeasured stops carrying both a recorded arrival and a promised window - the
 *                              only ones punctuality can be judged over, for the reason
 *                              {@code KpiShipmentsView.departuresMeasured} gives about departures
 * @param serviceWindowsMissed  of those, the ones the vehicle reached after the window had closed.
 *                              Judged in the company's own time zone against the shipment's
 *                              planning date, in SQL - see {@code TripStopRepository.serviceTotalsForRange}
 * @param onTimeServicePercent  {@code (measured - missed) / measured}, or null when nothing was
 *                              measured
 * @param deliveriesRecorded    order-level outcomes recorded against the range's shipments
 * @param deliveriesDelivered   handed over in full ({@code DeliveryResult.DELIVERED})
 * @param deliveriesShort       the customer was left short - partial, refused, or the attempt
 *                              failed ({@code DeliveryResult.isShortfall})
 * @param deliveriesNotAttempted never taken off the vehicle. Outside {@code deliveriesShort}
 *                              deliberately, exactly as it is outside that predicate: the stop it
 *                              belongs to already carries a typed exception explaining why
 * @param deliverySuccessPercent {@code deliveriesDelivered / deliveriesRecorded}, or null when
 *                              nothing was recorded - which is the ordinary state of an
 *                              installation that has not started recording proof of delivery, and
 *                              must not read as a 0% success rate
 */
public record KpiServiceView(
        long stops,
        long stopsCompleted,
        long stopsSkipped,
        long stopsFailed,
        long serviceWindowsMeasured,
        long serviceWindowsMissed,
        BigDecimal onTimeServicePercent,
        long deliveriesRecorded,
        long deliveriesDelivered,
        long deliveriesShort,
        long deliveriesNotAttempted,
        BigDecimal deliverySuccessPercent) {
}
