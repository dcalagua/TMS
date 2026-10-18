package com.ebim.tms.planning.application;

import java.util.UUID;

/**
 * The capacity answer for one trip: five dimensions (three until V62), where the numbers came from, and one
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
        /** Square metres of floor (V62); unlimited when the vehicle type has no length or width. */
        CapacityDimension floor,
        /** The tallest stack against the vehicle's height (V62); a maximum, never a sum. */
        CapacityDimension height,
        boolean withinCapacity,
        /**
         * The dimension closest to its limit ({@code WEIGHT|VOLUME|FLOOR|HEIGHT|PALLETS}), or null
         * when no dimension has a percentage - what a planner reads as "what fills this truck".
         */
        String bindingDimension) {
}
