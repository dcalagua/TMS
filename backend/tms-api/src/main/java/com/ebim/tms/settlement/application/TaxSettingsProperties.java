package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.tax.TaxRegimeCode;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deployment's tax defaults per regime, {@code tms.finance.tax.<pe|ec|generic>.*} (ADR-027, D-06):
 * what a company that stored nothing of its own gets. The values live in {@code application.yml}
 * with their environment variables; no rate is a literal in Java.
 *
 * <p>Withholdings are keyed by {@link WithholdingKind#propertyKey()} ({@code detraction},
 * {@code retention}, {@code income-tax}, {@code vat}) - hyphenated keys, because a Spring map key
 * outside brackets loses its underscores.
 */
@ConfigurationProperties(prefix = "tms.finance.tax")
public record TaxSettingsProperties(Country pe, Country ec, Country generic) {

    public TaxSettingsProperties {
        pe = pe == null ? Country.EMPTY : pe;
        ec = ec == null ? Country.EMPTY : ec;
        generic = generic == null ? Country.EMPTY : generic;
    }

    public Country of(TaxRegimeCode regime) {
        return switch (regime) {
            case PE -> pe;
            case EC -> ec;
            case GENERIC -> generic;
        };
    }

    /**
     * @param allowedVatRates the rates a document may state; empty = any {@code 0 <= rate < 100}
     */
    public record Country(BigDecimal vatRate, BigDecimal freightVatRate, List<BigDecimal> allowedVatRates,
            Map<String, Withholding> withholdings) {

        static final Country EMPTY = new Country(null, null, List.of(), Map.of());

        public Country {
            allowedVatRates = allowedVatRates == null ? List.of() : List.copyOf(allowedVatRates);
            withholdings = withholdings == null ? Map.of() : Map.copyOf(withholdings);
        }

        public Withholding withholding(WithholdingKind kind) {
            return withholdings.getOrDefault(kind.propertyKey(), Withholding.OFF);
        }
    }

    /** One withholding's defaults. Absent from the configuration = off. */
    public record Withholding(Boolean enabled, String code, BigDecimal rate, BigDecimal thresholdAmount) {

        static final Withholding OFF = new Withholding(false, null, null, null);
    }
}
