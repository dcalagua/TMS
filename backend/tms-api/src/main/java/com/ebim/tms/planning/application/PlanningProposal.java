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
public record PlanningProposal(String engine, List<ProposedTrip> trips, List<UnplannedOrder> unplanned,
        /**
         * What this proposal costs a day, in the terms a planner judges one by (JOB 05). Never
         * null: an engine that computes no KPIs reports {@link PlanningKpis#NONE} rather than
         * leaving a caller to decide what absent means.
         */
        PlanningKpis kpis) {

    public PlanningProposal {
        trips = List.copyOf(trips);
        unplanned = List.copyOf(unplanned);
        kpis = kpis == null ? PlanningKpis.NONE : kpis;
    }

    /** The pre-JOB-05 shape, for an engine that scores nothing. */
    public PlanningProposal(String engine, List<ProposedTrip> trips, List<UnplannedOrder> unplanned) {
        this(engine, trips, unplanned, PlanningKpis.NONE);
    }

    public static PlanningProposal empty(String engine) {
        return new PlanningProposal(engine, List.of(), List.of(), PlanningKpis.NONE);
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
     * Why an order was left out. Each cause needs a different response from a dispatcher - find
     * another truck, split the order, reload the board, or nothing at all - which is why they are
     * separate values and not one "could not plan".
     */
    public enum UnplannedReason {

        /** Larger than the largest vehicle offered, in at least one dimension. Splitting is the fix. */
        EXCEEDS_LARGEST_VEHICLE,

        /** Would fit something, but every vehicle was already loaded or already booked elsewhere. */
        NO_VEHICLE_AVAILABLE,

        /** No vehicle was offered at all - an empty or fully booked fleet for this date. */
        NO_FLEET,

        /**
         * The order was planned by somebody else between the snapshot and the write. Emitted by
         * {@code AutoPlanningService.apply} only - a preview cannot produce it, because nothing
         * has been attempted yet - and it is deliberately its own reason rather than
         * {@link #NO_VEHICLE_AVAILABLE}: the fix is to reload, not to find another truck, and
         * telling a planner their fleet was full when in fact a colleague was faster sends them
         * looking for capacity they already have.
         */
        TAKEN_WHILE_PLANNING,

        /**
         * The destination's service calendar does not cover the planning date. Emitted by
         * {@code AutoPlanningService}, never by an engine: calendars are master data, the engine
         * is a pure function over a snapshot, and the snapshot it receives has already had these
         * orders removed. They still appear in the proposal, because "the engine never saw it"
         * and "nobody has to deal with it" are different statements.
         */
        NOT_SERVICEABLE_ON_DATE,
        /**
         * Every trip that could carry it would have run past the end of the shift (JOB 05).
         *
         * <p>Its own reason rather than {@code NO_VEHICLE_AVAILABLE}, because the two call for
         * different actions: a capacity problem is solved with another truck, and this one is
         * solved with an earlier departure, a longer shift or a closer set of stops. Telling a
         * planner "no vehicle" when the fleet is idle would send them looking for the wrong thing.
         */
        EXCEEDS_SHIFT,
        /**
         * The order carries no unplanned remainder: all of it is already on trips (V37).
         *
         * <p>Reached when a run is replanned after somebody assigned part of an order by hand.
         * Distinct from every other reason here because nothing is wrong - the work is done, and
         * reporting it as a failure to plan would put a solved order on a planner's exception list.
         */
        FULLY_ALLOCATED
    }
}
