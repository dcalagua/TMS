package com.ebim.tms.fleet.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Why a vehicle or a driver cannot work (migration V42, {@code ck_resource_unavailability_reason}).
 *
 * <p>One enum for both, with each value declaring which resources it can describe. A vehicle on
 * HOLIDAY and a driver in REPAIR are both nonsense, and an enum that cannot say so leaves the
 * refusal to whoever remembers to write it.
 */
public enum UnavailabilityReason {

    MAINTENANCE(Applies.VEHICLE),
    REPAIR(Applies.VEHICLE),
    INSPECTION(Applies.VEHICLE),

    ABSENCE(Applies.DRIVER),
    HOLIDAY(Applies.DRIVER),
    TRAINING(Applies.DRIVER),
    MEDICAL(Applies.DRIVER),

    /**
     * Either. Present because an operation always has a reason nobody anticipated, and because the
     * alternative - a planner picking the nearest wrong value - loses more than a vague one does.
     */
    OTHER(Applies.EITHER);

    private enum Applies { VEHICLE, DRIVER, EITHER }

    private static final Set<Applies> VEHICLE_OK = EnumSet.of(Applies.VEHICLE, Applies.EITHER);
    private static final Set<Applies> DRIVER_OK = EnumSet.of(Applies.DRIVER, Applies.EITHER);

    private final Applies applies;

    UnavailabilityReason(Applies applies) {
        this.applies = applies;
    }

    public boolean appliesToVehicle() {
        return VEHICLE_OK.contains(applies);
    }

    public boolean appliesToDriver() {
        return DRIVER_OK.contains(applies);
    }
}
