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
public record CostInputs(BigDecimal distanceKm, BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets,
        /**
         * Drops after the first (V39), or null when the shipment has no stops yet. Never negative:
         * a one-stop trip contributes zero chargeable stop-offs, which is a real quantity and not
         * an unknown one.
         */
        BigDecimal chargeableStops,
        /**
         * Hours of detention somebody recorded (V39), or null when nobody has.
         *
         * <p>Null on every estimate, and correctly so: waiting is measured on the road. The
         * component reports itself non-calculable rather than being silently omitted, so an
         * estimate and an invoice can be compared line for line.
         */
        BigDecimal waitingHours,
        /**
         * Where {@link #distanceKm} came from (V39).
         *
         * <p>A fact about this estimate, not about the component: a shipment with its own measured
         * route (V38) is priced on {@link CostQuantitySource#MEASURED_ROUTE}, and one falling back
         * to the master corridor on {@link CostQuantitySource#ROUTE_REFERENCE}. The breakdown shows
         * which, because a per-kilometre charge that cannot be traced to the kilometres it
         * multiplied is a number nobody can dispute.
         *
         * <p>Null whenever {@code distanceKm} is null - there is no provenance for an absent
         * figure.
         */
        CostQuantitySource distanceSource) {

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
    /**
     * The four V30 quantities, with no stop count and no detention.
     *
     * <p>Kept as a constructor rather than pushed onto every caller: a test about weight rounding
     * should not have to say twice that it is not about stop-offs, and the two V39 quantities are
     * genuinely absent in that case rather than zero.
     */
    public CostInputs(BigDecimal distanceKm, BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {
        this(distanceKm, weightKg, volumeM3, pallets, null, null, CostQuantitySource.ROUTE_REFERENCE);
    }

    public static CostInputs of(CostableTrip trip, BigDecimal distanceKm) {
        return of(trip, distanceKm, CostQuantitySource.ROUTE_REFERENCE, null, null);
    }

    /**
     * The full V39 shape: the quantities above plus the shipment's stop count and any recorded
     * detention.
     *
     * <p>{@code stopCount} becomes {@code stopCount - 1} here, once, so that no caller and no
     * screen has to remember that the first drop is free - see {@code RateComponent.STOP_OFF}.
     * Zero survives, unlike the three declared totals: a single-stop shipment genuinely has no
     * chargeable stop-off, which is a fact rather than a blank field.
     */
    public static CostInputs of(CostableTrip trip, BigDecimal distanceKm, CostQuantitySource distanceSource,
            Integer stopCount, BigDecimal waitingHours) {
        BigDecimal chargeableStops = stopCount == null
                ? null
                : BigDecimal.valueOf(Math.max(0, stopCount - 1));
        BigDecimal distance = positiveOrNull(distanceKm);
        return new CostInputs(distance, positiveOrNull(trip.weightKg()),
                positiveOrNull(trip.volumeM3()), positiveOrNull(trip.pallets()),
                chargeableStops, positiveOrNull(waitingHours),
                distance == null ? null : distanceSource);
    }

    /**
     * Where a component's quantity came from on <em>this</em> estimate, which is the declared
     * source for everything except distance - see {@link #distanceSource}.
     */
    public CostQuantitySource sourceFor(RateComponent component) {
        if (component == RateComponent.DISTANCE && distanceSource != null) {
            return distanceSource;
        }
        return component.quantitySource();
    }

    /** The quantity for one measured component, or null when it is unknown. */
    public BigDecimal quantityFor(RateComponent component) {
        return switch (component) {
            case DISTANCE -> distanceKm;
            case WEIGHT -> weightKg;
            case VOLUME -> volumeM3;
            case PALLETS -> pallets;
            case STOP_OFF -> chargeableStops;
            case WAITING_TIME -> waitingHours;
            // The linehaul is not known until the linehaul components have been summed, so the
            // calculator supplies it rather than this record holding a number that does not exist
            // yet.
            case FUEL_SURCHARGE -> throw new IllegalArgumentException(
                    "FUEL_SURCHARGE multiplies the linehaul, which the calculator computes");
            case BASE, TOLL, OTHER_ACCESSORIAL, MINIMUM_ADJUSTMENT, MAXIMUM_ADJUSTMENT ->
                    throw new IllegalArgumentException(component + " is a flat amount and has no quantity");
        };
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }
}
