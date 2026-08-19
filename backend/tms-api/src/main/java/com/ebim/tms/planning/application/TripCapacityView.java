package com.ebim.tms.planning.application;

import java.util.UUID;

/**
 * The capacity answer for one trip: three dimensions, where the numbers came from, and one
 * pre-computed verdict so a client never has to combine them itself (and never gets to decide
 * what "within capacity" means - the backend does, see {@link PlanningCapacityService}).
 */
public record TripCapacityView(
        UUID tripId,
        CapacitySource source,
        long orderCount,
        CapacityDimension weight,
        CapacityDimension volume,
        CapacityDimension pallets,
        boolean withinCapacity) {
}
