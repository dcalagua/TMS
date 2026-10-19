package com.ebim.tms.rates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.CostableTrip;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The loading-metre component and the volume's provenance (V63, ADR-022 section 8), on numbers
 * small enough to check by hand: 4.08 m2 of floor across a 2.40 m deck filled to 0.85 is exactly
 * 2.000 loading metres.
 */
class LoadingMeterRateCalculatorTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 18);

    private static RateCard card(RateComponents components) {
        return new RateCard(COMPANY, "RC-LDM", "Card", CARRIER, RateCardScope.CARRIER, null, null, null,
                "PEN", DATE, null, components, UUID.randomUUID());
    }

    /** Base, per-m3, fuel surcharge and per-loading-metre; everything else absent. */
    private static RateComponents components(String base, String perM3, String fuelPercent, String perLoadingMeter) {
        return new RateComponents(bd(base), null, null, bd(perM3), null, null, null, bd(fuelPercent), null, null,
                null, null, null, bd(perLoadingMeter));
    }

    private static CostableTrip trip(String volumeM3, String floorAreaM2, String widthM, String fill) {
        return new CostableTrip(UUID.randomUUID(), COMPANY, "SH-1", DATE, CARRIER, null, null, null,
                bd("1000"), bd(volumeM3), bd("8"), true, null, 1, bd(floorAreaM2), bd(widthM), bd(fill));
    }

    private static CostLine line(CostEstimate estimate, RateComponent component) {
        return estimate.lines().stream().filter(line -> line.component() == component).findFirst().orElseThrow();
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Test
    @DisplayName("floor / (width x fill) is charged per loading metre, from the effective geometry")
    void chargedPerLoadingMeter() {
        CostEstimate estimate = TripCostCalculator.calculate(card(components(null, null, null, "50")),
                CostInputs.of(trip("1.2", "4.08", "2.40", "0.85"), null));

        CostLine line = line(estimate, RateComponent.LOADING_METERS);
        assertThat(line.status()).isEqualTo(CostComponentStatus.APPLIED);
        assertThat(line.quantity()).isEqualByComparingTo("2.000");
        assertThat(line.unit()).isEqualTo(CostUnit.LOADING_METER);
        assertThat(line.quantitySource()).isEqualTo(CostQuantitySource.EFFECTIVE_GEOMETRY);
        assertThat(line.amount()).isEqualByComparingTo("100.00");
        assertThat(estimate.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a vehicle with no load width makes the line NOT_CALCULABLE (LOAD_WIDTH_UNKNOWN), never zero")
    void widthUnknown() {
        CostEstimate estimate = TripCostCalculator.calculate(card(components("80", null, null, "50")),
                CostInputs.of(trip("1.2", "4.08", null, "0.85"), null));

        CostLine line = line(estimate, RateComponent.LOADING_METERS);
        assertThat(line.status()).isEqualTo(CostComponentStatus.NOT_CALCULABLE);
        assertThat(line.reason()).isEqualTo(CostComponentReason.LOAD_WIDTH_UNKNOWN);
        assertThat(line.quantity()).isNull();
        assertThat(line.amount()).isEqualByComparingTo("0.00");
        assertThat(estimate.amount()).isEqualByComparingTo("80.00");
        assertThat(estimate.isComplete()).isFalse();
    }

    @Test
    @DisplayName("a trip on which nothing was measured has no floor: NOT_CALCULABLE (FLOOR_AREA_UNKNOWN)")
    void floorUnknown() {
        CostEstimate estimate = TripCostCalculator.calculate(card(components(null, null, null, "50")),
                CostInputs.of(trip("1.2", "0", "2.40", "0.85"), null));

        assertThat(line(estimate, RateComponent.LOADING_METERS).reason())
                .isEqualTo(CostComponentReason.FLOOR_AREA_UNKNOWN);
    }

    @Test
    @DisplayName("VOLUME says EFFECTIVE_GEOMETRY when a measured line contributed, ORDER_DECLARED_TOTALS otherwise")
    void volumeProvenance() {
        RateCard perM3 = card(components(null, "100", null, null));

        CostLine measured = line(TripCostCalculator.calculate(perM3,
                CostInputs.of(trip("0.1503", "0.1103", "2.40", "0.85"), null)), RateComponent.VOLUME);
        assertThat(measured.quantity()).isEqualByComparingTo("0.1503");
        assertThat(measured.quantitySource()).isEqualTo(CostQuantitySource.EFFECTIVE_GEOMETRY);
        assertThat(measured.amount()).isEqualByComparingTo("15.03");

        CostLine declared = line(TripCostCalculator.calculate(perM3,
                CostInputs.of(trip("1.2", "0", null, null), null)), RateComponent.VOLUME);
        assertThat(declared.quantity()).isEqualByComparingTo("1.2");
        assertThat(declared.quantitySource()).isEqualTo(CostQuantitySource.ORDER_DECLARED_TOTALS);
    }

    @Test
    @DisplayName("loading metres are linehaul: they come before the fuel surcharge and are surcharged")
    void loadingMetersAreLinehaul() {
        CostEstimate estimate = TripCostCalculator.calculate(card(components("100", null, "10", "50")),
                CostInputs.of(trip("1.2", "4.08", "2.40", "0.85"), null));

        assertThat(estimate.lines()).extracting(CostLine::component).containsExactly(
                RateComponent.BASE, RateComponent.LOADING_METERS, RateComponent.FUEL_SURCHARGE);
        assertThat(line(estimate, RateComponent.FUEL_SURCHARGE).quantity()).isEqualByComparingTo("200.00");
        assertThat(estimate.amount()).isEqualByComparingTo("220.00");
    }

    @Test
    @DisplayName("a card charging only per loading metre is a card; a pre-V63 trip just cannot be priced on it")
    void loadingMeterOnlyCard() {
        RateCard card = card(components(null, null, null, "50"));
        assertThat(card.hasAnyComponent()).isTrue();

        CostableTrip v39Trip = new CostableTrip(UUID.randomUUID(), COMPANY, "SH-2", DATE, CARRIER, null, null, null,
                bd("1000"), bd("1.2"), bd("8"), true, null, 1);
        CostLine line = line(TripCostCalculator.calculate(card, CostInputs.of(v39Trip, null)),
                RateComponent.LOADING_METERS);
        assertThat(line.reason()).isEqualTo(CostComponentReason.FLOOR_AREA_UNKNOWN);
    }

    @Test
    @DisplayName("a card that says nothing about loading metres prints no line for them")
    void silentCard() {
        CostEstimate estimate = TripCostCalculator.calculate(card(components("100", null, null, null)),
                CostInputs.of(trip("1.2", "4.08", "2.40", "0.85"), null));

        assertThat(estimate.lines()).extracting(CostLine::component).containsExactly(RateComponent.BASE);
    }
}
