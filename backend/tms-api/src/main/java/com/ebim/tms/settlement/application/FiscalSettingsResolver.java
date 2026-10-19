package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRegimes;
import com.ebim.tms.settlement.domain.tax.TaxRules;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.settlement.domain.tax.WithholdingRule;
import com.ebim.tms.shared.settings.FiscalSettings;
import com.ebim.tms.shared.settings.FiscalSettingsPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * A company's tax rules (V66, ADR-027, D-06): the regime of its fiscal country (stored
 * {@code fiscal_country}, else {@code default_country}), and each number its own value where it
 * stored one, else the deployment's {@code tms.finance.tax.<regime>.*}. No rate is a literal in Java
 * or SQL.
 *
 * <p>The allowed VAT rates are the regime's configured list plus the company's own general and
 * freight rates: a company that sets a rate may always state it.
 */
@Component
@EnableConfigurationProperties(TaxSettingsProperties.class)
public class FiscalSettingsResolver {

    private final FiscalSettingsPort fiscalSettingsPort;
    private final TaxSettingsProperties properties;

    public FiscalSettingsResolver(FiscalSettingsPort fiscalSettingsPort, TaxSettingsProperties properties) {
        this.fiscalSettingsPort = fiscalSettingsPort;
        this.properties = properties;
    }

    /** What the company stored, unresolved. */
    public FiscalSettings storedOf(UUID companyId) {
        return fiscalSettingsPort.fiscalSettingsOf(companyId);
    }

    public TaxRules rulesOf(UUID companyId) {
        return resolve(storedOf(companyId), properties);
    }

    static TaxRules resolve(FiscalSettings stored, TaxSettingsProperties properties) {
        String country = stored.effectiveCountry().toUpperCase(Locale.ROOT);
        TaxRegime regime = TaxRegimes.forCountry(country);
        TaxSettingsProperties.Country defaults = properties.of(regime.code());

        BigDecimal vatRate = or(stored.vatRate(), defaults.vatRate());
        BigDecimal freightVatRate = or(stored.freightVatRate(), defaults.freightVatRate());
        List<BigDecimal> allowed = new ArrayList<>(defaults.allowedVatRates());
        if (!allowed.isEmpty()) {
            addIfAbsent(allowed, vatRate);
            addIfAbsent(allowed, freightVatRate);
        }

        List<WithholdingRule> rules = new ArrayList<>();
        for (WithholdingKind kind : regime.withholdingKinds()) {
            TaxSettingsProperties.Withholding fallback = defaults.withholding(kind);
            FiscalSettings.WithholdingSetting own = stored.withholdings().stream()
                    .filter(setting -> kind.name().equals(setting.kind()))
                    .findFirst()
                    .orElse(new FiscalSettings.WithholdingSetting(kind.name(), null, null, null, null));
            rules.add(new WithholdingRule(kind,
                    Boolean.TRUE.equals(or(own.enabled(), fallback.enabled())),
                    or(own.code(), fallback.code()),
                    or(own.rate(), fallback.rate()),
                    or(own.thresholdAmount(), fallback.thresholdAmount())));
        }
        return new TaxRules(regime, country, vatRate, freightVatRate, allowed, rules);
    }

    private static void addIfAbsent(List<BigDecimal> rates, BigDecimal rate) {
        if (rate != null && rates.stream().noneMatch(existing -> existing.compareTo(rate) == 0)) {
            rates.add(rate);
        }
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
