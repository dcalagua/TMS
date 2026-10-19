package com.ebim.tms.settlement.domain.tax;

import com.ebim.tms.shared.tax.TaxIdRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The fiscal vocabulary of one country, as far as a received carrier document needs it (ADR-027): what
 * its VAT is called, which currency its rules apply to, how it codes and numbers a document, how its
 * taxpayer id is checked and which withholdings it has. <b>Pure, and no number lives here</b>: rates,
 * codes and thresholds are configuration ({@link TaxRules}), resolved per company.
 *
 * <p>TMS <b>receives</b> carrier documents and checks their shape; it emits, signs or sends nothing to
 * a tax authority. Electronic validation against SUNAT or the SRI is not implemented
 * ({@code FiscalDocumentValidationPort} answers {@code EXTERNAL_DEPENDENCY}).
 */
public interface TaxRegime {

    TaxRegimeCode code();

    /** IGV, IVA... */
    String vatLabel();

    /**
     * The currency the regime's rules apply to: a breakdown is required and withholdings are computed
     * only on documents in it, because thresholds are in it and TMS converts no currency. Empty for
     * the generic regime, which enforces none.
     */
    Optional<String> fiscalCurrency();

    /** The country's code for a document kind; empty where the regime has none. */
    Optional<String> documentCode(DocumentKind kind);

    /** The kind a country code (or a kind's own name) stands for. */
    default Optional<DocumentKind> kindOf(String codeOrName) {
        if (codeOrName == null || codeOrName.isBlank()) {
            return Optional.empty();
        }
        String wanted = codeOrName.trim();
        for (DocumentKind kind : DocumentKind.values()) {
            if (kind.name().equalsIgnoreCase(wanted) || documentCode(kind).filter(wanted::equals).isPresent()) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * The fiscal number as stored: normalised so two spellings of one document are one under
     * {@code uq_carrier_invoice_fiscal_document}. Empty when the number is not in the regime's format.
     */
    Optional<String> normalizeDocumentNumber(String raw);

    /** A regular expression (Java and JavaScript alike) the number must match; null = free text. */
    String documentNumberPattern();

    String documentNumberPlaceholder();

    /** One sentence describing the format, for a form and for a refusal. */
    String documentNumberHint();

    TaxIdRule taxIdRule();

    /** RUC, NIT... */
    String taxIdLabel();

    /** The exact length of a valid tax id, or null when it varies. */
    Integer taxIdLength();

    String taxIdHint();

    /**
     * The carrier master's {@code tax_id_type} values that name this regime's taxpayer id: a document's
     * issuer is compared with the carrier's own id only when the carrier's is one of these. Empty = no
     * comparison (the generic regime does not know which identifier type is the fiscal one).
     */
    Set<String> carrierTaxIdTypes();

    /** The withholdings this regime has, in the order they are computed. */
    List<WithholdingKind> withholdingKinds();

    /** Whether a document in {@code currency} must state its subtotal, rate and VAT. */
    default boolean breakdownRequired(String currency) {
        return fiscalCurrency().map(fiscal -> fiscal.equals(currency)).orElse(false);
    }
}
