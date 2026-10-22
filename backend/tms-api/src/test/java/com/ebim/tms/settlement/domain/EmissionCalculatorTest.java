package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.EmissionCalculator.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmissionCalculatorTest {

    @Test
    @DisplayName("kg CO2e is distance times factor, to two decimals")
    void calculated() {
        EmissionCalculator.Result result = EmissionCalculator.compute(true, new BigDecimal("0.9"), new BigDecimal("123.456"));
        assertThat(result.status()).isEqualTo(Status.CALCULATED);
        assertThat(result.kgCo2e()).isEqualByComparingTo("111.11");
    }

    @Test
    @DisplayName("no vehicle, no factor or no distance is 'no data', never zero")
    void absentNeverZero() {
        assertThat(EmissionCalculator.compute(false, new BigDecimal("0.9"), BigDecimal.TEN).status())
                .isEqualTo(Status.NO_VEHICLE);
        assertThat(EmissionCalculator.compute(true, null, BigDecimal.TEN).status()).isEqualTo(Status.NO_FACTOR);
        assertThat(EmissionCalculator.compute(true, new BigDecimal("0.9"), BigDecimal.ZERO).status())
                .isEqualTo(Status.NO_DISTANCE);
        assertThat(EmissionCalculator.compute(true, null, BigDecimal.TEN).kgCo2e()).isNull();
    }

    @Test
    @DisplayName("intensity is grams per tonne-kilometre, absent without weight")
    void intensity() {
        // 90 kg over 100 km carrying 2 t = 90 000 g / 200 t-km = 450 g/t-km.
        assertThat(EmissionCalculator.gramsPerTonneKm(new BigDecimal("90"), new BigDecimal("100"), new BigDecimal("2000")))
                .isEqualByComparingTo("450.0");
        assertThat(EmissionCalculator.gramsPerTonneKm(new BigDecimal("90"), new BigDecimal("100"), BigDecimal.ZERO))
                .isNull();
    }
}
