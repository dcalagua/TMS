package com.ebim.tms.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import com.ebim.tms.fleet.domain.VehicleType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the vehicle-overrides-first, per-field resolution rule the step brief asks for,
 * independent of persistence - {@link EffectiveCapacityResolver} takes plain entities and does
 * not touch a repository.
 */
class EffectiveCapacityResolverTest {

    private final EffectiveCapacityResolver resolver = new EffectiveCapacityResolver();

    private static VehicleType type(BigDecimal maxWeightKg, BigDecimal maxVolumeM3, int maxPallets) {
        return new VehicleType(UUID.randomUUID(), "TYPE", "Type", maxWeightKg, maxVolumeM3, maxPallets, null, null,
                null, null, false, null, null, null, UUID.randomUUID());
    }

    private static Vehicle vehicle(UUID companyId, BigDecimal weightOverride, BigDecimal volumeOverride,
            Integer palletsOverride) {
        return new Vehicle(companyId, "VEH", "ABC-123", null, UUID.randomUUID(), weightOverride, volumeOverride,
                palletsOverride, VehicleAvailabilityStatus.AVAILABLE, null, UUID.randomUUID());
    }

    @Test
    @DisplayName("with no overrides, every dimension falls back to the vehicle type's default")
    void fallsBackToTypeWhenNoOverridesPresent() {
        VehicleType type = type(new BigDecimal("10000.00"), new BigDecimal("40.000"), 20);
        Vehicle vehicle = vehicle(UUID.randomUUID(), null, null, null);

        EffectiveCapacity capacity = resolver.resolve(vehicle, type);

        assertThat(capacity.maxWeightKg()).isEqualByComparingTo("10000.00");
        assertThat(capacity.maxVolumeM3()).isEqualByComparingTo("40.000");
        assertThat(capacity.maxPallets()).isEqualTo(20);
    }

    @Test
    @DisplayName("each override is applied independently: overriding one dimension leaves the others at the type default")
    void appliesEachOverrideIndependently() {
        VehicleType type = type(new BigDecimal("8000.00"), new BigDecimal("30.000"), 16);
        Vehicle vehicle = vehicle(UUID.randomUUID(), new BigDecimal("9500.00"), null, null);

        EffectiveCapacity capacity = resolver.resolve(vehicle, type);

        assertThat(capacity.maxWeightKg()).isEqualByComparingTo("9500.00");
        assertThat(capacity.maxVolumeM3()).isEqualByComparingTo("30.000");
        assertThat(capacity.maxPallets()).isEqualTo(16);
    }

    @Test
    @DisplayName("when every dimension is overridden, none of the type's defaults are used")
    void appliesAllOverridesWhenAllArePresent() {
        VehicleType type = type(new BigDecimal("10000.00"), new BigDecimal("40.000"), 20);
        Vehicle vehicle =
                vehicle(UUID.randomUUID(), new BigDecimal("12000.00"), new BigDecimal("45.000"), 24);

        EffectiveCapacity capacity = resolver.resolve(vehicle, type);

        assertThat(capacity.maxWeightKg()).isEqualByComparingTo("12000.00");
        assertThat(capacity.maxVolumeM3()).isEqualByComparingTo("45.000");
        assertThat(capacity.maxPallets()).isEqualTo(24);
    }

    @Test
    @DisplayName("a zero pallets override is a real override, not treated as absent")
    void zeroPalletsOverrideIsHonoured() {
        VehicleType type = type(new BigDecimal("10000.00"), new BigDecimal("40.000"), 20);
        Vehicle vehicle = vehicle(UUID.randomUUID(), null, null, 0);

        EffectiveCapacity capacity = resolver.resolve(vehicle, type);

        assertThat(capacity.maxPallets()).isZero();
    }
}
