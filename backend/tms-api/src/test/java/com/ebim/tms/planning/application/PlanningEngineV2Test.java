package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The V2 planning engine, proved as what it is: a pure function (JOB 05).
 *
 * <p>Same discipline as {@code HeuristicPlanningEngineTest} - no database, no Spring, no clock - and
 * for the same reason: an engine that reached for any of those would be shipped unproven on a host
 * with no Docker. Distances arrive as a {@link TravelMatrix} built by hand here, which is exactly
 * how {@code AutoPlanningService} supplies them in production.
 */
class PlanningEngineV2Test {

    private static final UUID ORIGIN = id("origin");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    private final PlanningEngineV2 engine = new PlanningEngineV2();

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    private static PlannableOrder order(String suffix, UUID destination, double weightKg) {
        return order(suffix, destination, "NORMAL", weightKg, OrderAmounts.NONE, null);
    }

    private static PlannableOrder order(String suffix, UUID destination, String priority, double weightKg,
            OrderAmounts allocated, LocalTime windowEnd) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, destination, null, null, DATE, priority,
                null, windowEnd, BigDecimal.valueOf(weightKg), BigDecimal.ONE, BigDecimal.ONE,
                null, null, allocated);
    }

    private static VehicleCapacityReference vehicle(String suffix, double maxWeightKg) {
        return new VehicleCapacityReference(id(suffix), "VEH-" + suffix, "PLT-" + suffix, null, null,
                id("type-" + suffix), "TYPE", BigDecimal.valueOf(maxWeightKg), BigDecimal.valueOf(1000),
                1000, true, "AVAILABLE");
    }

    private static RouteTemplate route(String suffix, UUID... destinations) {
        return new RouteTemplate(id(suffix), "RT-" + suffix, "Route " + suffix, ORIGIN, List.of(destinations), true);
    }

    /** A leg, with distance in km and drive time in minutes. */
    private static TravelEstimate leg(double km, int minutes) {
        return TravelEstimate.computed(BigDecimal.valueOf(km), Duration.ofMinutes(minutes), "TEST",
                RoutingSource.PROVIDER, OffsetDateTime.parse("2026-08-21T06:00:00Z"));
    }

    private PlanningProposal plan(List<PlannableOrder> orders, List<VehicleCapacityReference> vehicles,
            List<RouteTemplate> routes, TravelMatrix travel, PlanningShift shift) {
        return engine.plan(new PlanningInput(ORIGIN, DATE, orders, vehicles, routes, travel, Map.of(), shift));
    }

    private PlanningProposal plan(List<PlannableOrder> orders, List<VehicleCapacityReference> vehicles,
            List<RouteTemplate> routes) {
        return plan(orders, vehicles, routes, TravelMatrix.EMPTY, PlanningShift.DEFAULT);
    }

    // --- ship units ------------------------------------------------------------------

    @Nested
    @DisplayName("it plans what is actually outstanding")
    class Outstanding {

        /**
         * The improvement V1 cannot make. An order half loaded already needs half a truck, and
         * packing its whole weight would reserve capacity nobody needs.
         */
        @Test
        @DisplayName("a part-allocated order is packed against its remainder, not its total")
        void packsThePending() {
            UUID destination = id("d1");
            // 8,000 kg ordered, 6,000 already on another truck: 2,000 outstanding.
            PlannableOrder partly = order("a", destination, "NORMAL", 8_000,
                    new OrderAmounts(BigDecimal.valueOf(6_000), BigDecimal.ZERO, BigDecimal.ZERO), null);
            PlannableOrder other = order("b", destination, 2_500);

            PlanningProposal proposal = plan(List.of(partly, other), List.of(vehicle("t", 5_000)), List.of());

            // 2,000 + 2,500 fits a 5,000 kg truck. Against the totals it would not have.
            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.trips().get(0).orderIds()).containsExactlyInAnyOrder(partly.id(), other.id());
            assertThat(proposal.unplanned()).isEmpty();
        }

        @Test
        @DisplayName("an order already wholly on trips is reported as finished, not as a failure")
        void fullyAllocatedIsNotAFailure() {
            UUID destination = id("d1");
            PlannableOrder done = order("a", destination, "NORMAL", 3_000,
                    new OrderAmounts(BigDecimal.valueOf(3_000), BigDecimal.ONE, BigDecimal.ONE), null);

            PlanningProposal proposal = plan(List.of(done), List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.trips()).isEmpty();
            assertThat(proposal.unplanned()).singleElement()
                    .extracting(PlanningProposal.UnplannedOrder::reason)
                    .isEqualTo(UnplannedReason.FULLY_ALLOCATED);
        }

        @Test
        @DisplayName("an order whose measures are all unknown is planned, not treated as finished")
        void anOrderWithNothingKnownIsStillPlannable() {
            UUID destination = id("d1");
            PlannableOrder unknown = new PlannableOrder(id("a"), "TO-a", ORIGIN, destination, null, null, DATE,
                    "NORMAL", null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                    OrderAmounts.NONE);

            PlanningProposal proposal = plan(List.of(unknown), List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.trips()).hasSize(1);
        }
    }

    // --- sequencing ------------------------------------------------------------------

    @Nested
    @DisplayName("it sequences stops by distance")
    class Sequencing {

        /**
         * The change that moves kilometres. Loading order puts the far stop first; V2 walks
         * nearest-neighbour from the origin and visits the near one first.
         */
        @Test
        @DisplayName("stops are visited nearest-first, not in the order the orders loaded")
        void nearestFirst() {
            UUID far = id("far");
            UUID near = id("near");
            // The far order sorts first by order number, so insertion order would be far -> near.
            PlannableOrder toFar = order("a-far", far, 1_000);
            PlannableOrder toNear = order("b-near", near, 1_000);

            TravelMatrix travel = new TravelMatrix.Builder()
                    .add(ORIGIN, far, leg(100, 120)).add(ORIGIN, near, leg(10, 20))
                    .add(far, near, leg(95, 110)).add(near, far, leg(95, 110))
                    .build();

            PlanningProposal proposal = plan(List.of(toFar, toNear), List.of(vehicle("t", 10_000)), List.of(),
                    travel, PlanningShift.DEFAULT);

            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.trips().get(0).stopLocationIds()).containsExactly(near, far);
        }

        @Test
        @DisplayName("with no distances known, the sequence is the loading order - exactly V1's behaviour")
        void degradesWithoutAMatrix() {
            UUID first = id("d1");
            UUID second = id("d2");

            PlanningProposal proposal = plan(
                    List.of(order("a", first, 1_000), order("b", second, 1_000)),
                    List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.trips().get(0).stopLocationIds()).containsExactly(first, second);
            assertThat(proposal.kpis().totalDistanceKm()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("two orders to the same destination are one stop")
        void oneStopPerDestination() {
            UUID destination = id("d1");

            PlanningProposal proposal = plan(
                    List.of(order("a", destination, 1_000), order("b", destination, 1_000)),
                    List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.trips().get(0).stopLocationIds()).containsExactly(destination);
            assertThat(proposal.trips().get(0).orderIds()).hasSize(2);
        }
    }

    // --- the shift -------------------------------------------------------------------

    @Nested
    @DisplayName("it refuses a trip that cannot be driven in a shift")
    class Shift {

        @Test
        @DisplayName("a run longer than the shift is split across vehicles rather than proposed")
        void splitsRatherThanOverrunning() {
            UUID near = id("near");
            UUID far = id("far");
            TravelMatrix travel = new TravelMatrix.Builder()
                    .add(ORIGIN, near, leg(50, 60)).add(ORIGIN, far, leg(200, 240))
                    .add(near, far, leg(180, 220)).add(far, near, leg(180, 220))
                    .build();
            // A four-hour shift. Near alone is 60 min and far alone is 240, so each is reachable
            // on its own; together they are 60 + 220 = 280 and do not fit. That is the case worth
            // asserting - a shift limit that only ever refuses whole stops would never split one.
            PlanningShift shortShift = new PlanningShift(LocalTime.of(6, 0), Duration.ofHours(4));

            PlanningProposal proposal = plan(
                    List.of(order("a", near, 1_000), order("b", far, 1_000)),
                    List.of(vehicle("t1", 10_000), vehicle("t2", 10_000)), List.of(), travel, shortShift);

            assertThat(proposal.trips()).hasSize(2);
            assertThat(proposal.unplanned()).isEmpty();
        }

        @Test
        @DisplayName("a single stop beyond the shift is reported as EXCEEDS_SHIFT, not as no vehicle")
        void unreachableInAShift() {
            UUID far = id("far");
            TravelMatrix travel = new TravelMatrix.Builder().add(ORIGIN, far, leg(900, 900)).build();
            PlanningShift shortShift = new PlanningShift(LocalTime.of(6, 0), Duration.ofHours(8));

            PlanningProposal proposal = plan(List.of(order("a", far, 1_000)),
                    List.of(vehicle("t", 10_000)), List.of(), travel, shortShift);

            assertThat(proposal.trips()).isEmpty();
            // The distinction matters: "no vehicle" would send a planner looking for another truck
            // while the whole fleet sits idle.
            assertThat(proposal.unplanned()).singleElement()
                    .extracting(PlanningProposal.UnplannedOrder::reason)
                    .isEqualTo(UnplannedReason.EXCEEDS_SHIFT);
        }

        @Test
        @DisplayName("with no distances, nothing is ever refused for the shift")
        void noDistancesNoShiftRefusals() {
            PlanningShift oneMinute = new PlanningShift(LocalTime.of(6, 0), Duration.ofMinutes(1));

            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 1_000)),
                    List.of(vehicle("t", 10_000)), List.of(), TravelMatrix.EMPTY, oneMinute);

            assertThat(proposal.trips()).hasSize(1);
        }
    }

    // --- KPIs ------------------------------------------------------------------------

    @Nested
    @DisplayName("it scores what it proposed")
    class Kpis {

        @Test
        @DisplayName("distance, duration, trips, vehicles and utilisation come out of the proposal")
        void reportsTheDay() {
            UUID d1 = id("d1");
            TravelMatrix travel = new TravelMatrix.Builder().add(ORIGIN, d1, leg(30, 40)).build();

            PlanningProposal proposal = plan(List.of(order("a", d1, 5_000)),
                    List.of(vehicle("t", 10_000)), List.of(), travel, PlanningShift.DEFAULT);

            PlanningKpis kpis = proposal.kpis();
            assertThat(kpis.trips()).isEqualTo(1);
            assertThat(kpis.vehicles()).isEqualTo(1);
            assertThat(kpis.plannedOrders()).isEqualTo(1);
            assertThat(kpis.unplannedOrders()).isZero();
            assertThat(kpis.totalDistanceKm()).isEqualByComparingTo("30.000");
            assertThat(kpis.totalDurationMinutes()).isEqualTo(40);
            assertThat(kpis.weightUtilizationPercent()).isEqualByComparingTo("50.0");
            assertThat(kpis.plannedRatePercent()).isEqualByComparingTo("100.0");
        }

        /**
         * A cost nobody can compute must stay absent. Filling it with something plausible is the
         * one outcome that would be worse than leaving it out, because two engines would be
         * compared on it.
         */
        @Test
        @DisplayName("cost is null, because pricing a proposal is not this job's")
        void costIsNotInvented() {
            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 1_000)),
                    List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.kpis().totalCost()).isNull();
        }

        @Test
        @DisplayName("utilisation is null rather than zero when no vehicle declares a limit")
        void unknownCapacityIsNotAnEmptyTruck() {
            VehicleCapacityReference unlimited = new VehicleCapacityReference(id("u"), "VEH-u", "PLT-u", null,
                    null, id("type-u"), "TYPE", null, null, null, true, "AVAILABLE");

            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 1_000)), List.of(unlimited), List.of());

            assertThat(proposal.kpis().weightUtilizationPercent()).isNull();
        }

        @Test
        @DisplayName("an order arriving after its window closes is counted late, not refused")
        void latenessIsCountedNotRefused() {
            UUID far = id("far");
            TravelMatrix travel = new TravelMatrix.Builder().add(ORIGIN, far, leg(300, 300)).build();
            // Departs 06:00, arrives 11:00, the customer wanted it by 09:00.
            PlannableOrder tight = order("a", far, "NORMAL", 1_000, OrderAmounts.NONE, LocalTime.of(9, 0));

            PlanningProposal proposal = plan(List.of(tight), List.of(vehicle("t", 10_000)), List.of(),
                    travel, PlanningShift.DEFAULT);

            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.kpis().lateOrders()).isEqualTo(1);
        }

        @Test
        @DisplayName("an order with no window is never late")
        void noWindowIsNeverLate() {
            UUID far = id("far");
            TravelMatrix travel = new TravelMatrix.Builder().add(ORIGIN, far, leg(300, 300)).build();

            PlanningProposal proposal = plan(List.of(order("a", far, 1_000)),
                    List.of(vehicle("t", 10_000)), List.of(), travel, PlanningShift.DEFAULT);

            assertThat(proposal.kpis().lateOrders()).isZero();
        }

        @Test
        @DisplayName("a plan built on estimated distances says so")
        void estimatedIsCarriedOntoTheKpis() {
            TravelEstimate estimated = TravelEstimate.computed(BigDecimal.TEN, Duration.ofMinutes(20), "LOCAL",
                    RoutingSource.FALLBACK, OffsetDateTime.parse("2026-08-21T06:00:00Z"));
            TravelMatrix travel = new TravelMatrix.Builder().add(ORIGIN, id("d1"), estimated).build();

            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 1_000)),
                    List.of(vehicle("t", 10_000)), List.of(), travel, PlanningShift.DEFAULT);

            assertThat(proposal.kpis().distanceEstimated()).isTrue();
        }
    }

    // --- the properties every engine must keep ---------------------------------------

    @Nested
    @DisplayName("the guarantees it shares with V1")
    class SharedGuarantees {

        @Test
        @DisplayName("every order is either on a trip or in the unplanned list, never both, never neither")
        void nothingIsLost() {
            List<PlannableOrder> orders = List.of(
                    order("a", id("d1"), 4_000), order("b", id("d2"), 4_000), order("c", id("d3"), 40_000));

            PlanningProposal proposal = plan(orders, List.of(vehicle("t", 5_000)), List.of());

            long planned = proposal.trips().stream().mapToLong(trip -> trip.orderIds().size()).sum();
            assertThat(planned + proposal.unplanned().size()).isEqualTo(orders.size());
        }

        @Test
        @DisplayName("the same input plans the same way twice")
        void reproducible() {
            List<PlannableOrder> orders = List.of(order("a", id("d1"), 4_000), order("b", id("d2"), 4_000));
            List<VehicleCapacityReference> fleet = List.of(vehicle("t1", 5_000), vehicle("t2", 5_000));
            TravelMatrix travel = new TravelMatrix.Builder()
                    .add(ORIGIN, id("d1"), leg(10, 15)).add(ORIGIN, id("d2"), leg(20, 25)).build();

            PlanningProposal first = plan(orders, fleet, List.of(), travel, PlanningShift.DEFAULT);
            PlanningProposal second = plan(orders, fleet, List.of(), travel, PlanningShift.DEFAULT);

            assertThat(second.trips()).isEqualTo(first.trips());
            assertThat(second.kpis()).isEqualTo(first.kpis());
        }

        @Test
        @DisplayName("an empty fleet plans nothing and says why")
        void noFleet() {
            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 1_000)), List.of(), List.of());

            assertThat(proposal.unplanned()).singleElement()
                    .extracting(PlanningProposal.UnplannedOrder::reason)
                    .isEqualTo(UnplannedReason.NO_FLEET);
        }

        @Test
        @DisplayName("an order larger than every vehicle is reported before any packing is attempted")
        void exceedsLargestVehicle() {
            PlanningProposal proposal = plan(List.of(order("a", id("d1"), 90_000)),
                    List.of(vehicle("t", 10_000)), List.of());

            assertThat(proposal.unplanned()).singleElement()
                    .extracting(PlanningProposal.UnplannedOrder::reason)
                    .isEqualTo(UnplannedReason.EXCEEDS_LARGEST_VEHICLE);
        }

        @Test
        @DisplayName("a trip never mixes corridors")
        void corridorsAreNotMixed() {
            UUID north = id("north");
            UUID south = id("south");

            PlanningProposal proposal = plan(
                    List.of(order("a", north, 1_000), order("b", south, 1_000)),
                    List.of(vehicle("t1", 10_000), vehicle("t2", 10_000)),
                    List.of(route("N", north), route("S", south)));

            assertThat(proposal.trips()).hasSize(2);
            for (ProposedTrip trip : proposal.trips()) {
                assertThat(trip.stopLocationIds()).hasSize(1);
            }
        }

        @Test
        @DisplayName("an inactive route is not a corridor")
        void inactiveRoutesAreIgnored() {
            UUID destination = id("d1");
            RouteTemplate dormant = new RouteTemplate(id("R"), "RT-R", "Route R", ORIGIN,
                    List.of(destination), false);

            PlanningProposal proposal = plan(List.of(order("a", destination, 1_000)),
                    List.of(vehicle("t", 10_000)), List.of(dormant));

            // Off-corridor, so routeId is null - not the inactive route's id.
            assertThat(proposal.trips()).hasSize(1);
            assertThat(proposal.trips().get(0).routeId()).isNull();
        }

        @Test
        @DisplayName("it names itself, so a plan can be traced to the rules that made it")
        void named() {
            assertThat(engine.name()).isEqualTo("PLANNING_V2");
            assertThat(plan(List.of(), List.of(), List.of()).engine()).isEqualTo("PLANNING_V2");
        }
    }
}
