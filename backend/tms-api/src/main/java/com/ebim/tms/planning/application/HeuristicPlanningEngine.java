package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedOrder;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * V1 automatic planning: four rules a dispatcher can argue with.
 *
 * <ol>
 *   <li><b>Group by corridor.</b> Each order joins the group of the first active route (by code)
 *       that serves its destination; orders whose destination is on no route form one last group.
 *       A trip never mixes groups - a mathematically excellent truckload that visits three
 *       unrelated corridors is one nobody would drive, and grouping is the difference between a
 *       proposal a planner recognises and one they throw away.</li>
 *   <li><b>Order within the group</b> by priority (urgent first), then service date, then the
 *       destination's position along the corridor, then order number. The last key is there for
 *       determinism, not for the business: with it, the same input always plans the same way.</li>
 *   <li><b>Take the biggest vehicle still free</b> and fill it while every dimension fits. The
 *       fleet arrives sorted heaviest-first, so this is first-fit-decreasing on the vehicle side:
 *       big trucks get used, and what is left over is small enough for what is left.</li>
 *   <li><b>Never split an order.</b> V1 assigns whole orders (see {@code AssignOrderRequest}), so
 *       an order that exceeds the largest vehicle offered can only be reported, not planned.</li>
 * </ol>
 *
 * <p>What it deliberately does not do: time windows, travel time, distance, cost, or any attempt
 * at optimality. Those are what a solver is for, and {@link PlanningEngine} exists so one can be
 * added beside this without planning being rebuilt. Saying so here is cheaper than a planner
 * discovering it from a bad plan.
 *
 * <p>No repository, no clock, no randomness: this class is a pure function and is unit-tested as
 * one, which is what makes it provable on a host with no database.
 */
@Component
public class HeuristicPlanningEngine implements PlanningEngine {

    static final String NAME = "HEURISTIC_V1";

