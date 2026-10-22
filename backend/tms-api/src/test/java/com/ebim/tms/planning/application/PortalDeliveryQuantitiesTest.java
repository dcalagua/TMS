package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.OrderAmounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The nine figures of a partial delivery from the refusal a driver reports (V70). */
class PortalDeliveryQuantitiesTest {

    private static final OrderAmounts LOADED = new OrderAmounts(new BigDecimal("1000"), new BigDecimal("10"),
            new BigDecimal("10"), new BigDecimal("4.8"), new BigDecimal("1.2"));

    private static OrderAmounts refused(String kg, String m3, String pallets) {
        return new OrderAmounts(kg == null ? null : new BigDecimal(kg), m3 == null ? null : new BigDecimal(m3),
                pallets == null ? null : new BigDecimal(pallets));
    }

    @Test
    @DisplayName("attempted is what the trip carried; delivered is that less the refusal; nothing is left outstanding")
    void derivesTheNineFigures() {
        DeliveryResultRequest.DeliveryQuantitiesRequest quantities =
                PortalDeliveryQuantities.fromRefused(LOADED, refused("200", "2", "2"));

        assertThat(quantities.attemptedWeightKg()).isEqualByComparingTo("1000");
        assertThat(quantities.attemptedPallets()).isEqualByComparingTo("10");
        assertThat(quantities.deliveredWeightKg()).isEqualByComparingTo("800");
        assertThat(quantities.deliveredVolumeM3()).isEqualByComparingTo("8");
        assertThat(quantities.deliveredPallets()).isEqualByComparingTo("8");
        assertThat(quantities.refusedPallets()).isEqualByComparingTo("2");
        assertThat(quantities.toDomain().outstanding().isZero()).isTrue();
    }

    @Test
    @DisplayName("a measure the driver left blank is refused as zero, and the floor area never enters a delivery")
    void blankMeasuresAreZero() {
        DeliveryResultRequest.DeliveryQuantitiesRequest quantities =
                PortalDeliveryQuantities.fromRefused(LOADED, refused(null, null, "3"));

        assertThat(quantities.refusedWeightKg()).isEqualByComparingTo("0");
        assertThat(quantities.deliveredWeightKg()).isEqualByComparingTo("1000");
        assertThat(quantities.deliveredPallets()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("a partial delivery refuses something, never more than was carried, and never a negative amount")
    void refusesNonsense() {
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> PortalDeliveryQuantities.fromRefused(LOADED, refused("0", "0", "0")));
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> PortalDeliveryQuantities.fromRefused(LOADED, refused(null, null, "11")))
                .withMessageContaining("10 pallets");
        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> PortalDeliveryQuantities.fromRefused(LOADED, refused("-1", null, null)));
    }
}
