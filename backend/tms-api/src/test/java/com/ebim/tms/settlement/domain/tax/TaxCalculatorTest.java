package com.ebim.tms.settlement.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Breakdown rounding and the withholdings of each regime (V66, ADR-024 part 2 items 2, 7 and 8,
 * ADR-027). The rates are the defaults a company that set nothing resolves to; the test states them,
 * the code does not.
 */
class TaxCalculatorTest {

    private static TaxRules peru(boolean detraction, boolean retentionAgent) {
        return new TaxRules(TaxRegimes.forCountry("PE"), "PE", new BigDecimal("18.00"), new BigDecimal("18.00"),
                List.of(new BigDecimal("18.00"), BigDecimal.ZERO), List.of(
                new WithholdingRule(WithholdingKind.PE_DETRACTION, detraction, "027", new BigDecimal("4.00"),
                        new BigDecimal("400.00")),
                new WithholdingRule(WithholdingKind.PE_RETENTION, retentionAgent, null, new BigDecimal("3.00"), null)));
    }

    private static TaxRules ecuador(boolean income, boolean vatAgent) {
        return new TaxRules(TaxRegimes.forCountry("EC"), "EC", new BigDecimal("15.00"), BigDecimal.ZERO,
                List.of(BigDecimal.ZERO, new BigDecimal("5.00"), new BigDecimal("15.00")), List.of(
                new WithholdingRule(WithholdingKind.EC_INCOME_TAX_WITHHOLDING, income, "310", new BigDecimal("1.00"),
                        null),
                new WithholdingRule(WithholdingKind.EC_VAT_WITHHOLDING, vatAgent, null, new BigDecimal("70.00"), null)));
    }

    private static final TaxRules PERU = peru(true, false);

    private static List<ComputedWithholding> compute(TaxRules rules, DocumentKind kind, String currency,
            String subtotal, String tax, String total) {
        return TaxCalculator.compute(rules, kind, currency, subtotal == null ? null : new BigDecimal(subtotal),
                tax == null ? null : new BigDecimal(tax), new BigDecimal(total));
    }

    @Nested
    @DisplayName("the breakdown adds up within one cent")
    class Breakdown {

        @ParameterizedTest
        @CsvSource({"1000.00,180.00,1180.00,true", "1000.00,180.00,1180.01,true", "1000.00,180.00,1179.99,true",
                "1000.00,180.00,1180.02,false", "1000.00,180.00,1179.98,false", "84.75,15.26,100.00,true",
                "84.74,15.24,100.00,false"})
        void tolerance(String subtotal, String tax, String total, boolean addsUp) {
            assertThat(TaxCalculator.breakdownAddsUp(new BigDecimal(subtotal), new BigDecimal(tax),
                    new BigDecimal(total))).isEqualTo(addsUp);
        }

