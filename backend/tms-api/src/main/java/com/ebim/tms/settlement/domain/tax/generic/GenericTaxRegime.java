package com.ebim.tms.settlement.domain.tax.generic;

import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRegimeCode;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.shared.tax.TaxIdRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every country without a regime of its own (ADR-027): a VAT that may be broken out, no currency
 * enforced, no document codes, a free-text number and tax id, and <b>no withholdings</b>. A company in
 * Colombia or the United States receives and pays carrier documents exactly as before V66; adding a
 * country's withholdings is a new regime, not a configuration of this one.
 */
public final class GenericTaxRegime implements TaxRegime {

    public static final GenericTaxRegime INSTANCE = new GenericTaxRegime();

    private GenericTaxRegime() {
    }

    @Override
    public TaxRegimeCode code() {
        return TaxRegimeCode.GENERIC;
    }

    @Override
    public String vatLabel() {
        return "IVA/VAT";
    }

    @Override
    public Optional<String> fiscalCurrency() {
        return Optional.empty();
    }

    @Override
    public Optional<String> documentCode(DocumentKind kind) {
        return Optional.empty();
    }

    @Override
    public Optional<String> normalizeDocumentNumber(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() || trimmed.length() > 60 ? Optional.empty() : Optional.of(trimmed);
    }

    @Override
    public String documentNumberPattern() {
        return null;
    }

    @Override
    public String documentNumberPlaceholder() {
        return null;
    }

    @Override
    public String documentNumberHint() {
        return "El número fiscal tal como figura en el documento (hasta 60 caracteres)";
    }

    @Override
    public TaxIdRule taxIdRule() {
        return TaxIdRule.FREE_TEXT;
    }

    @Override
    public String taxIdLabel() {
        return "Identificación fiscal";
    }

    @Override
    public Integer taxIdLength() {
        return null;
    }

    @Override
    public String taxIdHint() {
        return "Letras, dígitos y guiones, hasta 20 caracteres";
    }

    @Override
    public Set<String> carrierTaxIdTypes() {
        return Set.of();
    }

    @Override
    public List<WithholdingKind> withholdingKinds() {
        return List.of();
    }
}
