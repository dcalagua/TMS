package com.ebim.tms.settlement.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.tax.TaxIdRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The fiscal vocabulary of each regime (ADR-027): codes, numbering, tax id, withholdings, currency. */
class TaxRegimesTest {

    @Test
    @DisplayName("PE and EC have their own regime; every other country the generic one")
    void selection() {
        assertThat(TaxRegimes.forCountry("PE").code()).isEqualTo(TaxRegimeCode.PE);
        assertThat(TaxRegimes.forCountry("ec").code()).isEqualTo(TaxRegimeCode.EC);
        assertThat(TaxRegimes.forCountry("CO").code()).isEqualTo(TaxRegimeCode.GENERIC);
        assertThat(TaxRegimes.forCountry("US").code()).isEqualTo(TaxRegimeCode.GENERIC);
        assertThat(TaxRegimes.forCountry(null).code()).isEqualTo(TaxRegimeCode.GENERIC);
    }

    @Nested
    @DisplayName("Peru")
    class Peru {

        private final TaxRegime peru = TaxRegimes.of(TaxRegimeCode.PE);

        @Test
        @DisplayName("IGV in soles; SUNAT codes 01, 07 and 08; RUC of 11 digits")
        void vocabulary() {
            assertThat(peru.vatLabel()).isEqualTo("IGV");
            assertThat(peru.fiscalCurrency()).contains("PEN");
            assertThat(peru.documentCode(DocumentKind.INVOICE)).contains("01");
            assertThat(peru.documentCode(DocumentKind.CREDIT_NOTE)).contains("07");
            assertThat(peru.documentCode(DocumentKind.DEBIT_NOTE)).contains("08");
            assertThat(peru.kindOf("07")).contains(DocumentKind.CREDIT_NOTE);
            assertThat(peru.kindOf("CREDIT_NOTE")).contains(DocumentKind.CREDIT_NOTE);
            assertThat(peru.kindOf("04")).isEmpty();
            assertThat(peru.taxIdRule()).isEqualTo(TaxIdRule.PE_RUC);
            assertThat(peru.taxIdLength()).isEqualTo(11);
            assertThat(peru.withholdingKinds()).containsExactly(WithholdingKind.PE_DETRACTION,
                    WithholdingKind.PE_RETENTION);
            assertThat(peru.breakdownRequired("PEN")).isTrue();
            assertThat(peru.breakdownRequired("USD")).isFalse();
        }

        @Test
        @DisplayName("series-correlative: the series upper case, the correlative without leading zeros")
        void numbering() {
            assertThat(peru.normalizeDocumentNumber("F001-00000123")).contains("F001-123");
            assertThat(peru.normalizeDocumentNumber("f001-123")).contains("F001-123");
            assertThat(peru.normalizeDocumentNumber("FC01-99999999")).contains("FC01-99999999");
            assertThat(peru.normalizeDocumentNumber("E001-1")).contains("E001-1");
            assertThat(peru.normalizeDocumentNumber("0001-55")).contains("0001-55");
        }

        @ParameterizedTest
        @ValueSource(strings = {"B001-1", "E002-1", "F01-1", "F001-000", "F001-123456789", "F001123", "F001-12a", ""})
        @DisplayName("a boleta, a wrong series, a zero or too long correlative, no hyphen: refused")
        void refused(String number) {
            assertThat(peru.normalizeDocumentNumber(number)).isEmpty();
        }

        @Test
        @DisplayName("the published pattern accepts what the normaliser accepts")
        void pattern() {
            assertThat("F001-123".matches(peru.documentNumberPattern())).isTrue();
            assertThat("B001-123".matches(peru.documentNumberPattern())).isFalse();
        }
    }

    @Nested
    @DisplayName("Ecuador")
    class Ecuador {

        private final TaxRegime ecuador = TaxRegimes.of(TaxRegimeCode.EC);

        @Test
        @DisplayName("IVA in dollars; SRI codes 01, 04 and 05; RUC of 13 digits; IR and IVA withholdings")
        void vocabulary() {
            assertThat(ecuador.vatLabel()).isEqualTo("IVA");
            assertThat(ecuador.fiscalCurrency()).contains("USD");
            assertThat(ecuador.documentCode(DocumentKind.INVOICE)).contains("01");
            assertThat(ecuador.documentCode(DocumentKind.CREDIT_NOTE)).contains("04");
            assertThat(ecuador.documentCode(DocumentKind.DEBIT_NOTE)).contains("05");
            assertThat(ecuador.kindOf("04")).contains(DocumentKind.CREDIT_NOTE);
            assertThat(ecuador.kindOf("07")).isEmpty();
            assertThat(ecuador.taxIdRule()).isEqualTo(TaxIdRule.EC_RUC);
            assertThat(ecuador.taxIdLength()).isEqualTo(13);
            assertThat(ecuador.carrierTaxIdTypes()).containsExactly("RUC");
            assertThat(ecuador.withholdingKinds()).containsExactly(WithholdingKind.EC_INCOME_TAX_WITHHOLDING,
                    WithholdingKind.EC_VAT_WITHHOLDING);
            assertThat(ecuador.breakdownRequired("USD")).isTrue();
        }

        @Test
        @DisplayName("establecimiento-punto de emisión-secuencial, 3+3+9, the sequential padded to nine digits")
        void numbering() {
            assertThat(ecuador.normalizeDocumentNumber("001-001-000000123")).contains("001-001-000000123");
            assertThat(ecuador.normalizeDocumentNumber("001-002-123")).contains("001-002-000000123");
            assertThat(ecuador.normalizeDocumentNumber(" 002-101-999999999 ")).contains("002-101-999999999");
            assertThat("001-001-000000123".matches(ecuador.documentNumberPattern())).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"000-001-000000123", "001-000-000000123", "001-001-000000000", "1-1-123",
                "001001000000123", "001-001-1234567890", "F001-123", ""})
        @DisplayName("a zero establishment, point or sequential, short parts, no hyphens, ten digits: refused")
        void refused(String number) {
            assertThat(ecuador.normalizeDocumentNumber(number)).isEmpty();
        }
    }

    @Nested
    @DisplayName("any other country")
    class Generic {

        private final TaxRegime generic = TaxRegimes.forCountry("CO");

        @Test
        @DisplayName("no currency enforced, no codes, free-text number and tax id, no withholdings")
        void vocabulary() {
            assertThat(generic.fiscalCurrency()).isEqualTo(Optional.empty());
            assertThat(generic.documentCode(DocumentKind.INVOICE)).isEmpty();
            assertThat(generic.kindOf("INVOICE")).contains(DocumentKind.INVOICE);
            assertThat(generic.documentNumberPattern()).isNull();
            assertThat(generic.normalizeDocumentNumber(" FE-1234 ")).contains("FE-1234");
            assertThat(generic.normalizeDocumentNumber("  ")).isEmpty();
            assertThat(generic.taxIdRule()).isEqualTo(TaxIdRule.FREE_TEXT);
            assertThat(generic.carrierTaxIdTypes()).isEmpty();
            assertThat(generic.withholdingKinds()).isEqualTo(List.of());
            assertThat(generic.breakdownRequired("COP")).isFalse();
        }
    }

    @Test
    @DisplayName("every withholding kind belongs to exactly the regime of its country")
    void kindsBelongToTheirCountry() {
        for (WithholdingKind kind : WithholdingKind.values()) {
            assertThat(TaxRegimes.forCountry(kind.country()).withholdingKinds()).contains(kind);
        }
    }
}
