package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code HEURISTIC_V1} against {@code PLANNING_V2}, on datasets both engines get identically
 * (JOB 05).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The brief asks for the two to be compared on reproducible fixtures, and that is a stronger
 * requirement than it looks. Without it, "V2 is better" is an assertion about code somebody wrote;
 * with it, the difference is a number that either appears or does not. These fixtures are built
 * once and handed to both engines unchanged, so any difference in the result is a difference in the
 * engines and in nothing else.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>V2 is not universally better and this suite does not pretend it is. It is better on the axis it
 * was built for - kilometres, when distances are known - and it is deliberately identical to V1
 * where V1 was already right. The case where V2 plans <em>fewer</em> orders is included on purpose:
 * refusing a shipment that cannot be driven in a shift is the correct answer, and a comparison that
 * only showed the flattering cases would be marketing rather than evidence.
 */
class PlanningEngineComparisonTest {

    private static final UUID ORIGIN = id("origin");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 21);

    private final HeuristicPlanningEngine v1 = new HeuristicPlanningEngine();
    private final PlanningEngineV2 v2 = new PlanningEngineV2();

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    private static PlannableOrder order(String suffix, UUID destination, double weightKg) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, destination, null, null, DATE, "NORMAL",
                null, null, BigDecimal.valueOf(weightKg), BigDecimal.ONE, BigDecimal.ONE, null, null,
                OrderAmounts.NONE);
    }

    private static VehicleCapacityReference vehicle(String suffix, double maxWeightKg) {
        return new VehicleCapacityReference(id(suffix), "VEH-" + suffix, "PLT-" + suffix, null, null,
                id("type-" + suffix), "TYPE", BigDecimal.valueOf(maxWeightKg), BigDecimal.valueOf(1000),
                1000, true, "AVAILABLE");
    }

    private static TravelEstimate leg(double km, int minutes) {
        return TravelEstimate.computed(BigDecimal.valueOf(km), Duration.ofMinutes(minutes), "TEST",
                RoutingSource.PROVIDER, OffsetDateTime.parse("2026-08-21T06:00:00Z"));
    }

    /**
     * Six destinations on a line out from the origin, deliberately fed to the engines in an order
     * that is not the driving order. The distances are symmetric and consistent, so the tour V2
     * finds is a real tour and not an artefact of an impossible matrix.
     */
    private static final class LineCorridor {

        final List<UUID> destinations = new ArrayList<>();
        final List<PlannableOrder> orders = new ArrayList<>();
        final TravelMatrix travel;
        /** How far each destination is from the origin, in km, by index. */
        private static final int[] KM = {10, 25, 40, 55, 70, 85};

        LineCorridor() {
            for (int i = 0; i < KM.length; i++) {
                destinations.add(id("stop-" + i));
            }
            // Loading order: farthest first, which is the worst case for "visit in loading order".
            for (int i = KM.length - 1; i >= 0; i--) {
                orders.add(order("o" + (KM.length - 1 - i), destinations.get(i), 1_000));
            }

            TravelMatrix.Builder builder = new TravelMatrix.Builder();
            for (int i = 0; i < KM.length; i++) {
                builder.add(ORIGIN, destinations.get(i), leg(KM[i], KM[i]));
                for (int j = 0; j < KM.length; j++) {
                    if (i != j) {
                        int km = Math.abs(KM[i] - KM[j]);
                        builder.add(destinations.get(i), destinations.get(j), leg(km, km));
                    }
                }
            }
            travel = builder.build();
        }

        PlanningInput input(List<VehicleCapacityReference> fleet, PlanningShift shift) {
            return new PlanningInput(ORIGIN, DATE, orders, fleet, List.of(), travel, Map.of(), shift);
        }
    }

    /**
     * The headline. Same orders, same fleet, same distances: V1 drives them in loading order,
     * V2 walks them nearest-first.
     */
    @Test
    @DisplayName("on a corridor fed in the wrong order, V2 drives materially fewer kilometres")
    void v2DrivesFewerKilometres() {
        LineCorridor dataset = new LineCorridor();
        List<VehicleCapacityReference> fleet = List.of(vehicle("big", 100_000));
        // A generous shift, so the only difference between the engines is the sequence.
        PlanningShift shift = new PlanningShift(null, Duration.ofHours(24));

        PlanningProposal fromV1 = v1.plan(dataset.input(fleet, shift));
        PlanningProposal fromV2 = v2.plan(dataset.input(fleet, shift));

        // Both plan every order onto one truck - the loads are identical.
        assertThat(fromV1.trips()).hasSize(1);
        assertThat(fromV2.trips()).hasSize(1);
        assertThat(fromV2.trips().get(0).orderIds())
                .containsExactlyInAnyOrderElementsOf(fromV1.trips().get(0).orderIds());

        // The stops differ, and that is the whole of V2's contribution here.
        assertThat(fromV1.trips().get(0).stopLocationIds())
                .as("V1 visits in loading order: farthest first")
                .containsExactlyElementsOf(dataset.destinations.reversed());
        assertThat(fromV2.trips().get(0).stopLocationIds())
                .as("V2 walks out from the origin, nearest first")
                .containsExactlyElementsOf(dataset.destinations);

        // 85 + 15*5 = 160 km driving out-and-back-along-the-line versus 85 km straight out.
        BigDecimal v2Km = fromV2.kpis().totalDistanceKm();
        assertThat(v2Km).isEqualByComparingTo("85.000");
        assertThat(measure(fromV1, dataset)).isGreaterThan(v2Km);
    }

    @Test
    @DisplayName("with no distances known at all, the two engines produce the same loads")
    void identicalWithoutAMatrix() {
        List<PlannableOrder> orders = List.of(
                order("a", id("d1"), 4_000), order("b", id("d2"), 4_000), order("c", id("d3"), 4_000));
        List<VehicleCapacityReference> fleet = List.of(vehicle("t1", 9_000), vehicle("t2", 9_000));
        PlanningInput input = new PlanningInput(ORIGIN, DATE, orders, fleet, List.of());

        PlanningProposal fromV1 = v1.plan(input);
        PlanningProposal fromV2 = v2.plan(input);

        // Same trips, same orders on each, same stops. V2's improvement is in sequencing, and with
        // nothing to sequence by it must not drift away from the behaviour a planner knows.
        assertThat(fromV2.trips()).isEqualTo(fromV1.trips());
        assertThat(fromV2.unplanned()).hasSameSizeAs(fromV1.unplanned());
    }

    /**
     * Included deliberately. V2 plans fewer orders here, and it is right to: the refused shipment
     * cannot be driven in the shift, and proposing it would produce a board that looks full and a
     * driver who runs out of hours.
     */
    @Test
    @DisplayName("V2 plans fewer orders than V1 when the day does not fit a shift, and says why")
    void v2RefusesWhatCannotBeDriven() {
        LineCorridor dataset = new LineCorridor();
        List<VehicleCapacityReference> fleet = List.of(vehicle("big", 100_000));
        // Two hours: only the nearest stop or two are reachable and servable.
        PlanningShift tight = new PlanningShift(null, Duration.ofHours(1));

        PlanningProposal fromV1 = v1.plan(dataset.input(fleet, tight));
        PlanningProposal fromV2 = v2.plan(dataset.input(fleet, tight));

        long plannedByV1 = fromV1.trips().stream().mapToLong(trip -> trip.orderIds().size()).sum();
        long plannedByV2 = fromV2.trips().stream().mapToLong(trip -> trip.orderIds().size()).sum();

        assertThat(plannedByV1).as("V1 knows nothing about shifts and plans the whole day")
                .isEqualTo(dataset.orders.size());
        assertThat(plannedByV2).as("V2 stops at what can actually be driven").isLessThan(plannedByV1);
        assertThat(fromV2.unplanned())
                .allMatch(unplanned -> unplanned.reason() == PlanningProposal.UnplannedReason.EXCEEDS_SHIFT
                        || unplanned.reason() == PlanningProposal.UnplannedReason.NO_VEHICLE_AVAILABLE);
    }

    @Test
    @DisplayName("only V2 reports KPIs; V1 reports none rather than zeros that look like a scored plan")
    void onlyV2Scores() {
        LineCorridor dataset = new LineCorridor();
        List<VehicleCapacityReference> fleet = List.of(vehicle("big", 100_000));
        PlanningShift shift = new PlanningShift(null, Duration.ofHours(24));

        assertThat(v1.plan(dataset.input(fleet, shift)).kpis()).isEqualTo(PlanningKpis.NONE);
        assertThat(v2.plan(dataset.input(fleet, shift)).kpis().totalDistanceKm()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("both engines account for every order, on every dataset")
    void neitherLosesAnOrder() {
        LineCorridor dataset = new LineCorridor();
        for (PlanningShift shift : List.of(new PlanningShift(null, Duration.ofHours(1)),
                new PlanningShift(null, Duration.ofHours(24)))) {
            for (List<VehicleCapacityReference> fleet : List.of(
                    List.of(vehicle("big", 100_000)),
                    List.of(vehicle("s1", 2_500), vehicle("s2", 2_500)))) {
                for (PlanningProposal proposal : List.of(
                        v1.plan(dataset.input(fleet, shift)), v2.plan(dataset.input(fleet, shift)))) {
                    long planned = proposal.trips().stream().mapToLong(trip -> trip.orderIds().size()).sum();
                    assertThat(planned + proposal.unplanned().size())
                            .as("%s lost an order", proposal.engine())
                            .isEqualTo(dataset.orders.size());
                }
            }
        }
    }

    /** The distance a proposal's stop sequence actually costs, for the engine that does not score. */
    private static BigDecimal measure(PlanningProposal proposal, LineCorridor dataset) {
        BigDecimal total = BigDecimal.ZERO;
        for (PlanningProposal.ProposedTrip trip : proposal.trips()) {
            UUID at = ORIGIN;
            for (UUID stop : trip.stopLocationIds()) {
                total = total.add(dataset.travel.distanceKm(at, stop));
                at = stop;
            }
        }
        return total;
    }
}
