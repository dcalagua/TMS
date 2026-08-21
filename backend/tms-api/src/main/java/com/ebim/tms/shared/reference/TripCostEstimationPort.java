package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.UUID;

/**
 * How {@code planning} asks for a shipment to be priced without depending on
 * {@code com.ebim.tms.rates} (migration V30). Implemented by
 * {@code rates.infrastructure.TripCostEstimationAdapter}.
 *
 * <p>Confirmation is where this is called, and that is the whole point: it is the moment the plan
 * becomes binding, so it is the moment the tariff in force should be recorded against it. A cost
 * estimated later would be estimated against whatever the rate cards said later.
 */
public interface TripCostEstimationPort {

    /**
     * Prices a trip that has just been confirmed, in the caller's transaction.
     *
     * <p><b>Best effort, and precisely defined.</b> A trip with no matching rate card is left with
     * no cost row at all and nothing is raised: an installation that has not entered its tariffs
     * yet must still be able to confirm a plan, and refusing would make costing a precondition for
     * dispatching a truck. The same goes for a trip whose carrier is not yet known.
     *
     * <p>What it does <em>not</em> swallow is a defect - a card that cannot be read, a cost row
     * that will not persist. Those roll the confirmation back with everything else, because a plan
     * confirmed in a transaction that half-failed is worse than a plan not confirmed.
     *
     * <p>Idempotent per trip: called twice, the second call rewrites the same row rather than
     * adding a second one ({@code uq_trip_cost_trip}). A cost already closed is left untouched.
     *
     * @param scope the tenant the trip belongs to, passed whole rather than as a bare company id
     *     because the estimate this writes is audited, and an audit event is scoped exactly like
     *     the write it describes ({@code AuditRecorder})
     * @param actorId the person confirming the plan - the estimate is recorded as theirs, because
     *     it is their action that caused it
     */
    void estimateOnConfirmation(CompanyScope scope, UUID tripId, UUID actorId);
}
