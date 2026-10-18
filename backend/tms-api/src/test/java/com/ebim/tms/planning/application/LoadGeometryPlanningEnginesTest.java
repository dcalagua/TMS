package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.planning.application.PlanningProposal.UnplannedReason;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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
}
