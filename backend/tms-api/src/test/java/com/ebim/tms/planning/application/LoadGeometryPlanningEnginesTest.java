package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.geometry.GeometryLine;
import com.ebim.tms.shared.geometry.OrderGeometry;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import com.ebim.tms.shared.reference.OrderGeometryProfile;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Both engines with load geometry (ADR-022 section 7): they keep calling
 * {@link CapacityLimits#accommodates}, and that is enough for nestables to stop opening trucks.
 */
class LoadGeometryPlanningEnginesTest {

    private static final UUID ORIGIN = id("origin");
    private static final UUID DESTINATION = id("destination");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);

    static Stream<PlanningEngine> engines() {
        return Stream.of(new HeuristicPlanningEngine(), new PlanningEngineV2());
    }

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    /** 1,000 PLP-571 cups: naive 1.93 m3, effective 0.1503 m3 on 0.1103 m2, stacks 1.363 m. */
    private static PlannableOrder cups(String suffix, boolean measured) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, DESTINATION, null, null, DATE, "NORMAL", null,
                null, new BigDecimal("120"), new BigDecimal("1.9294"), BigDecimal.ZERO, null, null, OrderAmounts.NONE,
                measured ? new BigDecimal("0.1503") : null, measured ? new BigDecimal("0.1103") : null,
                measured ? new BigDecimal("1.363") : null, measured ? new BigDecimal("0.175") : null);
    }

    private static PlannableOrder tall(String suffix) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, DESTINATION, null, null, DATE, "NORMAL", null,
                null, new BigDecimal("50"), new BigDecimal("1.25"), BigDecimal.ZERO, null, null, OrderAmounts.NONE,
                new BigDecimal("1.25"), new BigDecimal("0.50"), new BigDecimal("2.500"), new BigDecimal("2.500"));
    }

    private static PlannableOrder floorHungry(String suffix) {
        return new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, DESTINATION, null, null, DATE, "NORMAL", null,
                null, new BigDecimal("500"), new BigDecimal("0.9"), BigDecimal.ZERO, null, null, OrderAmounts.NONE,
                new BigDecimal("0.9"), new BigDecimal("8"), new BigDecimal("0.600"), new BigDecimal("0.300"));
    }

    /** A small van: 2.0 m3, 6 x 2.4 floor at 0.85 = 12.24 m2, 2.4 m high. */
    private static VehicleCapacityReference van(String suffix) {
        return new VehicleCapacityReference(id(suffix), "VEH-" + suffix, "PLT-" + suffix, null, null,
                id("type"), "VAN", new BigDecimal("3000"), new BigDecimal("2.0"), 10, true, "AVAILABLE",
                new BigDecimal("6.00"), new BigDecimal("2.40"), new BigDecimal("2.40"), new BigDecimal("0.85"));
    }

    private static PlanningProposal plan(PlanningEngine engine, List<PlannableOrder> orders) {
        return engine.plan(new PlanningInput(ORIGIN, DATE, orders, List.of(van("a"), van("b")), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("two orders of nestables that need two trucks by naive volume fit one by geometry")
    void nestablesFitOneTruck(PlanningEngine engine) {
        PlanningProposal naive = plan(engine, List.of(cups("1", false), cups("2", false)));
        PlanningProposal measured = plan(engine, List.of(cups("1", true), cups("2", true)));

        assertThat(naive.trips()).hasSize(2);
        assertThat(measured.trips()).hasSize(1);
        assertThat(measured.trips().get(0).orderIds()).hasSize(2);
        assertThat(measured.unplanned()).isEmpty();
        assertThat(measured.trips().get(0).bindingDimension()).isEqualTo("HEIGHT");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("an order whose unit is taller than every vehicle is ITEM_TALLER_THAN_VEHICLE")
    void tallerThanEveryVehicle(PlanningEngine engine) {
        PlanningProposal proposal = plan(engine, List.of(tall("t"), cups("1", true)));

        assertThat(proposal.unplanned()).singleElement()
                .satisfies(order -> assertThat(order.reason()).isEqualTo(UnplannedReason.ITEM_TALLER_THAN_VEHICLE));
        assertThat(proposal.trips()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("the floor binds: two 8 m2 orders do not share a 12.24 m2 floor")
    void floorBinds(PlanningEngine engine) {
        PlanningProposal proposal = plan(engine, List.of(floorHungry("f1"), floorHungry("f2")));

        assertThat(proposal.trips()).hasSize(2);
        assertThat(proposal.trips()).allSatisfy(trip -> assertThat(trip.bindingDimension()).isEqualTo("FLOOR"));
    }

    // ---- re-stacking per vehicle (ADR-022 section 5) --------------------------------------------

    private static final BigDecimal REFERENCE = new BigDecimal("2.40");

    private static final PhysicalBlock PLP_571 = new PhysicalBlock(new BigDecimal("0.105"), new BigDecimal("0.105"),
            new BigDecimal("0.175"), StackMode.NESTABLE, 100, new BigDecimal("0.012"), null, false);

    private static final PhysicalBlock CRATE = new PhysicalBlock(new BigDecimal("0.60"), new BigDecimal("0.40"),
            new BigDecimal("0.30"), StackMode.STACKABLE, null, null, null, false);

    /** An order written from real lines at the reference height, with its profile - what the port returns. */
    private record Measured(PlannableOrder order, OrderGeometryProfile profile) {}

    private static Measured measured(String suffix, PhysicalBlock block, int quantity, String unitVolume,
            String weight) {
        BigDecimal naive = BigDecimal.valueOf(quantity).multiply(new BigDecimal(unitVolume));
        List<GeometryLine> lines = List.of(new GeometryLine(BigDecimal.valueOf(quantity), "UN", null, block, naive));
        OrderGeometry stored = OrderGeometry.of(lines, naive, REFERENCE);
        PlannableOrder order = new PlannableOrder(id(suffix), "TO-" + suffix, ORIGIN, DESTINATION, null, null, DATE,
                "NORMAL", null, null, new BigDecimal(weight), naive, BigDecimal.ZERO, null, null, OrderAmounts.NONE,
                stored.planningVolumeM3(), stored.floorAreaM2(), stored.maxStackHeightM(), stored.maxUnitHeightM());
        return new Measured(order, new OrderGeometryProfile(order.id(), REFERENCE, naive, stored.planningVolumeM3(),
                stored.floorAreaM2(), lines));
    }

    private static VehicleCapacityReference vehicle(String suffix, String weight, String volume, String heightM) {
        return new VehicleCapacityReference(id(suffix), "VEH-" + suffix, "PLT-" + suffix, null, null,
                id("type-" + suffix), "VAN", new BigDecimal(weight), new BigDecimal(volume), 10, true, "AVAILABLE",
                new BigDecimal("6.00"), new BigDecimal("2.40"), new BigDecimal(heightM), new BigDecimal("0.85"));
    }

    private static PlanningProposal plan(PlanningEngine engine, List<Measured> orders,
            List<VehicleCapacityReference> vehicles, boolean withProfiles) {
        Map<UUID, OrderGeometryProfile> profiles = withProfiles
                ? orders.stream().collect(java.util.stream.Collectors.toMap(m -> m.order().id(), Measured::profile))
                : Map.of();
        return engine.plan(new PlanningInput(ORIGIN, DATE, orders.stream().map(Measured::order).toList(), vehicles,
                List.of()).withGeometry(profiles));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("PLP-571 from real lines: 1,000 cups are 10 stacks and 0.1503 m3, and two orders share one 2.20 m van")
    void plapasaNestablesShareOneLowVan(PlanningEngine engine) {
        Measured first = measured("plp1", PLP_571, 1000, "0.0019294", "120");
        Measured second = measured("plp2", PLP_571, 1000, "0.0019294", "120");
        assertThat(first.profile().at(REFERENCE).lines().get(0).stacks()).isEqualTo(10);
        assertThat(first.order().planningVolumeM3()).isEqualByComparingTo("0.1503");
        List<VehicleCapacityReference> vans = List.of(vehicle("low-a", "3000", "2.0", "2.20"),
                vehicle("low-b", "3000", "2.0", "2.20"));

        // By naive volume (1.93 m3 each) every order needs its own van.
        PlannableOrder naiveFirst = cups("n1", false);
        PlannableOrder naiveSecond = cups("n2", false);
        assertThat(engine.plan(new PlanningInput(ORIGIN, DATE, List.of(naiveFirst, naiveSecond), vans, List.of()))
                .trips()).hasSize(2);

        PlanningProposal proposal = plan(engine, List.of(first, second), vans, true);
        assertThat(proposal.unplanned()).isEmpty();
        assertThat(proposal.trips()).singleElement()
                .satisfies(trip -> assertThat(trip.orderIds()).hasSize(2));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("2.20 m vans: crates stored as stacks of eight are re-stacked to seven and planned, not refused")
    void cratesRestackedForLowVans(PlanningEngine engine) {
        Measured crates = measured("crates", CRATE, 16, "0.072", "160");
        assertThat(crates.order().maxStackHeightM()).isEqualByComparingTo("2.400");
        List<VehicleCapacityReference> lowVans = List.of(vehicle("v220", "3000", "2.0", "2.20"));

        // Without re-stacking (no profile handed in) the 2.40 m stacks exceed every 2.20 m van.
        assertThat(plan(engine, List.of(crates), lowVans, false).unplanned()).singleElement()
                .satisfies(order -> assertThat(order.reason()).isEqualTo(UnplannedReason.EXCEEDS_LARGEST_VEHICLE));

        PlanningProposal proposal = plan(engine, List.of(crates), lowVans, true);
        assertThat(proposal.unplanned()).isEmpty();
        assertThat(proposal.trips()).singleElement()
                .satisfies(trip -> assertThat(trip.orderIds()).containsExactly(crates.order().id()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    @DisplayName("an order the next vehicle refuses tries the remaining ones: heaviest is not tallest")
    void refusedOrderTriesTheRemainingVehicles(PlanningEngine engine) {
        // The heaviest vehicle comes first and is 2.00 m high; the second is lighter and 2.60 m high.
        List<VehicleCapacityReference> fleet = List.of(vehicle("heavy-low", "9000", "20", "2.00"),
                vehicle("light-tall", "3000", "20", "2.60"));
        PhysicalBlock tallUnit = new PhysicalBlock(new BigDecimal("0.60"), new BigDecimal("0.40"),
                new BigDecimal("2.30"), StackMode.NONE, null, null, null, false);
        Measured tall = measured("tall-unit", tallUnit, 2, "0.552", "100");

        PlanningProposal proposal = plan(engine, List.of(tall), fleet, true);

        assertThat(proposal.unplanned()).isEmpty();
        assertThat(proposal.trips()).singleElement()
                .satisfies(trip -> assertThat(trip.vehicleId()).isEqualTo(id("light-tall")));
    }
}
