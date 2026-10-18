package com.ebim.tms.settlement.domain.tax;

/**
 * Which kind of fiscal document a carrier sent (V66, ADR-027): stored by name in
 * {@code carrier_invoice.document_kind}. The country's own code for it (Peru 01/07/08, Ecuador SRI
 * 01/04/05) is the regime's ({@link TaxRegime#documentCode}) and is stored beside it.
 *
 * <p><b>Amounts are positive on every kind</b>, as every tax authority issues them;
 * {@link #effectiveSign()} is where a credit note becomes a subtraction.
 */
public enum DocumentKind {

    /** Factura. */
    INVOICE,

    /** Nota de crédito: reduces the invoice it references. */
    CREDIT_NOTE,

    /** Nota de débito: adds to the invoice it references. */
    DEBIT_NOTE;

    /** +1 for an invoice and a debit note, -1 for a credit note. */
    public int effectiveSign() {
        return this == CREDIT_NOTE ? -1 : 1;
    }

    /** A note references the invoice it corrects; an invoice references nothing. */
    public boolean isNote() {
        return this != INVOICE;
    }

    /** What a person reads; the same words in every regime this product has. */
    public String label() {
        return switch (this) {
            case INVOICE -> "Factura";
            case CREDIT_NOTE -> "Nota de crédito";
            case DEBIT_NOTE -> "Nota de débito";
        };
    }
}
