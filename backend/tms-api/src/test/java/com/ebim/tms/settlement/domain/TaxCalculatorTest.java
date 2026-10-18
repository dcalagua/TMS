package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Breakdown rounding, detraction by threshold and retention (V66, ADR-024 part 2, items 2, 7, 8). */
class TaxCalculatorTest {

    /** D-06's values, as a company that set nothing resolves them; the test states them, the code does not. */
    private static final FiscalRules PERU = new FiscalRules(new BigDecimal("18.00"), true, "027",
            new BigDecimal("4.00"), new BigDecimal("400.00"), false, new BigDecimal("3.00"));

    private static final FiscalRules RETENTION_AGENT = new FiscalRules(new BigDecimal("18.00"), true, "027",
            new BigDecimal("4.00"), new BigDecimal("400.00"), true, new BigDecimal("3.00"));

    @Nested
    @DisplayName("the breakdown adds up within one cent")
    class Breakdown {

        @ParameterizedTest
        @CsvSource({"1000.00,180.00,1180.00,true", "1000.00,180.00,1180.01,true", "1000.00,180.00,1179.99,true",
                "1000.00,180.00,1180.02,false", "1000.00,180.00,1179.98,false", "84.75,15.26,100.00,true",
                "84.74,15.24,100.00,false"})
        void tolerance(String subtotal, String igv, String total, boolean addsUp) {
            assertThat(TaxCalculator.breakdownAddsUp(new BigDecimal(subtotal), new BigDecimal(igv),
                    new BigDecimal(total))).isEqualTo(addsUp);
        }

        @Test
        @DisplayName("the IGV a subtotal calls for is rounded half up to the cent")
        void igvOf() {
            assertThat(TaxCalculator.igvOf(new BigDecimal("84.75"), new BigDecimal("18.00")))
                    .isEqualByComparingTo("15.26"); // 15.255
            assertThat(TaxCalculator.igvOf(new BigDecimal("1000"), new BigDecimal("18"))).isEqualByComparingTo("180.00");
        }
    }

    @Nested
    @DisplayName("detraction applies strictly above the threshold, in whole soles")
    class Detraction {

        @ParameterizedTest
        @CsvSource({"399.99,", "400.00,", "400.01,16.00", "1180.00,47.00", "1187.50,48.00", "1162.50,47.00"})
        @DisplayName("399.99 and 400.00 are not subject; 400.01 is; amounts round half up to the sol")
        void threshold(String total, String expected) {
            TaxCalculator.TaxOutcome outcome =
                    TaxCalculator.compute(PERU, DocumentType.INVOICE, "PEN", new BigDecimal(total));
            if (expected == null) {
                assertThat(outcome.detraction()).isNull();
            } else {
                assertThat(outcome.detraction().amount()).isEqualByComparingTo(expected);
                assertThat(outcome.detraction().amount().scale()).isEqualTo(2);
                assertThat(outcome.detraction().code()).isEqualTo("027");
                assertThat(outcome.detraction().rate()).isEqualByComparingTo("4.00");
            }
            assertThat(outcome.retention()).isNull();
        }

        @Test
        @DisplayName("the rule is named: strictly above, HALF_UP to zero decimals")
        void namedRules() {
            assertThat(TaxCalculator.DETRACTION_STRICTLY_ABOVE_THRESHOLD).isTrue();
            assertThat(TaxCalculator.DETRACTION_SCALE).isZero();
            assertThat(TaxCalculator.exceedsThreshold(new BigDecimal("400.00"), new BigDecimal("400.00"))).isFalse();
            assertThat(TaxCalculator.exceedsThreshold(new BigDecimal("400.01"), new BigDecimal("400.00"))).isTrue();
        }

        @Test
        @DisplayName("not in another currency, not on a note, not when the company disabled it")
        void notApplicable() {
            assertThat(TaxCalculator.compute(PERU, DocumentType.INVOICE, "USD", new BigDecimal("5000")))
                    .isEqualTo(TaxCalculator.TaxOutcome.NONE);
            assertThat(TaxCalculator.compute(PERU, DocumentType.CREDIT_NOTE, "PEN", new BigDecimal("5000")))
                    .isEqualTo(TaxCalculator.TaxOutcome.NONE);
            FiscalRules disabled = new FiscalRules(new BigDecimal("18"), false, "027", new BigDecimal("4"),
                    new BigDecimal("400"), false, new BigDecimal("3"));
            assertThat(TaxCalculator.compute(disabled, DocumentType.INVOICE, "PEN", new BigDecimal("5000")))
                    .isEqualTo(TaxCalculator.TaxOutcome.NONE);
        }

        @Test
        @DisplayName("the company's own rate and threshold are used, not D-06's")
        void companyValues() {
            FiscalRules own = new FiscalRules(new BigDecimal("18"), true, "027", new BigDecimal("10.00"),
                    new BigDecimal("700.00"), false, new BigDecimal("3"));
            assertThat(TaxCalculator.compute(own, DocumentType.INVOICE, "PEN", new BigDecimal("700.00")).detraction())
                    .isNull();
            assertThat(TaxCalculator.compute(own, DocumentType.INVOICE, "PEN", new BigDecimal("1000.00"))
                    .detraction().amount()).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    @DisplayName("retention, only where no detraction applies")
    class Retention {

        @Test
        @DisplayName("a retention agent retains 3 % to the cent below the detraction threshold")
        void retains() {
            TaxCalculator.TaxOutcome outcome =
                    TaxCalculator.compute(RETENTION_AGENT, DocumentType.INVOICE, "PEN", new BigDecimal("350.55"));
            assertThat(outcome.detraction()).isNull();
            assertThat(outcome.retention().amount()).isEqualByComparingTo("10.52"); // 10.5165
            assertThat(outcome.retention().rate()).isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("excluded when detraction applies: the two never coexist")
        void exclusiveWithDetraction() {
            TaxCalculator.TaxOutcome outcome =
                    TaxCalculator.compute(RETENTION_AGENT, DocumentType.INVOICE, "PEN", new BigDecimal("1180.00"));
            assertThat(outcome.detraction()).isNotNull();
            assertThat(outcome.retention()).isNull();
        }

        @Test
        @DisplayName("a company that is not an agent retains nothing")
        void notAnAgent() {
            assertThat(TaxCalculator.compute(PERU, DocumentType.INVOICE, "PEN", new BigDecimal("350")).retention())
                    .isNull();
        }
    }
}
