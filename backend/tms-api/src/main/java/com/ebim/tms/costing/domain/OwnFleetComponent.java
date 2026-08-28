package com.ebim.tms.costing.domain;


/**
 * The things running our own truck consumes (V48, JOB 22).
 *
 * <p><b>Why this is not {@code RateComponent}.</b> A carrier's rate card charges {@code DISTANCE}
 * and {@code FUEL_SURCHARGE}; those are line items in a commercial agreement. Fuel, driver hours and
 * depreciation are what a vehicle actually consumes, and the two vocabularies only look alike. A
 * carrier's {@code DISTANCE} charge is one number covering fuel, wear, the driver and their margin;
 * mapping our fuel rate onto it would put unlike things in one enum and make a report that summed
 * across both meaningless.
 *
 * <p><b>Every component here has a real quantity source</b>, which is the test each had to pass to
 * be in V1. There is no {@code OVERHEAD_ALLOCATION} and no {@code INSURANCE_PER_TRIP}, not because
 * they are not real costs but because TMS holds no input that would give either a quantity - they
 * would be a second flat charge wearing a specific name.
 */
public enum OwnFleetComponent {

    /** Charged once per trip whatever it turns out to be. Needs no quantity, so never unknown. */
    FIXED_TRIP(null, false, false),

    /** Fuel burned over the distance driven, repositioning included. */
    FUEL_PER_KM(OwnFleetUnit.KM, true, false),

    /** The driver's time, which runs from when they start repositioning, not when the trip does. */
    DRIVER_PER_HOUR(OwnFleetUnit.HOUR, false, true),

    /** The vehicle being unavailable for anything else while this runs. */
    VEHICLE_PER_HOUR(OwnFleetUnit.HOUR, false, true),

    /** Servicing and tyres, accrued per kilometre. */
    MAINTENANCE_PER_KM(OwnFleetUnit.KM, true, false),

    /** The truck's capital consumed per kilometre. */
    DEPRECIATION_PER_KM(OwnFleetUnit.KM, true, false),

    /**
     * A flat expected toll per trip.
     *
     * <p>Deliberately NOT per kilometre. Tolls depend on which roads a route uses, not how long it
     * is, and multiplying distance by an average would produce a figure with the shape of a
     * measurement and the content of a guess. A company that cannot state one leaves it unset, and
     * the estimate then says tolls are not modelled rather than saying they are zero.
     */
    TOLL(null, false, false);

    private final OwnFleetUnit unit;
    private final boolean needsDistance;
    private final boolean needsDuty;

    OwnFleetComponent(OwnFleetUnit unit, boolean needsDistance, boolean needsDuty) {
        this.unit = unit;
        this.needsDistance = needsDistance;
        this.needsDuty = needsDuty;
    }

    /** The unit its rate is quoted in, or null for a flat per-trip charge. */
    public OwnFleetUnit unit() {
        return unit;
    }

    /** Whether this component cannot be calculated without a distance. */
    public boolean needsDistance() {
        return needsDistance;
    }

    /** Whether this component cannot be calculated without a duty duration. */
    public boolean needsDuty() {
        return needsDuty;
    }

    /** Whether the component is charged flat per trip, so no input can be missing. */
    public boolean isFlat() {
        return !needsDistance && !needsDuty;
    }
}
