package com.ebim.tms.rates.domain;

import com.ebim.tms.shared.reference.CostableTrip;
import java.math.BigDecimal;

/**
 * The quantities one shipment can be charged by, separated from the trip they were read off so
 * that {@link TripCostCalculator} is a pure function of "a card and four numbers".
 *
 * <p>Every field is <em>nullable, and null means unknown</em>. That is the whole contract: a
 * component whose quantity is unknown is reported as non-calculable and contributes nothing,
 * instead of being multiplied by a zero nobody meant.
 *
 * @param distanceKm the master route's reference distance, or null when the shipment has no route
 *                   or the route has no distance on it. TMS measures no road distance and this
 *                   record does not pretend otherwise.
 */
public record CostInputs(BigDecimal distanceKm, BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {

    /**
     * The quantities of a trip, with {@code distanceKm} supplied separately because it comes from
     * the route master rather than from the trip.
     *
     * <p><b>Zero becomes unknown here</b>, and only for the three declared totals. A trip whose
     * orders declare no weight at all sums to zero, and zero is indistinguishable from "nobody
     * filled the field in" - the same ambiguity {@code CapacityLoad} resolves the other way, to
     * zero, because an unknown weight must not silently disable a capacity limit. Costing has to
     * resolve it the opposite way for the same reason: charging a truckload at nothing per kilo
     * because a field was left blank produces an invoice that is confidently wrong, and a line
     * that says "weight unknown" is the honest answer.
     */
    public static CostInputs of(CostableTrip trip, BigDecimal distanceKm) {
        return new CostInputs(positiveOrNull(distanceKm), positiveOrNull(trip.weightKg()),
                positiveOrNull(trip.volumeM3()), positiveOrNull(trip.pallets()));
    }

    /** The quantity for one measured component, or null when it is unknown. */
    public BigDecimal quantityFor(RateComponent component) {
        return switch (component) {
            case DISTANCE -> distanceKm;
            case WEIGHT -> weightKg;
            case VOLUME -> volumeM3;
            case PALLETS -> pallets;
            case BASE, MINIMUM_ADJUSTMENT -> throw new IllegalArgumentException(
                    component + " is a flat amount and has no quantity");
        };
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}
