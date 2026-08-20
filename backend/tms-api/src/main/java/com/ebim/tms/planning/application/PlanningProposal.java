package com.ebim.tms.planning.application;

import java.util.List;
import java.util.UUID;

/**
 * What a {@link PlanningEngine} came up with: some proposed trips, and - just as important - every
 * order it could not place and why.
 *
 * <p>The unplanned list is not an error channel. An engine that quietly returned four trips out of
 * a hundred orders would look like it worked; the planner would find out at the end of the day.
 * Every order that went in comes out either on a trip or in {@link #unplanned()} with a reason, and
 * {@code AutoPlanningService} asserts that as an invariant rather than trusting it.
 *
 * @param engine   {@link PlanningEngine#name()}, so a plan can be traced to the rules that made it
 * @param trips    proposed loads, in the order the engine built them - trip 1 is the first one
 * @param unplanned every order left over, each with a reason a dispatcher can act on
 */
public record PlanningProposal(String engine, List<ProposedTrip> trips, List<UnplannedOrder> unplanned) {

    public PlanningProposal {
        trips = List.copyOf(trips);
        unplanned = List.copyOf(unplanned);
    }

    public static PlanningProposal empty(String engine) {
        return new PlanningProposal(engine, List.of(), List.of());
    }

    /**
     * One proposed load.
     *
     * @param vehicleId  the vehicle the engine picked
     * @param routeId    the corridor whose orders these are, or {@code null} when the group was
     *                   assembled from destinations no active route covers
     * @param orderIds   the orders to assign, in the sequence the engine wants them served
     * @param stopLocationIds the distinct destinations behind {@code orderIds}, in stop order.
     *                   Advisory: {@code TripService} derives the real stops from the assignments
     *                   it actually accepts, so a rejected order cannot leave a phantom stop
     *                   behind. Carried anyway because it is what makes a preview readable
     */
    public record ProposedTrip(
            UUID vehicleId, UUID routeId, List<UUID> orderIds, List<UUID> stopLocationIds) {

        public ProposedTrip {
            orderIds = List.copyOf(orderIds);
            stopLocationIds = List.copyOf(stopLocationIds);
        }
    }

    /** One order the engine could not place, and the reason - phrased for a dispatcher, not a log. */
    public record UnplannedOrder(UUID orderId, String orderNumber, UnplannedReason reason) {
    }

    /**
     * Why an order was left out. Three causes, because they need three different responses:
     * find another truck, split the order, or nothing (there was simply no capacity left today).
     */
    public enum UnplannedReason {

        /** Larger than the largest vehicle offered, in at least one dimension. Splitting is the fix. */
        EXCEEDS_LARGEST_VEHICLE,

        /** Would fit something, but every vehicle was already loaded or already booked elsewhere. */
        NO_VEHICLE_AVAILABLE,

        /** No vehicle was offered at all - an empty or fully booked fleet for this date. */
        NO_FLEET,

        /**
         * The destination's service calendar does not cover the planning date. Emitted by
         * {@code AutoPlanningService}, never by an engine: calendars are master data, the engine
         * is a pure function over a snapshot, and the snapshot it receives has already had these
         * orders removed. They still appear in the proposal, because "the engine never saw it"
         * and "nobody has to deal with it" are different statements.
         */
        NOT_SERVICEABLE_ON_DATE
    }
}
