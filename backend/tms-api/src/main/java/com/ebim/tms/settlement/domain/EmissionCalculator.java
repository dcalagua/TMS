package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A trip's carbon footprint (migration V73): its distance times its vehicle type's factor. Pure, so
 * every case is a line in a table test.
 *
 * <h2>No factor, no figure</h2>
 *
 * <p>A trip without a vehicle, a vehicle type without a factor or a route that could not be measured
 * has <b>no</b> footprint - an absent figure with the reason, never zero. A zero is the most
 * flattering number a sustainability report can print and the least true one.
 *
 * <p>Kilograms to two decimals: ten grams is below any precision an emission factor has.
 */
public final class EmissionCalculator {

    private EmissionCalculator() {
    }

    /** Why a trip has, or has not, a footprint. */
    public enum Status {
        CALCULATED,
        NO_VEHICLE,
        NO_FACTOR,
        NO_DISTANCE
    }

    /** @param kgCo2e null unless {@link Status#CALCULATED} */
    public record Result(Status status, BigDecimal kgCo2e) {

        public boolean calculated() {
            return status == Status.CALCULATED;
        }
    }

    public static Result compute(boolean hasVehicle, BigDecimal factorKgPerKm, BigDecimal distanceKm) {
        if (!hasVehicle) {
            return new Result(Status.NO_VEHICLE, null);
        }
        if (factorKgPerKm == null || factorKgPerKm.signum() <= 0) {
            return new Result(Status.NO_FACTOR, null);
        }
        if (distanceKm == null || distanceKm.signum() <= 0) {
            return new Result(Status.NO_DISTANCE, null);
        }
        return new Result(Status.CALCULATED,
                distanceKm.multiply(factorKgPerKm, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * Grams of CO2e per tonne-kilometre - the intensity a shipper compares carriers on. Null without a
     * figure, a distance or a weight: a trip that carried nothing weighed has no intensity.
     */
    public static BigDecimal gramsPerTonneKm(BigDecimal kgCo2e, BigDecimal distanceKm, BigDecimal weightKg) {
        if (kgCo2e == null || distanceKm == null || weightKg == null || distanceKm.signum() <= 0
                || weightKg.signum() <= 0) {
            return null;
        }
        BigDecimal tonneKm = weightKg.divide(BigDecimal.valueOf(1000), MathContext.DECIMAL128)
                .multiply(distanceKm, MathContext.DECIMAL128);
        return kgCo2e.multiply(BigDecimal.valueOf(1000)).divide(tonneKm, 1, RoundingMode.HALF_UP);
    }
}
