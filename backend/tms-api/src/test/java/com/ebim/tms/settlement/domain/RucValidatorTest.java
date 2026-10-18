package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The RUC check digit (modulo 11) and the CPE numbering (V66, ADR-024 part 2, item 3). */
class RucValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"20100070970", "20131312955", "20100047218", "10000000014", "20000000010",
            "20000000061"})
    @DisplayName("valid RUCs pass, including the 10 -> 0 and 11 -> 1 cases")
    void valid(String ruc) {
        assertThat(RucValidator.isValid(ruc)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"20100070971", "2010007097", "201000709700", "30100070970", "2010007097A",
            " 20100070970"})
    @DisplayName("a wrong check digit, length, prefix or character is refused")
    void invalid(String ruc) {
        assertThat(RucValidator.isValid(ruc)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2010007097,0", "2000000006,1", "2000000001,0", "1000000001,4"})
    @DisplayName("the check digit is 11 - (sum mod 11), with 10 read as 0 and 11 as 1")
    void checkDigit(String firstTen, int digit) {
        assertThat(RucValidator.checkDigitOf(firstTen)).isEqualTo(digit);
    }

    @Test
    @DisplayName("series: F + 3 alphanumerics, E001 or 4 digits")
    void series() {
        assertThat(CpeNumbering.isValidSeries("F001")).isTrue();
        assertThat(CpeNumbering.isValidSeries("FC01")).isTrue();
        assertThat(CpeNumbering.isValidSeries("E001")).isTrue();
        assertThat(CpeNumbering.isValidSeries("0001")).isTrue();
        assertThat(CpeNumbering.isValidSeries("B001")).isFalse(); // a boleta, not a carrier's factura
        assertThat(CpeNumbering.isValidSeries("E002")).isFalse();
        assertThat(CpeNumbering.isValidSeries("F01")).isFalse();
        assertThat(CpeNumbering.isValidSeries(null)).isFalse();
    }

    @Test
    @DisplayName("a correlative loses its leading zeros, so padding cannot dodge uniqueness")
    void correlative() {
        assertThat(CpeNumbering.normalizeCorrelative("00000123")).isEqualTo("123");
        assertThat(CpeNumbering.normalizeCorrelative("123")).isEqualTo("123");
        assertThat(CpeNumbering.normalizeCorrelative("99999999")).isEqualTo("99999999");
        assertThat(CpeNumbering.normalizeCorrelative("00000000")).isNull();
        assertThat(CpeNumbering.normalizeCorrelative("123456789")).isNull();
        assertThat(CpeNumbering.normalizeCorrelative("12a")).isNull();
        assertThat(CpeNumbering.invoiceNumberOf("F001", "123")).isEqualTo("F001-123");
    }
}
