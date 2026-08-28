package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * What costing needs to know about a trip to cost it on our own fleet (V48, JOB 22).
 *
 * <p>Shaped for its one consumer rather than reusing {@link CostableTrip}, which carries a carrier,
 * a route and declared weights that own-fleet costing never reads, and which lacks the two things it
 * does need - the vehicle and the planned window.
 *
 * <p>The costing module owns no trips and never will; this is the whole of what crosses the
 * boundary, in the direction ArchUnit permits.
 */
public interface OwnFleetTripLookupPort {

    Optional<OwnFleetCostableTrip> findOwnFleetCostableTrip(UUID tripId, UUID companyId);

    /**
     * @param vehicleId          the truck assigned, or null when nothing is assigned yet - in which
     *                           case no profile can be resolved and there is no cost, rather than a
     *                           cost of zero
     * @param vehicleTypeId      that truck's type, for the fallback level of profile resolution
     * @param startsAt           the planned start, or null when the trip is not scheduled
     * @param endsAt             the planned end. <b>Null when a leg could not be measured</b>
     *                           (V47's rule), and a null end means no duty and so no time-based
     *                           component - never a duration of zero
     * @param measuredDistanceKm the distance driven over this trip's own stops, or null when its
     *                           locations have no coordinates. Never zero standing in for unknown
     * @param carrierId          the owner of the assigned vehicle, or null when we own it. A trip on
     *                           a carrier's truck has no own-fleet cost to model
     */
    record OwnFleetCostableTrip(
            UUID tripId,
            UUID vehicleId,
            UUID vehicleTypeId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BigDecimal measuredDistanceKm,
            UUID carrierId) {

        /** Whether this is our truck at all. A subcontracted trip is priced, not costed. */
        public boolean isOwnFleet() {
            return vehicleId != null && carrierId == null;
        }

        /** Minutes the trip itself runs, or null when either end of the window is unknown. */
        public Long executionMinutes() {
            if (startsAt == null || endsAt == null) {
                return null;
            }
            return java.time.Duration.between(startsAt, endsAt).toMinutes();
        }
    }
}