    /** Urgent first. Anything the vocabulary does not know sorts last rather than throwing. */
    private static final List<String> PRIORITY_ORDER = List.of("URGENT", "HIGH", "NORMAL", "LOW");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlanningProposal plan(PlanningInput input) {
        if (input.orders().isEmpty()) {
            return PlanningProposal.empty(NAME);
        }
        if (input.vehicles().isEmpty()) {
            return new PlanningProposal(NAME, List.of(), unplannedAll(input.orders(), UnplannedReason.NO_FLEET));
        }

        Corridors corridors = Corridors.of(input.routes());
        List<ProposedTrip> trips = new ArrayList<>();
        List<UnplannedOrder> unplanned = new ArrayList<>();

        // The fleet is consumed left to right: sorted heaviest-first by the port, and never
        // reused, so one vehicle cannot end up on two trips of the same proposal.
        int nextVehicle = 0;

        for (Map.Entry<UUID, List<PlannableOrder>> group : groupByCorridor(input.orders(), corridors).entrySet()) {
            UUID routeId = group.getKey();
            List<PlannableOrder> ordersInGroup = sortForLoading(group.getValue(), corridors, routeId);

            List<PlannableOrder> open = new ArrayList<>();
            CapacityLoad load = CapacityLoad.EMPTY;
            CapacityLimits limits = nextVehicle < input.vehicles().size()
                    ? CapacityLimits.of(input.vehicles().get(nextVehicle))
                    : null;

            for (PlannableOrder order : ordersInGroup) {
                if (exceedsEveryVehicle(order, input.vehicles())) {
                    // Reported before any packing attempt: retrying it against every vehicle in
                    // turn would empty the fleet to reach the same conclusion.
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.EXCEEDS_LARGEST_VEHICLE));
                    continue;
                }
                if (limits == null) {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.NO_VEHICLE_AVAILABLE));
                    continue;
                }

                CapacityLoad candidate = load.plus(CapacityLoad.of(order));
                if (limits.accommodates(candidate)) {
                    open.add(order);
                    load = candidate;
                    continue;
                }

                // Full. Close what is open and try this order again on the next vehicle. An empty
                // open trip here would mean the order does not fit this vehicle but does fit a
                // smaller one later, which cannot happen with a heaviest-first fleet - so closing
                // an empty trip is impossible rather than merely unlikely.
                if (!open.isEmpty()) {
                    trips.add(toTrip(input.vehicles().get(nextVehicle).id(), routeId, open, corridors));
                    nextVehicle++;
                    open = new ArrayList<>();
                    load = CapacityLoad.EMPTY;
                    limits = nextVehicle < input.vehicles().size()
                            ? CapacityLimits.of(input.vehicles().get(nextVehicle))
                            : null;
                }
                if (limits != null && limits.accommodates(CapacityLoad.of(order))) {
                    open.add(order);
                    load = CapacityLoad.of(order);
                } else {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.NO_VEHICLE_AVAILABLE));
                }
            }

            if (!open.isEmpty()) {
                trips.add(toTrip(input.vehicles().get(nextVehicle).id(), routeId, open, corridors));
                nextVehicle++;
            }
        }

        return new PlanningProposal(NAME, trips, unplanned);
    }

    /**
     * Groups by corridor, keeping a stable order: routes in the order they were given (by code),
     * then the no-route group last. A {@code LinkedHashMap} because the iteration order is what
     * decides which corridor gets the biggest truck.
     */
    private static Map<UUID, List<PlannableOrder>> groupByCorridor(
            List<PlannableOrder> orders, Corridors corridors) {
        Map<UUID, List<PlannableOrder>> grouped = new LinkedHashMap<>();
        for (UUID routeId : corridors.routeIdsInOrder()) {
            grouped.put(routeId, new ArrayList<>());
        }
        List<PlannableOrder> offCorridor = new ArrayList<>();

        for (PlannableOrder order : orders) {
            UUID routeId = corridors.routeFor(order.destinationId());
            if (routeId == null) {
                offCorridor.add(order);
            } else {
                grouped.get(routeId).add(order);
            }
        }

        grouped.values().removeIf(List::isEmpty);
        if (!offCorridor.isEmpty()) {
            // null key: "these belong to no corridor", carried through to ProposedTrip.routeId.
            grouped.put(null, offCorridor);
        }
        return grouped;
    }

    private static List<PlannableOrder> sortForLoading(
            List<PlannableOrder> orders, Corridors corridors, UUID routeId) {
        return orders.stream()
                .sorted(Comparator
                        .comparingInt((PlannableOrder order) -> priorityRank(order.priority()))
                        .thenComparing(PlannableOrder::serviceDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(order -> corridors.positionOf(routeId, order.destinationId()))
                        .thenComparing(PlannableOrder::orderNumber,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static int priorityRank(String priority) {
        int index = priority == null ? -1 : PRIORITY_ORDER.indexOf(priority);
        return index < 0 ? PRIORITY_ORDER.size() : index;
    }

    /**
     * True when no vehicle in the fleet could take this order on its own. Checked against every
     * vehicle rather than only the first, because "biggest" is per dimension: the heaviest truck
     * is not necessarily the roomiest one.
     */
    private static boolean exceedsEveryVehicle(PlannableOrder order, List<VehicleCapacityReference> vehicles) {
        CapacityLoad alone = CapacityLoad.of(order);
        return vehicles.stream().noneMatch(vehicle -> CapacityLimits.of(vehicle).accommodates(alone));
    }

    /** Stops are the distinct destinations of the trip's orders, in the order the orders were loaded. */
    private static ProposedTrip toTrip(
            UUID vehicleId, UUID routeId, List<PlannableOrder> orders, Corridors corridors) {
        Set<UUID> stops = new LinkedHashSet<>();
        orders.forEach(order -> stops.add(order.destinationId()));
        List<UUID> stopOrder = new ArrayList<>(stops);
        if (routeId != null) {
            // Along the corridor rather than in loading order: the master route is what says in
            // which sequence these places are normally served.
            stopOrder.sort(Comparator.comparingInt(stop -> corridors.positionOf(routeId, stop)));
        }
        return new ProposedTrip(vehicleId, routeId, orders.stream().map(PlannableOrder::id).toList(), stopOrder);
    }

    private static List<UnplannedOrder> unplannedAll(List<PlannableOrder> orders, UnplannedReason reason) {
        return orders.stream()
                .map(order -> new UnplannedOrder(order.id(), order.orderNumber(), reason))
                .toList();
    }
}
