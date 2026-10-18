package com.ebim.tms.settlement.domain.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * One company's tax rules, resolved (V66, ADR-027, D-06): its regime, and every number the regime
 * needs - the company's own value where it stored one, the deployment's
 * {@code tms.finance.tax.<country>.*} otherwise. Built by {@code FiscalSettingsResolver}; never a
 * literal.
 *
 * @param country         the resolved fiscal country (ISO alpha-2); for the generic regime, the
 *                        company's own country
 * @param vatRate         the general VAT percentage; may be null under the generic regime
 * @param freightVatRate  the percentage a freight document is previewed with; may be null
 * @param allowedVatRates the rates a document may state; empty = any {@code 0 <= rate < 100}
 * @param withholdings    one rule per kind of the regime, in its order
 */
public record TaxRules(TaxRegime regime, String country, BigDecimal vatRate, BigDecimal freightVatRate,
        List<BigDecimal> allowedVatRates, List<WithholdingRule> withholdings) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public TaxRules {
        allowedVatRates = allowedVatRates == null ? List.of() : List.copyOf(allowedVatRates);
        withholdings = withholdings == null ? List.of() : List.copyOf(withholdings);
    }

    /** Whether a document may state this VAT rate. */
    public boolean allowsVatRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(HUNDRED) >= 0) {
            return false;
        }
        return allowedVatRates.isEmpty() || allowedVatRates.stream().anyMatch(allowed -> allowed.compareTo(rate) == 0);
    }

    public Optional<WithholdingRule> rule(WithholdingKind kind) {
        return withholdings.stream().filter(rule -> rule.kind() == kind).findFirst();
    }
}
