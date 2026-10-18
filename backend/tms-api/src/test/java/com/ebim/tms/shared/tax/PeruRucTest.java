package com.ebim.tms.shared.tax;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The Peruvian RUC check digit (modulo 11) (V66, ADR-024 part 2 item 3, ADR-027). */
class PeruRucTest {

    @ParameterizedTest
    @ValueSource(strings = {"20100070970", "20131312955", "20100047218", "10000000014", "20000000010",
            "20000000061"})
    @DisplayName("valid RUCs pass, including the 10 -> 0 and 11 -> 1 cases")
    void valid(String ruc) {
        assertThat(PeruRuc.isValid(ruc)).isTrue();
        assertThat(TaxIdRule.PE_RUC.isValid(ruc)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"20100070971", "2010007097", "201000709700", "30100070970", "2010007097A",
            " 20100070970"})
    @DisplayName("a wrong check digit, length, prefix or character is refused")
    void invalid(String ruc) {
        assertThat(PeruRuc.isValid(ruc)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2010007097,0", "2000000006,1", "2000000001,0", "1000000001,4"})
    @DisplayName("the check digit is 11 - (sum mod 11), with 10 read as 0 and 11 as 1")
    void checkDigit(String firstTen, int digit) {
        assertThat(PeruRuc.checkDigitOf(firstTen)).isEqualTo(digit);
    }
}
