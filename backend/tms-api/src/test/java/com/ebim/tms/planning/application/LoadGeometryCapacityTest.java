package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Floor and height as capacity dimensions (V62, ADR-022 section 5): null unlimited, sums and maxima. */
class LoadGeometryCapacityTest {

    private static final UUID TRIP = UUID.fromString("11111111-0000-4000-8000-000000000062");

    private final PlanningCapacityService service = new PlanningCapacityService();

    private static VehicleCapacityReference vehicle(String length, String width, String height, String fill) {
        return new VehicleCapacityReference(UUID.randomUUID(), "VEH", "PLT", null, null, UUID.randomUUID(), "TYPE",
                new BigDecimal("10000"), new BigDecimal("40"), 20, true, "AVAILABLE", d(length), d(width), d(height),
                d(fill));
    }

    private static CapacityLoad load(String floor, String height) {
        return new CapacityLoad(new BigDecimal("100"), new BigDecimal("1"), BigDecimal.ZERO, d(floor), d(height), 1);
    }

    @Test
    @DisplayName("floor limit is length x width x fill factor; height limit is the vehicle's height")
    void limitsFromTheVehicleType() {
        CapacityLimits limits = CapacityLimits.of(vehicle("6.00", "2.40", "2.40", "0.85"));

        assertThat(limits.maxFloorAreaM2()).isEqualByComparingTo("12.240");
        assertThat(limits.maxStackHeightM()).isEqualByComparingTo("2.40");
        assertThat(limits.loadWidthM()).isEqualByComparingTo("2.40");
    }

    @Test
    @DisplayName("a vehicle type without dimensions has no floor or height limit - today's behaviour")
    void nullDimensionsAreUnlimited() {
        CapacityLimits limits = CapacityLimits.of(vehicle(null, null, null, "0.85"));

        assertThat(limits.maxFloorAreaM2()).isNull();
        assertThat(limits.maxStackHeightM()).isNull();
        assertThat(limits.accommodates(load("9999", "99"))).isTrue();
        TripCapacityView view = service.summarize(TRIP, CapacitySource.LIVE, limits, load("9999", "99"));
        assertThat(view.floor().unlimited()).isTrue();
        assertThat(view.height().unlimited()).isTrue();
        assertThat(view.withinCapacity()).isTrue();
    }

    @Test
    @DisplayName("over the floor or over the height is refused, naming each failed dimension")
    void refusalNamesFloorAndHeight() {
        CapacityLimits limits = CapacityLimits.of(vehicle("6.00", "2.40", "2.40", "0.85"));

        assertThat(limits.accommodates(load("12.24", "2.40"))).isTrue();
        assertThat(limits.accommodates(load("12.25", "1.00"))).isFalse();
        assertThat(limits.accommodates(load("1.00", "2.41"))).isFalse();
        assertThatThrownBy(() -> service.requireWithinCapacity("Order TO-1 does not fit trip 1", limits,
                load("13", "2.9")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("floor area 13 m2 exceeds the capacity of 12.24 m2")
                .hasMessageContaining("stack height 2.9 m exceeds the capacity of 2.4 m");
    }

    @Test
    @DisplayName("floor sums; stack height takes the maximum, never the sum")
    void floorSumsHeightMaximum() {
        CapacityLoad both = load("4", "1.363").plus(load("5", "2.100"));

        assertThat(both.floorAreaM2()).isEqualByComparingTo("9");
        assertThat(both.maxStackHeightM()).isEqualByComparingTo("2.100");
        OrderAmounts amounts = new OrderAmounts(null, null, null, d("1"), d("1.2"))
                .plus(new OrderAmounts(null, null, null, d("2"), d("0.8")));
        assertThat(amounts.floorAreaM2()).isEqualByComparingTo("3");
        assertThat(amounts.maxStackHeightM()).isEqualByComparingTo("1.2");
    }

    @Test
    @DisplayName("the view reports floor and height bars and the binding dimension")
    void viewReportsBindingDimension() {
        CapacityLimits limits = CapacityLimits.of(vehicle("6.00", "2.40", "2.40", "0.85"));

        TripCapacityView view = service.summarize(TRIP, CapacitySource.LIVE, limits, load("11.016", "1.20"));

        assertThat(view.floor().percentUsed()).isEqualByComparingTo("90.0");
        assertThat(view.height().percentUsed()).isEqualByComparingTo("50.0");
        assertThat(view.bindingDimension()).isEqualTo("FLOOR");
        assertThat(CapacityLimits.unlimited().bindingDimension(load("1", "1"))).isNull();
    }

    @Test
    @DisplayName("a unit taller than the vehicle is refused as such; no height limit admits anything")
    void unitHeight() {
        CapacityLimits limits = CapacityLimits.of(vehicle("6.00", "2.40", "2.40", "0.85"));

        assertThat(limits.admitsUnitHeight(d("2.50"))).isFalse();
        assertThat(limits.admitsUnitHeight(d("2.40"))).isTrue();
        assertThat(limits.admitsUnitHeight(null)).isTrue();
        assertThat(CapacityLimits.unlimited().admitsUnitHeight(d("9"))).isTrue();
    }

    @Test
    @DisplayName("a delivery is compared without the floor: kilograms and pallets cover the order")
    void deliveriesIgnoreGeometry() {
        OrderAmounts ordered = new OrderAmounts(d("100"), d("0.15"), d("1"), d("0.11"), d("1.363"));
        OrderAmounts delivered = new OrderAmounts(d("100"), d("0.15"), d("1"));

        assertThat(delivered.covers(ordered)).isFalse();
        assertThat(delivered.covers(ordered.withoutGeometry())).isTrue();
        assertThat(ordered.minus(ordered).isZero()).isTrue();
    }

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
