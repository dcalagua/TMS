package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.tax.TaxCalculator;
import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRules;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.settlement.domain.tax.WithholdingRule;
import com.ebim.tms.shared.settings.FiscalSettings;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * A company's tax regime, resolved (V66, ADR-027): everything an invoice form needs to label, validate
 * and preview a carrier document with the same rules the server applies - so the frontend carries no
 * rate, code, label or format of its own. Plus what the company stored, for the settings screen.
 *
 * @param electronicValidation what the tax authority says of a document: always
 *                             {@code EXTERNAL_DEPENDENCY} (TMS receives documents; it does not consult
 *                             SUNAT or the SRI, and never presents a document as "valid")
 */
public record TaxRegimeView(
        String regime,
        String country,
        String defaultCountry,
        String fiscalCurrency,
        String vatLabel,
        BigDecimal vatRate,
        BigDecimal freightVatRate,
        List<BigDecimal> allowedVatRates,
        boolean breakdownRequired,
        BigDecimal breakdownTolerance,
        List<DocumentKindView> documentKinds,
        DocumentNumberView documentNumber,
        TaxIdView taxId,
        String electronicValidation,
        List<WithholdingRuleView> withholdings,
        StoredView stored) {

    public record DocumentKindView(String kind, String code, String label) {
    }

    /** @param pattern a regular expression valid in Java and JavaScript; null = free text */
    public record DocumentNumberView(String pattern, String placeholder, String hint) {
    }

    public record TaxIdView(String label, String rule, Integer length, String pattern, String hint) {
    }

    /**
     * One withholding of the regime with its resolved numbers, and the rules of its kind.
     *
     * @param requiresNoOther kinds whose presence on the document excludes this one
     */
    public record WithholdingRuleView(String kind, String label, boolean enabled, String code, BigDecimal rate,
            String base, BigDecimal thresholdAmount, boolean strictlyAboveThreshold, int scale, boolean overridable,
            List<String> requiresNoOther) {

        static WithholdingRuleView of(WithholdingRule rule) {
            WithholdingKind kind = rule.kind();
            return new WithholdingRuleView(kind.name(), kind.label(), rule.enabled(), rule.code(), rule.rate(),
                    kind.base().name(), rule.thresholdAmount(), kind.strictlyAboveThreshold(), kind.scale(),
                    kind.overridable(), kind.excludedBy().stream().map(Enum::name).sorted().toList());
        }
    }

    /** What the company stored; a null is "inherits the default". */
    public record StoredView(String fiscalCountry, BigDecimal vatRate, BigDecimal freightVatRate,
            List<FiscalSettings.WithholdingSetting> withholdings) {
    }

    public static TaxRegimeView of(TaxRules rules, FiscalSettings stored) {
        TaxRegime regime = rules.regime();
        List<DocumentKindView> kinds = Arrays.stream(DocumentKind.values())
                .map(kind -> new DocumentKindView(kind.name(), regime.documentCode(kind).orElse(null), kind.label()))
                .toList();
        Integer length = regime.taxIdLength();
        String taxIdPattern = length == null ? null : "^[0-9]{" + length + "}$";
        return new TaxRegimeView(regime.code().name(), rules.country(), stored.defaultCountry(),
                regime.fiscalCurrency().orElse(null), regime.vatLabel(), rules.vatRate(), rules.freightVatRate(),
                rules.allowedVatRates().stream().sorted().toList(),
                regime.fiscalCurrency().isPresent(), TaxCalculator.BREAKDOWN_TOLERANCE, kinds,
                new DocumentNumberView(regime.documentNumberPattern(), regime.documentNumberPlaceholder(),
                        regime.documentNumberHint()),
                new TaxIdView(regime.taxIdLabel(), regime.taxIdRule().name(), length, taxIdPattern,
                        regime.taxIdHint()),
                FiscalDocumentValidationPort.FiscalDocumentValidationStatus.EXTERNAL_DEPENDENCY.name(),
                rules.withholdings().stream().map(WithholdingRuleView::of).toList(),
                new StoredView(stored.fiscalCountry(), stored.vatRate(), stored.freightVatRate(),
                        stored.withholdings()));
    }
}
