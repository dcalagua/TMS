package com.ebim.tms.shared.settings;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one company stored of its fiscal settings (V66, ADR-027). <b>Every override may be null</b>,
 * and a null is "use the deployment's default for the regime" (decision D-06), resolved by
 * {@code settlement.application.FiscalSettingsResolver}; nothing here is a default itself.
 *
 * @param defaultCountry the company's {@code default_country} (V34), never null: what a null
 *                       {@code fiscalCountry} falls back to
 * @param fiscalCountry  the country whose tax regime applies, or null
 * @param withholdings   the company's own values per withholding kind; a kind absent is all defaults
 */
public record FiscalSettings(
        String defaultCountry,
        String fiscalCountry,
        BigDecimal vatRate,
        BigDecimal freightVatRate,
        List<WithholdingSetting> withholdings) {

    public FiscalSettings {
        withholdings = withholdings == null ? List.of() : List.copyOf(withholdings);
    }

    /**
     * One company override of one withholding kind; each field null = the default.
     *
     * @param kind the {@code WithholdingKind} name (a string here: {@code shared} does not know the
     *             regimes, which are {@code settlement}'s)
     */
    public record WithholdingSetting(String kind, Boolean enabled, String code, BigDecimal rate,
            BigDecimal thresholdAmount) {
    }

    /** A company that stored nothing, or has no settings row: taxed by its default country. */
    public static FiscalSettings unset(String defaultCountry) {
        return new FiscalSettings(defaultCountry, null, null, null, List.of());
    }

    /** The country whose regime applies: the stored fiscal country, else the default country. */
    public String effectiveCountry() {
        return fiscalCountry != null ? fiscalCountry : defaultCountry;
    }
}
