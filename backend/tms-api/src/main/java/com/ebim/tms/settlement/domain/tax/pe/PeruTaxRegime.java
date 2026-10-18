package com.ebim.tms.settlement.domain.tax.pe;

import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRegimeCode;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.shared.tax.TaxIdRule;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Peru (ADR-024 part 2, ADR-027): IGV, soles, SUNAT document codes {@code 01} factura, {@code 07}
 * nota de crédito, {@code 08} nota de débito, the electronic number {@code series-correlative}, the RUC
 * and two withholdings - the SPOT detraction or, failing it, the IGV retention.
 *
 * <p>Series: {@code F} + 3 alphanumerics (electronic factura and its notes), {@code E001} (issued
 * from SUNAT's portal) or 4 digits (a contingency paper series). Correlative: up to 8 digits, stored
 * without leading zeros so {@code F001-00000123} and {@code F001-123} are one document.
 */
public final class PeruTaxRegime implements TaxRegime {

    public static final PeruTaxRegime INSTANCE = new PeruTaxRegime();

    private static final Pattern NUMBER = Pattern.compile("^(F[A-Z0-9]{3}|E001|[0-9]{4})-([0-9]{1,8})$");

    private PeruTaxRegime() {
    }

    @Override
    public TaxRegimeCode code() {
        return TaxRegimeCode.PE;
    }

    @Override
    public String vatLabel() {
        return "IGV";
    }

    @Override
    public Optional<String> fiscalCurrency() {
        return Optional.of("PEN");
    }

    @Override
    public Optional<String> documentCode(DocumentKind kind) {
        return Optional.of(switch (kind) {
            case INVOICE -> "01";
            case CREDIT_NOTE -> "07";
            case DEBIT_NOTE -> "08";
        });
    }

    @Override
    public Optional<String> normalizeDocumentNumber(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = NUMBER.matcher(raw.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String correlative = matcher.group(2).replaceFirst("^0+", "");
        return correlative.isEmpty() ? Optional.empty() : Optional.of(matcher.group(1) + "-" + correlative);
    }

    @Override
    public String documentNumberPattern() {
        return NUMBER.pattern();
    }

    @Override
    public String documentNumberPlaceholder() {
        return "F001-123";
    }

    @Override
    public String documentNumberHint() {
        return "Serie (F + 3 caracteres, E001 o 4 dígitos), guion y correlativo de 1 a 8 dígitos";
    }

    @Override
    public TaxIdRule taxIdRule() {
        return TaxIdRule.PE_RUC;
    }

    @Override
    public String taxIdLabel() {
        return "RUC";
    }

    @Override
    public Integer taxIdLength() {
        return 11;
    }

    @Override
    public String taxIdHint() {
        return "11 dígitos que empiezan por 10, 15, 17 o 20, con dígito verificador";
    }

    @Override
    public Set<String> carrierTaxIdTypes() {
        return Set.of("RUC");
    }

    @Override
    public List<WithholdingKind> withholdingKinds() {
        return List.of(WithholdingKind.PE_DETRACTION, WithholdingKind.PE_RETENTION);
    }
}
