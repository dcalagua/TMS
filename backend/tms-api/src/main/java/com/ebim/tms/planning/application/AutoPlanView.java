package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What automatic planning did, or would do.
 *
 * <p>One shape for both {@code preview} and {@code apply} on purpose: a preview a planner trusts
 * is one that looks exactly like the result, differing in a single boolean. Two shapes would
 * invite the two paths to drift, and the drift would be invisible until a plan did something the
 * preview had not shown.
 *
 * <p>Codes and numbers are resolved here rather than left as ids. A proposal is something a human
 * reads before deciding, and "vehicle 8f1c-..." is not something anyone decides from.
 *
 * @param applied     false for a preview, true when the draft trips were actually created
 * @param engine      which algorithm produced this ({@link PlanningEngine#name()})
 * @param proposed    every proposed load, whether or not it was written
 * @param created     the trips that now exist; empty on a preview
 * @param unplanned   every order left over, with a reason
 * @param ordersConsidered how many eligible orders the snapshot held - the denominator a planner
 *                    needs to judge the rest of this record
 * @param vehiclesOffered how many vehicles were free for this date
 */
public record AutoPlanView(
        boolean applied,
        String engine,
        List<ProposedTripView> proposed,
        List<TripDetailView> created,
        List<UnplannedOrderView> unplanned,
        int ordersConsidered,
        int vehiclesOffered) {

    /** One proposed load, in the words of the screen rather than of the engine. */
    public record ProposedTripView(
            UUID vehicleId, String vehicleCode, UUID routeId, List<String> orderNumbers, int stopCount) {
    }

    /** One order that will not be planned, and why - {@code reason} is the enum name, translated by the UI. */
    public record UnplannedOrderView(UUID orderId, String orderNumber, String reason) {
    }

    static AutoPlanView preview(
            PlanningProposal proposal, Map<UUID, String> orderNumbers, Map<UUID, String> vehicleCodes) {
        return new AutoPlanView(false, proposal.engine(), proposedViews(proposal, orderNumbers, vehicleCodes),
                List.of(), unplannedViews(proposal), orderNumbers.size(), vehicleCodes.size());
    }

    static AutoPlanView applied(PlanningProposal proposal, List<TripDetailView> created,
            Map<UUID, String> orderNumbers, Map<UUID, String> vehicleCodes) {
        return new AutoPlanView(true, proposal.engine(), proposedViews(proposal, orderNumbers, vehicleCodes),
                List.copyOf(created), unplannedViews(proposal), orderNumbers.size(), vehicleCodes.size());
    }

    private static List<ProposedTripView> proposedViews(
            PlanningProposal proposal, Map<UUID, String> orderNumbers, Map<UUID, String> vehicleCodes) {
        return proposal.trips().stream()
                .map(trip -> new ProposedTripView(trip.vehicleId(), vehicleCodes.get(trip.vehicleId()),
                        trip.routeId(), orderNumbersOf(trip, orderNumbers), trip.stopLocationIds().size()))
                .toList();
    }

    private static List<String> orderNumbersOf(ProposedTrip trip, Map<UUID, String> orderNumbers) {
        return trip.orderIds().stream().map(orderNumbers::get).filter(java.util.Objects::nonNull).toList();
    }

    private static List<UnplannedOrderView> unplannedViews(PlanningProposal proposal) {
        return proposal.unplanned().stream()
                .map(AutoPlanView::toView)
                .toList();
    }

    private static UnplannedOrderView toView(UnplannedOrder unplanned) {
        return new UnplannedOrderView(unplanned.orderId(), unplanned.orderNumber(), unplanned.reason().name());
    }
}
