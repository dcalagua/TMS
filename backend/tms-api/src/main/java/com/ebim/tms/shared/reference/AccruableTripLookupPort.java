package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.settings.AccrualTrigger;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The trips whose execution makes a freight cost an obligation (migration V65, ADR-023).
 *
 * <p>Answered by {@code planning}, which owns {@code tms.trip}; read by {@code settlement}, which
 * owns the accruals. The question "which trips should be accrued and are not" is asked over facts
 * that are already committed - a departure or a completion - so nothing the sweep needs can be lost
 * the way an in-memory event can.
 *
 * <p>Every method carries its own company predicate: the sweep calls {@link #companiesWithExecutionSince}
 * from a scheduled thread with no tenant scope, and the per-company calls under a system scope.
 */
public interface AccruableTripLookupPort {

    /**
     * Companies with a trip departed or completed at or after {@code since}, across every tenant.
     * Only the scheduled sweep asks this; it is how it finds whom to visit without a company list.
     */
    Set<UUID> companiesWithExecutionSince(OffsetDateTime since);

    /**
     * One page of the company's trips whose fact - departure for {@link AccrualTrigger#DISPATCHED},
     * completion for {@link AccrualTrigger#DELIVERED} - falls in {@code [from, to)}, in fact order.
     * Cancelled trips are never returned.
     *
     * @param after the last trip of the previous page, or null for the first: keyset paging on
     *              (fact instant, trip id), so a trip executed while the sweep pages is not skipped
     */
    List<AccruableTrip> findExecuted(UUID companyId, AccrualTrigger trigger, OffsetDateTime from,
            OffsetDateTime to, AccruableTrip after, int limit);

    /** These trips, keyed by id. Absent means not this company's. */
    Map<UUID, AccruableTrip> findByIds(Collection<UUID> tripIds, UUID companyId);

    /**
     * @param carrierId   the owner of the assigned vehicle; null for own fleet or no vehicle
     * @param vehicleId   the vehicle; null when none was assigned. Carrier null and vehicle set is
     *                    own fleet, which is not a freight payable
     * @param routeId     the route template the trip ran, for the GL mapping scope; nullable
     * @param departedAt  {@code actual_departure_at}, null if it never left
     * @param completedAt {@code actual_completion_at}, null if it never completed
     */
    record AccruableTrip(
            UUID tripId,
            String shipmentNumber,
            UUID carrierId,
            UUID vehicleId,
            UUID routeId,
            OffsetDateTime departedAt,
            OffsetDateTime completedAt) {

        /** The instant the trigger names, or null when the trip has not reached it. */
        public OffsetDateTime factAt(AccrualTrigger trigger) {
            return trigger == AccrualTrigger.DELIVERED ? completedAt : departedAt;
        }

        public boolean ownFleet() {
            return carrierId == null && vehicleId != null;
        }
    }
}
