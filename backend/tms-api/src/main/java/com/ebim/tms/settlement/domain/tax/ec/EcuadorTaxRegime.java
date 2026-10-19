package com.ebim.tms.settlement.domain.tax.ec;

import com.ebim.tms.settlement.domain.tax.DocumentKind;
import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRegimeCode;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.shared.tax.TaxIdRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ecuador (ADR-027): IVA, US dollars, SRI document codes {@code 01} factura, {@code 04} nota de
 * crédito, {@code 05} nota de débito, the number {@code establecimiento-puntoEmisión-secuencial}
 * ({@code 001-001-000000123}, 3+3+9 digits), the 13-digit RUC and two withholdings that may apply to
 * the same document: the income-tax withholding on the subtotal and, for a VAT-withholding agent, the
 * VAT withholding on the VAT.
 *
 * <p>Domestic land freight is VAT 0% (LRTI art. 56); the general rate is 15%. Those are configuration
 * ({@code tms.finance.tax.ec.*}), like every rate - this class names the vocabulary only.
 */
public final class EcuadorTaxRegime implements TaxRegime {

    public static final EcuadorTaxRegime INSTANCE = new EcuadorTaxRegime();

    private static final Pattern NUMBER = Pattern.compile("^([0-9]{3})-([0-9]{3})-([0-9]{1,9})$");

    private EcuadorTaxRegime() {
    }

    @Override
    public TaxRegimeCode code() {
        return TaxRegimeCode.EC;
    }

    @Override
    public String vatLabel() {
        return "IVA";
    }

    @Override
    public Optional<String> fiscalCurrency() {
        return Optional.of("USD");
    }

    @Override
    public Optional<String> documentCode(DocumentKind kind) {
        return Optional.of(switch (kind) {
            case INVOICE -> "01";
            case CREDIT_NOTE -> "04";
            case DEBIT_NOTE -> "05";
        });
    }

    /** {@code 1-1-123} is not accepted: establishment and point are printed with three digits. */
    @Override
    public Optional<String> normalizeDocumentNumber(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = NUMBER.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String establishment = matcher.group(1);
        String point = matcher.group(2);
        long sequential = Long.parseLong(matcher.group(3));
        if ("000".equals(establishment) || "000".equals(point) || sequential == 0) {
            return Optional.empty();
        }
        return Optional.of(establishment + "-" + point + "-" + String.format("%09d", sequential));
    }

    @Override
    public String documentNumberPattern() {
        return NUMBER.pattern();
    }

    @Override
    public String documentNumberPlaceholder() {
        return "001-001-000000123";
    }

    @Override
    public String documentNumberHint() {
        return "Establecimiento (3 dígitos), punto de emisión (3 dígitos) y secuencial (hasta 9 dígitos)";
    }

    @Override
    public TaxIdRule taxIdRule() {
        return TaxIdRule.EC_RUC;
    }

    @Override
    public String taxIdLabel() {
        return "RUC";
    }

    @Override
    public Integer taxIdLength() {
        return 13;
    }

    @Override
    public String taxIdHint() {
        return "13 dígitos: provincia, tipo de contribuyente, dígito verificador y establecimiento (001)";
    }

    @Override
    public Set<String> carrierTaxIdTypes() {
        return Set.of("RUC");
    }

    @Override
    public List<WithholdingKind> withholdingKinds() {
        return List.of(WithholdingKind.EC_INCOME_TAX_WITHHOLDING, WithholdingKind.EC_VAT_WITHHOLDING);
    }
}
