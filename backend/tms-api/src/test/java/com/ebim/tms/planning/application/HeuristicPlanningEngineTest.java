package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedOrder;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The automatic planning heuristic, proved as what it is: a pure function.
 *
 * <p>This suite matters more than its size suggests. Every Testcontainers test in this repository
 * is skipped on the development host (BASELINE E-1), so an engine that reached for a repository or
 * a clock would be shipped unproven. It does not, and these tests are the proof - they run
 * everywhere, in milliseconds, with no database and no Spring context.
 *
 * <p>They assert behaviour a dispatcher would recognise, not implementation: which trips came out,
 * in what order, and - just as important - which orders did not and why.
 */
class HeuristicPlanningEngineTest {

    private static final UUID ORIGIN = UUID.nameUUIDFromBytes("origin".getBytes(StandardCharsets.UTF_8));
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    private final HeuristicPlanningEngine engine = new HeuristicPlanningEngine();

    // --- fixtures -------------------------------------------------------------------------

    /**
     * A stable id for a label. Derived from the label rather than random, so a failing assertion
     * names the same vehicle or destination on every run - which is the whole point of an engine
     * that is reproducible.
     */
    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    /** An order with only the fields the engine reads; everything else is deliberately null. */
    private static PlannableOrder order(String suffix, UUID destination, String priority,
            double weightKg, double volumeM3, double pallets) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, destination, null, null, DATE, priority,
                null, null, BigDecimal.valueOf(weightKg), BigDecimal.valueOf(volumeM3),
                BigDecimal.valueOf(pallets), null, null);
    }

    private static VehicleCapacityReference vehicle(String suffix, double maxWeightKg, double maxVolumeM3,
            int maxPallets) {
        return new VehicleCapacityReference(id(suffix), "VEH-" + suffix, "PLT-" + suffix, null, null,
                id("type-" + suffix), "TYPE", BigDecimal.valueOf(maxWeightKg), BigDecimal.valueOf(maxVolumeM3),
                maxPallets, true, "AVAILABLE");
    }

    private static RouteTemplate route(String suffix, UUID... destinations) {
        return new RouteTemplate(id(suffix), "RT-" + suffix, "Route " + suffix, ORIGIN, List.of(destinations), true);
    }

    private PlanningProposal plan(List<PlannableOrder> orders, List<VehicleCapacityReference> vehicles,
            List<RouteTemplate> routes) {
        return engine.plan(new PlanningInput(ORIGIN, DATE, orders, vehicles, routes));
    }

    private static List<String> orderNumbersOf(ProposedTrip trip, List<PlannableOrder> orders) {
        return trip.orderIds().stream()
                .map(orderId -> orders.stream().filter(o -> o.id().equals(orderId)).findFirst().orElseThrow())
                .map(PlannableOrder::orderNumber)
                .toList();
    }

    // --- degenerate inputs ------------------------------------------------------------------

    @Nested
    @DisplayName("nothing to do")
    class Degenerate {

        @Test
        @DisplayName("no orders produces no trips and no complaints")
        void noOrders() {
            PlanningProposal proposal = plan(List.of(), List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips()).isEmpty();
            assertThat(proposal.unplanned()).isEmpty();
            assertThat(proposal.engine()).isEqualTo("HEURISTIC_V1");
        }

        @Test
        @DisplayName("no fleet reports every order rather than returning an empty plan")
        void noVehicles() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 100, 1, 1),
                    order("02", id("d2"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(), List.of());

            assertThat(proposal.trips()).isEmpty();
            assertThat(proposal.unplanned())
                    .as("an empty plan and an empty fleet look identical to a planner unless the "
                            + "engine says which one happened")
                    .extracting(UnplannedOrder::orderNumber, UnplannedOrder::reason)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("TO-01", UnplannedReason.NO_FLEET),
                            org.assertj.core.api.Assertions.tuple("TO-02", UnplannedReason.NO_FLEET));
        }
    }

    // --- capacity ---------------------------------------------------------------------------

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("fills a vehicle, then opens the next one")
        void fillsThenOpensTheNextVehicle() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 6_000, 10, 5),
                    order("02", id("d2"), "NORMAL", 5_000, 10, 5),
                    order("03", id("d3"), "NORMAL", 3_000, 10, 5));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("a1", 10_000, 40, 20), vehicle("a2", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips()).hasSize(2);
            assertThat(orderNumbersOf(proposal.trips().get(0), orders)).containsExactly("TO-01");
            assertThat(orderNumbersOf(proposal.trips().get(1), orders)).containsExactly("TO-02", "TO-03");
            assertThat(proposal.unplanned()).isEmpty();
        }

        @Test
        @DisplayName("every dimension is a limit: pallets alone can close a truck")
        void palletsCanBeTheBindingDimension() {
            // Weight and volume are trivial; only the pallet count decides.
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 10, 0.1, 12),
                    order("02", id("d2"), "NORMAL", 10, 0.1, 12));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("a1", 10_000, 40, 20), vehicle("a2", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips()).hasSize(2);
            assertThat(proposal.unplanned()).isEmpty();
        }

        @Test
        @DisplayName("an order larger than any vehicle is reported, not retried against the whole fleet")
        void orderTooBigForTheFleet() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 50_000, 1, 1),
                    order("02", id("d2"), "NORMAL", 500, 1, 1));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("a1", 10_000, 40, 20), vehicle("a2", 8_000, 30, 15)), List.of());

            assertThat(proposal.unplanned())
                    .extracting(UnplannedOrder::orderNumber, UnplannedOrder::reason)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("TO-01",
                                    UnplannedReason.EXCEEDS_LARGEST_VEHICLE));
            assertThat(proposal.trips())
                    .as("the oversized order must not consume a truck on its way to being refused")
                    .hasSize(1);
            assertThat(orderNumbersOf(proposal.trips().get(0), orders)).containsExactly("TO-02");
        }

        @Test
        @DisplayName("orders left over when the fleet runs out say so")
        void fleetRunsOut() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 9_000, 1, 1),
                    order("02", id("d2"), "NORMAL", 9_000, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.unplanned())
                    .extracting(UnplannedOrder::orderNumber, UnplannedOrder::reason)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("TO-02",
                                    UnplannedReason.NO_VEHICLE_AVAILABLE));
        }

        @Test
        @DisplayName("the biggest vehicle is used first, so what is left fits what is left")
        void heaviestVehicleFirst() {
            // The fleet arrives sorted heaviest-first, which is the port's contract.
            List<PlannableOrder> orders = List.of(order("01", id("d1"), "NORMAL", 9_000, 1, 1));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("big", 10_000, 40, 20), vehicle("small", 3_000, 10, 5)), List.of());

            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.trips().get(0).vehicleId()).isEqualTo(id("big"));
        }
    }

    // --- corridors --------------------------------------------------------------------------

    @Nested
    @DisplayName("corridors")
    class Corridors {

        @Test
        @DisplayName("a trip never mixes two corridors, even when they would fit on one truck")
        void neverMixesCorridors() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 100, 1, 1),
                    order("02", id("d2"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("a1", 10_000, 40, 20), vehicle("a2", 10_000, 40, 20)),
                    List.of(route("r1", id("d1")), route("r2", id("d2"))));

            assertThat(proposal.trips())
                    .as("both orders fit one truck by weight; a plan that visited two unrelated "
                            + "corridors is one nobody would drive")
                    .hasSize(2);
            assertThat(proposal.trips().get(0).routeId()).isEqualTo(id("r1"));
            assertThat(proposal.trips().get(1).routeId()).isEqualTo(id("r2"));
        }

        @Test
        @DisplayName("destinations on no route form one group of their own, planned last")
        void offCorridorOrdersGroupTogether() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d9"), "NORMAL", 100, 1, 1),
                    order("02", id("d1"), "NORMAL", 100, 1, 1),
                    order("03", id("d8"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders,
                    List.of(vehicle("a1", 10_000, 40, 20), vehicle("a2", 10_000, 40, 20)),
                    List.of(route("r1", id("d1"))));

            assertThat(proposal.trips()).hasSize(2);
            assertThat(proposal.trips().get(0).routeId()).isEqualTo(id("r1"));
            assertThat(orderNumbersOf(proposal.trips().get(0), orders)).containsExactly("TO-02");
            assertThat(proposal.trips().get(1).routeId())
                    .as("no corridor is a real answer, not a missing one")
                    .isNull();
            assertThat(orderNumbersOf(proposal.trips().get(1), orders)).containsExactly("TO-01", "TO-03");
        }

        @Test
        @DisplayName("stops follow the master route's sequence, not the order the orders arrived in")
        void stopsFollowTheCorridor() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d3"), "NORMAL", 100, 1, 1),
                    order("02", id("d1"), "NORMAL", 100, 1, 1),
                    order("03", id("d2"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)),
                    List.of(route("r1", id("d1"), id("d2"), id("d3"))));

            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.trips().get(0).stopLocationIds())
                    .containsExactly(id("d1"), id("d2"), id("d3"));
        }

        @Test
        @DisplayName("two orders to one place are one stop, not two")
        void oneStopPerDestination() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 100, 1, 1),
                    order("02", id("d1"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips().get(0).orderIds()).hasSize(2);
            assertThat(proposal.trips().get(0).stopLocationIds()).containsExactly(id("d1"));
        }
    }

    // --- ordering and reproducibility ---------------------------------------------------------

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("urgent orders are loaded before the rest, so a full truck leaves them behind last")
        void priorityWins() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "LOW", 6_000, 1, 1),
                    order("02", id("d2"), "URGENT", 6_000, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            assertThat(proposal.trips()).hasSize(1);
            assertThat(orderNumbersOf(proposal.trips().get(0), orders)).containsExactly("TO-02");
            assertThat(proposal.unplanned()).extracting(UnplannedOrder::orderNumber).containsExactly("TO-01");
        }

        @Test
        @DisplayName("an unknown priority sorts last instead of throwing")
        void unknownPriorityIsTolerated() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "SOMETHING_ELSE", 100, 1, 1),
                    order("02", id("d2"), "NORMAL", 100, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            assertThat(orderNumbersOf(proposal.trips().get(0), orders)).containsExactly("TO-02", "TO-01");
        }

        @Test
        @DisplayName("the same input plans the same way twice")
        void isReproducible() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d2"), "NORMAL", 3_000, 5, 3),
                    order("02", id("d1"), "NORMAL", 3_000, 5, 3),
                    order("03", id("d3"), "HIGH", 3_000, 5, 3),
                    order("04", id("d9"), "LOW", 3_000, 5, 3));
            List<VehicleCapacityReference> fleet =
                    List.of(vehicle("a1", 7_000, 40, 20), vehicle("a2", 7_000, 40, 20));
            List<RouteTemplate> routes = List.of(route("r1", id("d1"), id("d2"), id("d3")));

            PlanningProposal first = plan(orders, fleet, routes);
            PlanningProposal second = plan(orders, fleet, routes);

            assertThat(second)
                    .as("a planner who asks 'why did it do that' needs the answer to still be true "
                            + "the second time they run it")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("every order comes back exactly once, planned or reported")
        void nothingIsLost() {
            List<PlannableOrder> orders = List.of(
                    order("01", id("d1"), "NORMAL", 9_000, 1, 1),
                    order("02", id("d2"), "NORMAL", 9_000, 1, 1),
                    order("03", id("d3"), "NORMAL", 99_000, 1, 1),
                    order("04", id("d1"), "NORMAL", 500, 1, 1));

            PlanningProposal proposal = plan(orders, List.of(vehicle("a1", 10_000, 40, 20)), List.of());

            List<UUID> planned = proposal.trips().stream().flatMap(trip -> trip.orderIds().stream()).toList();
            List<UUID> reported = proposal.unplanned().stream().map(UnplannedOrder::orderId).toList();

            assertThat(planned).doesNotHaveDuplicates();
            assertThat(reported).doesNotHaveDuplicates();
            assertThat(java.util.stream.Stream.concat(planned.stream(), reported.stream()).toList())
                    .containsExactlyInAnyOrderElementsOf(orders.stream().map(PlannableOrder::id).toList());
        }
    }
}
