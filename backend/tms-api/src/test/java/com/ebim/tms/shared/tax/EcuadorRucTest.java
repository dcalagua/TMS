package com.ebim.tms.shared.tax;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Ecuadorian RUC (ADR-027). The valid examples were built by computing the check digit of each
 * branch by hand - the test states the arithmetic, so a reader can redo it.
 */
class EcuadorRucTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // natural person (3rd digit 0-5): the cedula's modulo 10 at digit 10, then 001
            "1710034065001", "0912345675001", "3001234560001",
            // private company (3rd digit 9): modulo 11, weights 4,3,2,7,6,5,4,3,2, check at digit 10
            "0990004196001", "1790000001001", "1791234561001",
            // public entity (3rd digit 6): modulo 11, weights 3,2,7,6,5,4,3,2, check at digit 9, then 0001
            "1760001550001", "1760000070001"})
    @DisplayName("valid RUCs of the three taxpayer types pass")
    void valid(String ruc) {
        assertThat(EcuadorRuc.isValid(ruc)).isTrue();
        assertThat(TaxIdRule.EC_RUC.isValid(ruc)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "1710034064001", // natural, wrong check digit
            "0990004197001", // private, wrong check digit
            "1760001560001", // public, wrong check digit
            "0991234560001", // private whose modulo 11 gives 10: no digit is right
            "1760001551001", // public whose establishment does not start with 0
            "2510034065001", // province 25 does not exist
            "0010034065001", // province 00 does not exist
            "1770034065001", // third digit 7 is no taxpayer type
            "1780034065001", // nor is 8
            "1710034065000", // establishment 000
            "171003406500", "17100340650011", "171003406500A", " 1710034065001", "20100070970"})
    @DisplayName("a wrong check digit, province, type, establishment, length or character is refused")
    void invalid(String ruc) {
        assertThat(EcuadorRuc.isValid(ruc)).isFalse();
    }

    @Test
    @DisplayName("the natural-person check is the cedula's: products above 9 lose 9")
    void naturalCheckDigit() {
        // 1*2 7*1 1*2 0*1 0*2 3*1 4*2 0*1 6*2=12->3: 2+7+2+0+0+3+8+0+3 = 25 -> (10 - 5) % 10 = 5
        assertThat(EcuadorRuc.naturalCheckDigit("171003406")).isEqualTo(5);
    }

    @Test
    @DisplayName("a free-text tax id is letters, digits and hyphens, up to 20")
    void freeText() {
        assertThat(TaxIdRule.FREE_TEXT.isValid("900123456-7")).isTrue();
        assertThat(TaxIdRule.FREE_TEXT.isValid("12-3456789")).isTrue();
        assertThat(TaxIdRule.FREE_TEXT.isValid("")).isFalse();
        assertThat(TaxIdRule.FREE_TEXT.isValid("NIT 900123456")).isFalse();
        assertThat(TaxIdRule.FREE_TEXT.isValid("123456789012345678901")).isFalse();
    }
}
