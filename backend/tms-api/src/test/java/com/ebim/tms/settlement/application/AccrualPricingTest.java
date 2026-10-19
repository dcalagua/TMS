package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.AccrualBasis;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.TripCostLookupPort.TripCostSummary;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a trip is accrued at, or why not - never an invented figure (V65, ADR-023). */
class AccrualPricingTest {

    private static final UUID TRIP = UUID.randomUUID();

    @Test
    @DisplayName("closed actual first, otherwise the estimate")
    void priced() {
        assertThat(AccrualPricing.of(carrierTrip(), cost("1450.00", "1500.00", true)))
                .isEqualTo(new AccrualPricing.Priced(new BigDecimal("1500.00"), "PEN", AccrualBasis.ACTUAL));
        assertThat(AccrualPricing.of(carrierTrip(), cost("1450.00", "1500.00", false)))
                .isEqualTo(new AccrualPricing.Priced(new BigDecimal("1450.00"), "PEN", AccrualBasis.ESTIMATED));
    }

    @Test
    @DisplayName("no cost, no figure, no carrier: listed with a reason; own fleet: neither accrued nor listed")
    void unpriced() {
        assertThat(AccrualPricing.of(carrierTrip(), null))
                .isEqualTo(new AccrualPricing.Unpriced(UnaccruedReason.NO_TRIP_COST));
        assertThat(AccrualPricing.of(carrierTrip(), cost(null, "300.00", false)))
                .isEqualTo(new AccrualPricing.Unpriced(UnaccruedReason.NOT_CALCULABLE));
        assertThat(AccrualPricing.of(carrierTrip(), cost("0.00", null, false)))
                .isEqualTo(new AccrualPricing.Unpriced(UnaccruedReason.NOT_CALCULABLE));
        assertThat(AccrualPricing.of(trip(null, null), cost("100.00", null, false)))
                .isEqualTo(new AccrualPricing.Unpriced(UnaccruedReason.NO_CARRIER));
        assertThat(AccrualPricing.of(trip(null, UUID.randomUUID()), cost("100.00", null, false)))
                .isInstanceOf(AccrualPricing.OwnFleet.class);
    }

    private static AccruableTrip carrierTrip() {
        return trip(UUID.randomUUID(), UUID.randomUUID());
    }

    private static AccruableTrip trip(UUID carrierId, UUID vehicleId) {
        return new AccruableTrip(TRIP, "SH-1", carrierId, vehicleId, null, OffsetDateTime.now(), null);
    }

    private static TripCostSummary cost(String expected, String actual, boolean closed) {
        return new TripCostSummary(TRIP, expected == null ? null : new BigDecimal(expected),
                actual == null ? null : new BigDecimal(actual), "PEN", closed, Map.of(),
                closed ? OffsetDateTime.now() : null);
    }
}
