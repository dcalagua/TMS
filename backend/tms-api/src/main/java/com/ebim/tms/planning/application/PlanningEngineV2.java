package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedOrder;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * V2 automatic planning: everything V1 does, plus the three facts V1 had no way to know (JOB 05).
 *
 * <p>{@link HeuristicPlanningEngine} stays exactly as it is and stays selectable. This is a second
 * implementation of the same port, which is what {@link PlanningEngine} was written for, and the
 * two are compared on identical fixtures by {@code PlanningEngineComparisonTest} rather than by
 * assertion.
 *
 * <h2>What V2 adds</h2>
 *
 * <ol>
 *   <li><b>It plans what is actually outstanding.</b> V1 packs against an order's totals. Since
 *       V37 an order can be half loaded already, and packing its whole weight onto a second truck
 *       would double-book capacity that is not needed - so V2 packs against
 *       {@link PlannableOrder#pending()} and reports a fully allocated order as
 *       {@link UnplannedReason#FULLY_ALLOCATED} rather than as a failure.</li>
 *   <li><b>It sequences stops by distance.</b> V1 visits destinations in the order the orders
 *       happened to load. V2 walks them nearest-neighbour from the origin using the travel matrix
 *       (V38), which is the single change that moves total kilometres.</li>
 *   <li><b>It refuses a trip that cannot be driven in a shift.</b> Driving plus service time
 *       against {@link PlanningShift}. A board full of shipments that run out of hours at the
 *       fourth stop is worse than a board with one fewer shipment on it.</li>
 * </ol>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><b>It is not a solver.</b> No branch and bound, no metaheuristic, no OR-Tools - deferred by
 * decision, and this class is not a substitute pretending otherwise. It is a constructive heuristic
 * whose every decision a dispatcher can follow, which is the property that makes a proposal
 * arguable rather than merely produced.
 *
 * <p><b>It does not price anything.</b> Cost is the one KPI left null. Pricing a hypothetical trip
 * needs a rating port that takes a proposal rather than a persisted shipment, and inventing a
 * plausible figure so the block looks complete would be the worst possible outcome - somebody would
 * compare two engines on it.
 *
 * <p><b>It does not split orders.</b> The ledger V37 built makes splitting expressible, but
 * <em>which</em> 30 of the 100 pallets go on the second truck is a planner's decision today. An
 * engine proposing splits is a larger change and needs its own brief.
 *
 * <p><b>It never dispatches.</b> Like V1, it returns draft trips for a planner to edit.
 *
 * <p>Pure: no repository, no clock, no randomness. Distances arrive as a {@link TravelMatrix}
 * resolved before the run, which is what keeps that true - see the matrix's own comment.
 */
@Component
public class PlanningEngineV2 implements PlanningEngine {

    static final String NAME = "PLANNING_V2";

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

        List<UnplannedOrder> unplanned = new ArrayList<>();
        List<PlannableOrder> outstanding = new ArrayList<>();
        for (PlannableOrder order : input.orders()) {
            // Nothing left to plan is not a failure to plan: after V37 a run replanned over an
            // order somebody finished by hand must say so rather than list it as a problem.
            if (order.pending().isZero() && !OrderAmounts.wholeOf(order).isZero()) {
                unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                        UnplannedReason.FULLY_ALLOCATED));
            } else {
                outstanding.add(order);
            }
        }
        if (outstanding.isEmpty()) {
            return new PlanningProposal(NAME, List.of(), unplanned,
                    Kpi.of(List.of(), unplanned, input, Map.of()));
        }
        if (input.vehicles().isEmpty()) {
            outstanding.forEach(order -> unplanned.add(
                    new UnplannedOrder(order.id(), order.orderNumber(), UnplannedReason.NO_FLEET)));
            return new PlanningProposal(NAME, List.of(), unplanned,
                    Kpi.of(List.of(), unplanned, input, Map.of()));
        }

        Corridors corridors = Corridors.of(input.routes());
        List<ProposedTrip> trips = new ArrayList<>();
        Map<UUID, TripPlan> plansByTrip = new LinkedHashMap<>();
        // A copy: a vehicle further down that takes an order the next one refuses is moved up.
        List<VehicleCapacityReference> fleet = new ArrayList<>(input.vehicles());
        int nextVehicle = 0;

        for (Map.Entry<UUID, List<PlannableOrder>> group
                : groupByCorridor(outstanding, corridors).entrySet()) {
            UUID routeId = group.getKey();
            List<PlannableOrder> ordersInGroup = sortForLoading(group.getValue(), corridors, routeId);

            TripBuilder open = null;

            for (PlannableOrder order : ordersInGroup) {
                if (tallerThanEveryVehicle(order, input.vehicles())) {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.ITEM_TALLER_THAN_VEHICLE));
                    continue;
                }
                if (exceedsEveryVehicle(order, input)) {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.EXCEEDS_LARGEST_VEHICLE));
                    continue;
                }
                if (nextVehicle >= fleet.size()) {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.NO_VEHICLE_AVAILABLE));
                    continue;
                }
                if (open == null) {
                    open = new TripBuilder(fleet.get(nextVehicle), routeId);
                }

                Fit fit = open.tryAdd(order, input);
                if (fit == Fit.ACCEPTED) {
                    continue;
                }

                // The vehicle is full, or the run would no longer fit a shift. Close what is open
                // and start the next vehicle - then try this order again exactly once. Trying it
                // twice on the same fleet position is what would loop.
                if (!open.isEmpty()) {
                    closeTrip(open, input, trips, plansByTrip);
                    nextVehicle++;
                    open = null;
                }
                // Heaviest is not tallest: the order tries every remaining vehicle on its own, and the
                // first that takes it moves up to the next position (HeuristicPlanningEngine does the
                // same). The skipped ones stay, in order, for the orders after it.
                int fitting = firstTaking(fleet, nextVehicle, order, input);
                if (fitting >= 0) {
                    fleet.add(nextVehicle, fleet.remove(fitting));
                }
                if (nextVehicle < fleet.size()) {
                    open = new TripBuilder(fleet.get(nextVehicle), routeId);
                }
                if (open == null) {
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            UnplannedReason.NO_VEHICLE_AVAILABLE));
                    continue;
                }
                Fit retry = open.tryAdd(order, input);
                if (retry != Fit.ACCEPTED) {
                    // On an empty trip the only two ways to fail are a vehicle too small - already
                    // excluded above for every vehicle - and a single stop that cannot be reached
                    // and served inside a shift. So this reason is specific, not a catch-all.
                    unplanned.add(new UnplannedOrder(order.id(), order.orderNumber(),
                            retry == Fit.EXCEEDS_SHIFT
                                    ? UnplannedReason.EXCEEDS_SHIFT
                                    : UnplannedReason.NO_VEHICLE_AVAILABLE));
                }
            }

            if (open != null && !open.isEmpty()) {
                closeTrip(open, input, trips, plansByTrip);
                nextVehicle++;
            }
        }

        return new PlanningProposal(NAME, trips, unplanned, Kpi.of(trips, unplanned, input, plansByTrip));
    }

    private static void closeTrip(TripBuilder open, PlanningInput input, List<ProposedTrip> trips,
            Map<UUID, TripPlan> plansByTrip) {
        TripPlan plan = open.finish(input);
        trips.add(new ProposedTrip(open.vehicle.id(), open.routeId, plan.orderIds(), plan.stopIds(),
                open.limits.bindingDimension(open.load)));
        plansByTrip.put(open.vehicle.id(), plan);
    }

    // --- packing ---------------------------------------------------------------------

    /** Why an order did or did not go on the trip being built. */
    private enum Fit {
        ACCEPTED,
        EXCEEDS_CAPACITY,
        EXCEEDS_SHIFT
    }

    /**
     * One trip under construction.
     *
     * <p>Every candidate is checked against capacity <em>and</em> against the run the trip would
     * become if it were added - resequenced, driven and served. Checking the shift only at the end
     * would produce a trip that has to be taken apart again, and taking one apart is where a
     * heuristic starts producing plans nobody can follow.
     */
    private static final class TripBuilder {

        private final VehicleCapacityReference vehicle;
        private final CapacityLimits limits;
        private final UUID routeId;
        private final List<PlannableOrder> orders = new ArrayList<>();
        private CapacityLoad load = CapacityLoad.EMPTY;

        TripBuilder(VehicleCapacityReference vehicle, UUID routeId) {
            this.vehicle = vehicle;
            this.limits = CapacityLimits.of(vehicle);
            this.routeId = routeId;
        }

        boolean isEmpty() {
            return orders.isEmpty();
        }

        Fit tryAdd(PlannableOrder order, PlanningInput input) {
            // Re-stacked for this vehicle's height (ADR-022 section 5).
            CapacityLoad candidate = load.plus(input.loadOn(order, order.pending(), limits));
            if (!limits.accommodates(candidate)) {
                return Fit.EXCEEDS_CAPACITY;
            }

            orders.add(order);
            TripPlan plan = sequence(orders, input);
            if (!input.shift().accommodates(plan.totalMinutes())) {
                orders.remove(orders.size() - 1);
                return Fit.EXCEEDS_SHIFT;
            }
            load = candidate;
            return Fit.ACCEPTED;
        }

        TripPlan finish(PlanningInput input) {
            return sequence(orders, input);
        }
    }

    /**
     * Orders a trip's stops nearest-neighbour from the origin, and measures the resulting run.
     *
     * <p><b>Nearest-neighbour and not something better.</b> It is O(n²) on a handful of stops,
     * every step is explainable to the dispatcher who has to drive it, and it captures most of the
     * available saving over "the order the orders happened to load". A proper tour improvement
     * (2-opt, or a solver) is the next step and is deliberately not taken here: this class is the
     * honest heuristic, not a solver wearing its name.
     *
     * <p>With no travel matrix every leg measures zero, so the sequence degrades to insertion order
     * and the shift check passes everything - exactly V1's behaviour, which is the right thing for
     * a company whose locations are not geocoded.
     */
    private static TripPlan sequence(List<PlannableOrder> orders, PlanningInput input) {
        LinkedHashSet<UUID> destinations = new LinkedHashSet<>();
        orders.forEach(order -> destinations.add(order.destinationId()));

        List<UUID> remaining = new ArrayList<>(destinations);
        List<UUID> route = new ArrayList<>(remaining.size());
        UUID at = input.originId();
        BigDecimal km = BigDecimal.ZERO;
        long minutes = 0;
        List<StopArrival> arrivals = new ArrayList<>();

        while (!remaining.isEmpty()) {
            UUID next = nearest(at, remaining, input.travel());
            remaining.remove(next);
            km = km.add(input.travel().distanceKm(at, next));
            minutes += input.travel().travelMinutes(at, next);
            arrivals.add(new StopArrival(next, minutes));
            minutes += input.serviceMinutesAt(next);
            route.add(next);
            at = next;
        }

        return new TripPlan(orders.stream().map(PlannableOrder::id).toList(), List.copyOf(route), km, minutes,
                List.copyOf(arrivals));
    }

    /**
     * The closest of {@code candidates} to {@code from}.
     *
     * <p>Ties break on the candidate list's own order, which is the orders' loading order, so the
     * same input sequences the same way twice. Without that a proposal would be irreproducible the
     * moment two destinations were equidistant - which, with every distance unknown and therefore
     * zero, is every proposal on an un-geocoded dataset.
     */
    private static UUID nearest(UUID from, List<UUID> candidates, TravelMatrix travel) {
        UUID best = candidates.get(0);
        BigDecimal bestKm = travel.distanceKm(from, best);
        for (UUID candidate : candidates) {
            BigDecimal km = travel.distanceKm(from, candidate);
            if (km.compareTo(bestKm) < 0) {
                best = candidate;
                bestKm = km;
            }
        }
        return best;
    }

    /** A trip's sequenced stops and what driving them costs. */
    private record TripPlan(List<UUID> orderIds, List<UUID> stopIds, BigDecimal distanceKm, long totalMinutes,
            List<StopArrival> arrivals) {
    }

    /** When the vehicle reaches one stop, in minutes after departure. */
    private record StopArrival(UUID locationId, long minutesFromDeparture) {
    }

    // --- KPIs ------------------------------------------------------------------------

    /** Scores a finished proposal. Separate from the packing so that neither is read through the other. */
    private static final class Kpi {

        private Kpi() {}

        static PlanningKpis of(List<ProposedTrip> trips, List<UnplannedOrder> unplanned, PlanningInput input,
                Map<UUID, TripPlan> plansByTrip) {
            Map<UUID, VehicleCapacityReference> vehicles = new LinkedHashMap<>();
            input.vehicles().forEach(vehicle -> vehicles.put(vehicle.id(), vehicle));
            Map<UUID, PlannableOrder> orders = new LinkedHashMap<>();
            input.orders().forEach(order -> orders.put(order.id(), order));

            BigDecimal totalKm = BigDecimal.ZERO;
            long totalMinutes = 0;
            int plannedOrders = 0;
            int lateOrders = 0;
            Set<UUID> distinctVehicles = new HashSet<>();
            List<BigDecimal> weightPercents = new ArrayList<>();
            List<BigDecimal> volumePercents = new ArrayList<>();
            List<BigDecimal> palletPercents = new ArrayList<>();
            List<BigDecimal> floorPercents = new ArrayList<>();
            List<BigDecimal> heightPercents = new ArrayList<>();

            for (ProposedTrip trip : trips) {
                distinctVehicles.add(trip.vehicleId());
                plannedOrders += trip.orderIds().size();
                TripPlan plan = plansByTrip.get(trip.vehicleId());
                if (plan != null) {
                    totalKm = totalKm.add(plan.distanceKm());
                    totalMinutes += plan.totalMinutes();
                    lateOrders += countLate(plan, trip, orders, input);
                }

                VehicleCapacityReference vehicle = vehicles.get(trip.vehicleId());
                CapacityLimits tripLimits = vehicle == null ? null : CapacityLimits.of(vehicle);
                CapacityLoad load = trip.orderIds().stream()
                        .map(orders::get)
                        .filter(java.util.Objects::nonNull)
                        .map(order -> input.loadOn(order, order.pending(), tripLimits))
                        .reduce(CapacityLoad.EMPTY, CapacityLoad::plus);
                if (vehicle != null) {
                    weightPercents.add(percent(load.weightKg(), vehicle.maxWeightKg()));
                    volumePercents.add(percent(load.volumeM3(), vehicle.maxVolumeM3()));
                    palletPercents.add(percent(load.pallets(), palletsOf(vehicle)));
                    floorPercents.add(percent(load.floorAreaM2(), CapacityLimits.floorOf(vehicle)));
                    heightPercents.add(percent(load.maxStackHeightM(), vehicle.heightM()));
                }
            }

            return new PlanningKpis(
                    trips.size(),
                    distinctVehicles.size(),
                    plannedOrders,
                    unplanned.size(),
                    lateOrders,
                    totalKm.setScale(3, RoundingMode.HALF_UP),
                    totalMinutes,
                    PlanningKpis.averagePercent(weightPercents),
                    PlanningKpis.averagePercent(volumePercents),
                    PlanningKpis.averagePercent(palletPercents),
                    input.travel().anyEstimated(),
                    // Never computed here - see the class comment. JOB 06 owns proposal pricing.
                    null,
                    ProposalPricing.NOT_ASKED,
                    PlanningKpis.averagePercent(floorPercents),
                    PlanningKpis.averagePercent(heightPercents));
        }

        /**
         * Orders whose requested window closes before the vehicle is projected to arrive.
         *
         * <p>Counted, not refused. A late delivery is a real delivery and usually the best answer
         * available; refusing to plan it would leave the customer with nothing instead of with
         * something late, and would hide the lateness from the planner who could still fix it.
         *
         * <p>An order with no window, or a trip with no measured arrival, is never late: an unknown
         * is not a breach, and counting it as one would make the figure meaningless on exactly the
         * datasets where distances are missing.
         */
        private static int countLate(TripPlan plan, ProposedTrip trip, Map<UUID, PlannableOrder> orders,
                PlanningInput input) {
            Map<UUID, Long> arrivalByLocation = new LinkedHashMap<>();
            plan.arrivals().forEach(arrival ->
                    arrivalByLocation.putIfAbsent(arrival.locationId(), arrival.minutesFromDeparture()));

            int late = 0;
            for (UUID orderId : trip.orderIds()) {
                PlannableOrder order = orders.get(orderId);
                if (order == null || order.requestedWindowEnd() == null) {
                    continue;
                }
                Long minutes = arrivalByLocation.get(order.destinationId());
                if (minutes == null || minutes == 0) {
                    continue;
                }
                LocalTime arrival = input.shift().clockAfter(minutes);
                if (arrival.isAfter(order.requestedWindowEnd())) {
                    late++;
                }
            }
            return late;
        }

        private static BigDecimal percent(BigDecimal used, BigDecimal limit) {
            if (limit == null || limit.signum() <= 0) {
                return null;
            }
            return used.multiply(BigDecimal.valueOf(100)).divide(limit, 1, RoundingMode.HALF_UP);
        }

        private static BigDecimal palletsOf(VehicleCapacityReference vehicle) {
            return vehicle.maxPallets() == null ? null : BigDecimal.valueOf(vehicle.maxPallets());
        }
    }

    // --- grouping and ordering, shared in shape with V1 -------------------------------

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
            grouped.put(null, offCorridor);
        }
        return grouped;
    }

    /**
     * Priority, then service date, then position along the corridor, then order number.
     *
     * <p>Identical to V1's, and deliberately so: the loading order is what a dispatcher recognises,
     * and V2's improvement is in how the resulting stops are <em>sequenced</em>, not in reshuffling
     * which orders travel together. Changing both at once would make the comparison between the two
     * engines uninterpretable.
     */
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

    /** True when no vehicle could take this order's outstanding part on its own. */
    /** One unit of the order is taller than every vehicle offered (ADR-022): no split or re-stack helps. */
    private static boolean tallerThanEveryVehicle(PlannableOrder order, List<VehicleCapacityReference> vehicles) {
        return order.maxUnitHeightM() != null && !vehicles.isEmpty()
                && vehicles.stream().noneMatch(vehicle -> CapacityLimits.of(vehicle).admitsUnitHeight(order.maxUnitHeightM()));
    }

    private static boolean exceedsEveryVehicle(PlannableOrder order, PlanningInput input) {
        return input.vehicles().stream().noneMatch(vehicle -> takes(vehicle, order, input));
    }

    /** The first vehicle from {@code from} on that takes the order's outstanding part on its own, or -1. */
    private static int firstTaking(List<VehicleCapacityReference> fleet, int from, PlannableOrder order,
            PlanningInput input) {
        for (int i = from; i < fleet.size(); i++) {
            if (takes(fleet.get(i), order, input)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean takes(VehicleCapacityReference vehicle, PlannableOrder order, PlanningInput input) {
        CapacityLimits limits = CapacityLimits.of(vehicle);
        return limits.admitsUnitHeight(order.maxUnitHeightM())
                && limits.accommodates(input.loadOn(order, order.pending(), limits));
    }
}
