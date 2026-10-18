package com.ebim.tms.settlement.domain.tax;

/** What a withholding's rate applies to (V66, ADR-027). */
public enum WithholdingBase {
    /** The document's total, VAT included (Peru's detraction and retention). */
    TOTAL,
    /** The document's subtotal, net of VAT (Ecuador's income-tax withholding). */
    SUBTOTAL,
    /** The document's VAT amount (Ecuador's VAT withholding). */
    VAT
}
