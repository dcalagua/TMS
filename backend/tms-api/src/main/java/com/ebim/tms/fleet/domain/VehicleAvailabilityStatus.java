package com.ebim.tms.fleet.domain;

/**
 * A vehicle's current operational status, mirroring the {@code ck_vehicle_availability_status}
 * check constraint in migration V9. Deliberately a single current-state flag, not a scheduling
 * calendar - see the step brief's "availability baseline that does not pretend to be a full
 * scheduling calendar" and {@link Vehicle}'s class comment.
 */
public enum VehicleAvailabilityStatus {
    AVAILABLE,
    IN_MAINTENANCE,
    OUT_OF_SERVICE
}
