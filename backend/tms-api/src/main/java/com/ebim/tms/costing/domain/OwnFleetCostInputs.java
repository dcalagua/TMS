package com.ebim.tms.costing.domain;

import java.math.BigDecimal;

/**
 * What the calculator is handed about one trip (V48, JOB 22).
 *
 * <p><b>Every quantity is nullable, and null means "we could not measure it" - never zero.</b> This
 * is the rule the whole job turns on. A trip whose distance is unknown priced at zero kilometres
 * comes out cheaper than every trip that could be measured, and a planner comparing options would
 * be handed the un-measurable one as the winner. The same mistake as V43's ETAs, V45's quantities
 * and V38's travel matrix, and it is caught here the same way: absence is a value, and the
 * calculator refuses a comparable total rather than substituting zero.
 *
 * @param distanceKm       kilometres over the trip, reposition included when one applied, or null
 *                         when routing could not measure the legs
 * @param distanceSource   how {@code distanceKm} was arrived at; null exactly when it is null
 * @param dutyMinutes      minutes the resource is tied up, reposition included when one applied, or
 *                         null when the trip has no planned window to measure
 * @param dutySource       how {@code dutyMinutes} was arrived at; null exactly when it is null
 */
public record OwnFleetCostInputs(
        BigDecimal distanceKm,
        OwnFleetQuantitySource distanceSource,
        Long dutyMinutes,
        OwnFleetQuantitySource dutySource) {

    /** Nothing measurable at all - what a trip with no coordinates and no window supplies. */
    public static final OwnFleetCostInputs NOTHING_MEASURED =
            new OwnFleetCostInputs(null, null, null, null);

    public OwnFleetCostInputs {
        if ((distanceKm == null) != (distanceSource == null)) {
            throw new IllegalArgumentException(
                    "a distance and its provenance are present together or absent together");
        }
        if ((dutyMinutes == null) != (dutySource == null)) {
            throw new IllegalArgumentException(
                    "a duty duration and its provenance are present together or absent together");
        }
        if (distanceKm != null && distanceKm.signum() < 0) {
            throw new IllegalArgumentException("a distance cannot be negative");
        }
        if (dutyMinutes != null && dutyMinutes < 0) {
            throw new IllegalArgumentException("a duty duration cannot be negative");
        }
    }

    public boolean hasDistance() {
        return distanceKm != null;
    }

    public boolean hasDuty() {
        return dutyMinutes != null;
    }

    /** Duty expressed in hours, which is the unit the profile's rates are quoted in. */
    public BigDecimal dutyHours() {
        if (dutyMinutes == null) {
            return null;
        }
        return BigDecimal.valueOf(dutyMinutes).divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP);
    }
}
