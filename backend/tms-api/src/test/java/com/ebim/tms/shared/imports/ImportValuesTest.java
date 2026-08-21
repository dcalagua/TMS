package com.ebim.tms.shared.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImportValues} conversions, run with no Spring context and no database - every entity
 * import's validator relies on these being correct.
 */
class ImportValuesTest {

    @Test
    @DisplayName("a blank or null cell converts to null, never an exception")
    void blankIsNull() {
        assertThat(ImportValues.decimal(null)).isNull();
        assertThat(ImportValues.decimal("  ")).isNull();
        assertThat(ImportValues.nonNegativeInteger("")).isNull();
        assertThat(ImportValues.enumeration(null, ImportFormat.class, "XLSX, CSV")).isNull();
    }

    @Test
    @DisplayName("a decimal accepts a dot, and a single comma as a Spanish-locale decimal separator")
    void decimalAcceptsDotAndComma() {
        assertThat(ImportValues.decimal("12.5")).isEqualByComparingTo("12.5");
        assertThat(ImportValues.decimal("12,5")).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("a thousands separator is refused rather than guessed")
    void thousandsSeparatorIsRejected() {
        assertThatThrownBy(() -> ImportValues.decimal("1,234.5")).isInstanceOf(ImportValues.Rejected.class);
        assertThatThrownBy(() -> ImportValues.decimal("1,234,567")).isInstanceOf(ImportValues.Rejected.class);
    }

    @Test
    @DisplayName("positiveDecimal refuses zero and negatives; nonNegativeDecimal allows zero")
    void signConstraints() {
        assertThatThrownBy(() -> ImportValues.positiveDecimal("0")).isInstanceOf(ImportValues.Rejected.class);
        assertThatThrownBy(() -> ImportValues.positiveDecimal("-1")).isInstanceOf(ImportValues.Rejected.class);
        assertThat(ImportValues.nonNegativeDecimal("0")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ImportValues.nonNegativeDecimal("-1")).isInstanceOf(ImportValues.Rejected.class);
    }

    @Test
    @DisplayName("a whole number rejects fractions and negatives")
    void nonNegativeInteger() {
        assertThat(ImportValues.nonNegativeInteger("12")).isEqualTo(12);
        assertThatThrownBy(() -> ImportValues.nonNegativeInteger("-1")).isInstanceOf(ImportValues.Rejected.class);
        assertThatThrownBy(() -> ImportValues.nonNegativeInteger("1.5")).isInstanceOf(ImportValues.Rejected.class);
    }

    @Test
    @DisplayName("an enum resolves case-insensitively and rejects an unknown value with the allowed list")
    void enumeration() {
        assertThat(ImportValues.enumeration("xlsx", ImportFormat.class, "XLSX, CSV")).isEqualTo(ImportFormat.XLSX);
        assertThatThrownBy(() -> ImportValues.enumeration("PDF", ImportFormat.class, "XLSX, CSV"))
                .isInstanceOf(ImportValues.Rejected.class)
                .hasMessageContaining("XLSX, CSV");
    }

    @Test
    @DisplayName("a boolean accepts TRUE/FALSE, yes/no and 1/0")
    void bool() {
        assertThat(ImportValues.bool("TRUE")).isTrue();
        assertThat(ImportValues.bool("yes")).isTrue();
        assertThat(ImportValues.bool("1")).isTrue();
        assertThat(ImportValues.bool("false")).isFalse();
        assertThat(ImportValues.bool("no")).isFalse();
        assertThat(ImportValues.bool("0")).isFalse();
        assertThatThrownBy(() -> ImportValues.bool("maybe")).isInstanceOf(ImportValues.Rejected.class);
    }
}
