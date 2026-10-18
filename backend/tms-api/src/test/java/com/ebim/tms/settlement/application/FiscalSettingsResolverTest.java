package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.FiscalRules;
import com.ebim.tms.shared.settings.FiscalSettings;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A company's own fiscal value wins; a null is the deployment's default (V66, D-06). */
class FiscalSettingsResolverTest {

    private static final FiscalRules DEFAULTS = new FiscalRules(new BigDecimal("18.00"), true, "027",
            new BigDecimal("4.00"), new BigDecimal("400.00"), false, new BigDecimal("3.00"));

    @Test
    @DisplayName("nothing stored resolves to the defaults")
    void unset() {
        assertThat(FiscalSettingsResolver.resolve(FiscalSettings.unset(), DEFAULTS)).isEqualTo(DEFAULTS);
        assertThat(FiscalSettingsResolver.resolve(null, DEFAULTS)).isEqualTo(DEFAULTS);
    }

    @Test
    @DisplayName("each stored value replaces only its own default, false included")
    void partial() {
        FiscalRules rules = FiscalSettingsResolver.resolve(new FiscalSettings(null, false, null,
                new BigDecimal("12.00"), null, true, null), DEFAULTS);
        assertThat(rules.igvRate()).isEqualByComparingTo("18.00");
        assertThat(rules.detractionEnabled()).isFalse();
        assertThat(rules.detractionCode()).isEqualTo("027");
        assertThat(rules.detractionRate()).isEqualByComparingTo("12.00");
        assertThat(rules.detractionThresholdAmount()).isEqualByComparingTo("400.00");
        assertThat(rules.retentionAgent()).isTrue();
        assertThat(rules.retentionRate()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("the disabled CPE validation never says valid")
    void cpeValidationIsNeverValid() {
        assertThat(new DisabledCpeValidationAdapter().validate("20100070970", "01", "F001", "1"))
                .isEqualTo(CpeValidationPort.CpeValidationStatus.EXTERNAL_DEPENDENCY);
    }
}