        @Test
        @DisplayName("the VAT a subtotal calls for is rounded half up to the cent")
        void vatOf() {
            assertThat(TaxCalculator.vatOf(new BigDecimal("84.75"), new BigDecimal("18.00")))
                    .isEqualByComparingTo("15.26"); // 15.255
            assertThat(TaxCalculator.vatOf(new BigDecimal("1000"), new BigDecimal("15"))).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("a stated rate must be one the regime allows")
        void allowedRates() {
            TaxRules ecuador = ecuador(true, false);
            assertThat(ecuador.allowsVatRate(BigDecimal.ZERO)).isTrue();
            assertThat(ecuador.allowsVatRate(new BigDecimal("15.00"))).isTrue();
            assertThat(ecuador.allowsVatRate(new BigDecimal("5"))).isTrue();
            assertThat(ecuador.allowsVatRate(new BigDecimal("12.00"))).isFalse(); // the rate before 2024
            assertThat(PERU.allowsVatRate(new BigDecimal("18"))).isTrue();
            assertThat(PERU.allowsVatRate(new BigDecimal("10"))).isFalse();
            TaxRules generic = new TaxRules(TaxRegimes.forCountry("CO"), "CO", null, null, List.of(), List.of());
            assertThat(generic.allowsVatRate(new BigDecimal("19"))).isTrue();
            assertThat(generic.allowsVatRate(new BigDecimal("100"))).isFalse();
            assertThat(generic.allowsVatRate(new BigDecimal("-1"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Peru: detraction strictly above the threshold, in whole soles")
    class PeruDetraction {

        @ParameterizedTest
        @CsvSource({"399.99,", "400.00,", "400.01,16.00", "1180.00,47.00", "1187.50,48.00", "1162.50,47.00"})
        @DisplayName("399.99 and 400.00 are not subject; 400.01 is; amounts round half up to the sol")
        void threshold(String total, String expected) {
            List<ComputedWithholding> withholdings = compute(PERU, DocumentKind.INVOICE, "PEN", null, null, total);
            if (expected == null) {
                assertThat(withholdings).isEmpty();
            } else {
                assertThat(withholdings).singleElement().satisfies(detraction -> {
                    assertThat(detraction.kind()).isEqualTo(WithholdingKind.PE_DETRACTION);
                    assertThat(detraction.amount()).isEqualByComparingTo(expected);
                    assertThat(detraction.amount().scale()).isEqualTo(2);
                    assertThat(detraction.code()).isEqualTo("027");
                    assertThat(detraction.rate()).isEqualByComparingTo("4.00");
                    assertThat(detraction.base()).isEqualTo(WithholdingBase.TOTAL);
                    assertThat(detraction.baseAmount()).isEqualByComparingTo(total);
                });
            }
        }

        @Test
        @DisplayName("the rule is named: strictly above, HALF_UP to zero decimals, not overridable")
        void namedRules() {
            assertThat(WithholdingKind.PE_DETRACTION.strictlyAboveThreshold()).isTrue();
            assertThat(WithholdingKind.PE_DETRACTION.scale()).isZero();
            assertThat(WithholdingKind.PE_DETRACTION.overridable()).isFalse();
            assertThat(TaxCalculator.exceedsThreshold(WithholdingKind.PE_DETRACTION, new BigDecimal("400.00"),
                    new BigDecimal("400.00"))).isFalse();
            assertThat(TaxCalculator.exceedsThreshold(WithholdingKind.PE_DETRACTION, new BigDecimal("400.01"),
                    new BigDecimal("400.00"))).isTrue();
        }

        @Test
        @DisplayName("not in another currency, not on a note, not when the company disabled it")
        void notApplicable() {
            assertThat(compute(PERU, DocumentKind.INVOICE, "USD", null, null, "5000")).isEmpty();
            assertThat(compute(PERU, DocumentKind.CREDIT_NOTE, "PEN", null, null, "5000")).isEmpty();
            assertThat(compute(peru(false, false), DocumentKind.INVOICE, "PEN", null, null, "5000")).isEmpty();
        }

        @Test
        @DisplayName("the company's own rate and threshold are used, not D-06's")
        void companyValues() {
            TaxRules own = new TaxRules(TaxRegimes.forCountry("PE"), "PE", new BigDecimal("18"), new BigDecimal("18"),
                    List.of(), List.of(new WithholdingRule(WithholdingKind.PE_DETRACTION, true, "027",
                            new BigDecimal("10.00"), new BigDecimal("700.00"))));
            assertThat(compute(own, DocumentKind.INVOICE, "PEN", null, null, "700.00")).isEmpty();
            assertThat(compute(own, DocumentKind.INVOICE, "PEN", null, null, "1000.00"))
                    .singleElement().extracting(ComputedWithholding::amount)
                    .satisfies(amount -> assertThat(amount).isEqualByComparingTo("100.00"));
        }
    }

    @Nested
    @DisplayName("Peru: retention, only where no detraction applies")
    class PeruRetention {

        @Test
        @DisplayName("a retention agent retains 3 % to the cent below the detraction threshold")
        void retains() {
            assertThat(compute(peru(true, true), DocumentKind.INVOICE, "PEN", null, null, "350.55"))
                    .singleElement().satisfies(retention -> {
                        assertThat(retention.kind()).isEqualTo(WithholdingKind.PE_RETENTION);
                        assertThat(retention.amount()).isEqualByComparingTo("10.52"); // 10.5165
                        assertThat(retention.rate()).isEqualByComparingTo("3.00");
                    });
        }

        @Test
        @DisplayName("excluded when detraction applies: the two never coexist")
        void exclusiveWithDetraction() {
            assertThat(compute(peru(true, true), DocumentKind.INVOICE, "PEN", null, null, "1180.00"))
                    .extracting(ComputedWithholding::kind).containsExactly(WithholdingKind.PE_DETRACTION);
        }

        @Test
        @DisplayName("an agent whose detraction is disabled retains on any total")
        void retainsWhenDetractionIsOff() {
            assertThat(compute(peru(false, true), DocumentKind.INVOICE, "PEN", null, null, "1180.00"))
                    .extracting(ComputedWithholding::kind).containsExactly(WithholdingKind.PE_RETENTION);
        }

        @Test
        @DisplayName("a company that is not an agent retains nothing")
        void notAnAgent() {
            assertThat(compute(PERU, DocumentKind.INVOICE, "PEN", null, null, "350")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Ecuador: income-tax withholding on the subtotal, VAT withholding on the VAT")
    class Ecuador {

        @Test
        @DisplayName("freight at VAT 0%: IR 1% code 310 on the subtotal, and the VAT withholding yields nothing")
        void freightAtZero() {
            List<ComputedWithholding> withholdings = compute(ecuador(true, true), DocumentKind.INVOICE, "USD",
                    "1234.56", "0.00", "1234.56");
            assertThat(withholdings).singleElement().satisfies(income -> {
                assertThat(income.kind()).isEqualTo(WithholdingKind.EC_INCOME_TAX_WITHHOLDING);
                assertThat(income.code()).isEqualTo("310");
                assertThat(income.base()).isEqualTo(WithholdingBase.SUBTOTAL);
                assertThat(income.baseAmount()).isEqualByComparingTo("1234.56");
                assertThat(income.amount()).isEqualByComparingTo("12.35"); // 12.3456
            });
        }

        @Test
        @DisplayName("a VAT-withholding agent on a taxed document: both withholdings on one document")
        void both() {
            List<ComputedWithholding> withholdings = compute(ecuador(true, true), DocumentKind.INVOICE, "USD",
                    "1000.00", "150.00", "1150.00");
            assertThat(withholdings).extracting(ComputedWithholding::kind).containsExactly(
                    WithholdingKind.EC_INCOME_TAX_WITHHOLDING, WithholdingKind.EC_VAT_WITHHOLDING);
            assertThat(withholdings.get(0).amount()).isEqualByComparingTo("10.00");
            assertThat(withholdings.get(1).base()).isEqualTo(WithholdingBase.VAT);
            assertThat(withholdings.get(1).baseAmount()).isEqualByComparingTo("150.00");
            assertThat(withholdings.get(1).amount()).isEqualByComparingTo("105.00");
        }

        @Test
        @DisplayName("the VAT withholding is off by default: a company that is no agent withholds only the IR")
        void notAVatAgent() {
            assertThat(compute(ecuador(true, false), DocumentKind.INVOICE, "USD", "1000.00", "150.00", "1150.00"))
                    .extracting(ComputedWithholding::kind).containsExactly(WithholdingKind.EC_INCOME_TAX_WITHHOLDING);
        }

        @Test
        @DisplayName("nothing on a note, outside USD, or without a subtotal to apply the IR to")
        void notApplicable() {
            assertThat(compute(ecuador(true, true), DocumentKind.CREDIT_NOTE, "USD", "100", "0", "100")).isEmpty();
            assertThat(compute(ecuador(true, true), DocumentKind.INVOICE, "EUR", "100", "0", "100")).isEmpty();
            assertThat(compute(ecuador(true, false), DocumentKind.INVOICE, "USD", null, null, "100")).isEmpty();
        }
    }

    @Test
    @DisplayName("a generic regime withholds nothing, in any currency")
    void generic() {
        TaxRules colombia = new TaxRules(TaxRegimes.forCountry("CO"), "CO", new BigDecimal("19"), null, List.of(),
                List.of());
        assertThat(compute(colombia, DocumentKind.INVOICE, "COP", "1000000", "190000", "1190000")).isEmpty();
        assertThat(compute(colombia, DocumentKind.INVOICE, "USD", "1000", "0", "1000")).isEmpty();
    }
}
