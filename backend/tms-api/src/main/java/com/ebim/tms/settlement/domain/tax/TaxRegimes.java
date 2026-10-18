package com.ebim.tms.settlement.domain.tax;

import com.ebim.tms.settlement.domain.tax.ec.EcuadorTaxRegime;
import com.ebim.tms.settlement.domain.tax.generic.GenericTaxRegime;
import com.ebim.tms.settlement.domain.tax.pe.PeruTaxRegime;
import java.util.Locale;

/** Which regime a country is taxed by (ADR-027): Peru and Ecuador their own, every other the generic one. */
public final class TaxRegimes {

    private TaxRegimes() {
    }

    public static TaxRegime forCountry(String country) {
        String code = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return switch (code) {
            case "PE" -> PeruTaxRegime.INSTANCE;
            case "EC" -> EcuadorTaxRegime.INSTANCE;
            default -> GenericTaxRegime.INSTANCE;
        };
    }

    public static TaxRegime of(TaxRegimeCode code) {
        return switch (code) {
            case PE -> PeruTaxRegime.INSTANCE;
            case EC -> EcuadorTaxRegime.INSTANCE;
            case GENERIC -> GenericTaxRegime.INSTANCE;
        };
    }
}
