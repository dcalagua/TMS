package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.geometry.GeometryLine;
import com.ebim.tms.shared.geometry.OrderGeometry;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import com.ebim.tms.shared.reference.OrderAmounts;
import com.ebim.tms.shared.reference.OrderGeometryProfile;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Re-stacking an order for the vehicle it is checked against (ADR-022 section 5). */
class VehicleLoadsTest {

    private static final BigDecimal REFERENCE = new BigDecimal("2.40");

    /** 0.60 x 0.40 x 0.30 m crates, 10 kg each, stackable with no other bound than the height. */
    private static final PhysicalBlock CRATE = new PhysicalBlock(m("0.60"), m("0.40"), m("0.30"),
            StackMode.STACKABLE, null, null, null, false);

    /** A vehicle with only a height and a floor: 2.20 m high, 12.24 m2 of floor. */
    private static CapacityLimits vehicle(String heightM) {
        return new CapacityLimits(null, null, null, m("12.240"), m(heightM), m("2.40"));
    }

    /** 16 crates as the order service stores them: at the reference height, stacks of eight. */
    private static OrderGeometryProfile crates(int quantity) {
        List<GeometryLine> lines = List.of(new GeometryLine(BigDecimal.valueOf(quantity), "UN", m("10"), CRATE,
                BigDecimal.valueOf(quantity).multiply(m("0.072"))));
        BigDecimal total = BigDecimal.valueOf(quantity).multiply(m("0.072"));
        OrderGeometry stored = OrderGeometry.of(lines, total, REFERENCE);
        return new OrderGeometryProfile(UUID.randomUUID(), REFERENCE, total, stored.planningVolumeM3(),
                stored.floorAreaM2(), lines);
    }

    private static OrderAmounts stored(OrderGeometryProfile profile) {
        OrderGeometry geometry = profile.at(REFERENCE);
        return new OrderAmounts(m("160"), geometry.planningVolumeM3(), BigDecimal.ZERO, geometry.floorAreaM2(),
                geometry.maxStackHeightM());
    }

    @Test
    @DisplayName("2.20 m vehicle: crates stored as stacks of eight (2.40 m) load as stacks of seven and fit")
    void lowerVehicleTakesStacksOfSeven() {
        OrderGeometryProfile profile = crates(16);
        OrderAmounts order = stored(profile);
        CapacityLimits low = vehicle("2.20");

        // The defect: the stored figure compared with the lower vehicle refused a load that fits it.
        assertThat(order.maxStackHeightM()).isEqualByComparingTo("2.400");
        assertThat(low.accommodates(CapacityLoad.of(order))).isFalse();

        CapacityLoad restacked = VehicleLoads.of(order, profile, low);
        assertThat(restacked.maxStackHeightM()).isEqualByComparingTo("2.100");
        assertThat(restacked.floorAreaM2()).isEqualByComparingTo("0.7200");
        assertThat(restacked.volumeM3()).isEqualByComparingTo(order.volumeM3());
        assertThat(restacked.weightKg()).isEqualByComparingTo("160");
        assertThat(low.accommodates(restacked)).isTrue();
    }

    @Test
    @DisplayName("at the reference height, without a height or without a profile, the stored figures hold exactly")
    void storedFiguresHoldWhereNothingChanges() {
        OrderGeometryProfile profile = crates(16);
        OrderAmounts order = stored(profile);

        assertThat(VehicleLoads.amountsOn(order, profile, vehicle("2.40"))).isEqualTo(order);
        assertThat(VehicleLoads.amountsOn(order, profile, CapacityLimits.unlimited())).isEqualTo(order);
        assertThat(VehicleLoads.amountsOn(order, null, vehicle("2.20"))).isEqualTo(order);
    }

    @Test
    @DisplayName("a taller vehicle takes the same crates on less floor")
    void tallerVehicleTakesLessFloor() {
        OrderGeometryProfile profile = crates(24);
        OrderAmounts order = stored(profile);

        CapacityLoad tall = VehicleLoads.of(order, profile, vehicle("3.60"));

        // 24 crates: three stacks of eight at 2.40 m, two stacks of twelve at 3.60 m.
        assertThat(order.floorAreaM2()).isEqualByComparingTo("0.7200");
        assertThat(tall.floorAreaM2()).isEqualByComparingTo("0.4800");
        assertThat(tall.maxStackHeightM()).isEqualByComparingTo("3.600");
    }

    @Test
    @DisplayName("a slice is re-stacked in proportion to its share of the order's floor")
    void sliceInProportion() {
        OrderGeometryProfile profile = crates(16);
        OrderAmounts order = stored(profile);
        OrderAmounts half = new OrderAmounts(m("80"), order.volumeM3().divide(m("2")), BigDecimal.ZERO,
                order.floorAreaM2().divide(m("2")), order.maxStackHeightM());

        CapacityLoad restacked = VehicleLoads.of(half, profile, vehicle("2.20"));

        // Half of 0.48 is 0.24; the order grows by 0.24 m2 at 2.20 m, so the half grows by 0.12.
        assertThat(restacked.floorAreaM2()).isEqualByComparingTo("0.3600");
        assertThat(restacked.maxStackHeightM()).isEqualByComparingTo("2.100");
    }

    @Test
    @DisplayName("a unit taller than the vehicle is not re-stacked into fitting")
    void unitTallerThanVehicleStillRefused() {
        OrderGeometryProfile profile = crates(16);
        OrderAmounts order = stored(profile);
        CapacityLimits tooLow = vehicle("0.25");

        assertThat(tooLow.admitsUnitHeight(profile.at(REFERENCE).maxUnitHeightM())).isFalse();
        assertThat(tooLow.accommodates(VehicleLoads.of(order, profile, tooLow))).isFalse();
    }

    private static BigDecimal m(String value) {
        return new BigDecimal(value);
    }
}
