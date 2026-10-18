package com.ebim.tms.settlement.domain;

/**
 * What a GL account mapping books (V65, ADR-023; country-neutral since ADR-027). The accrual journal
 * uses {@link #FREIGHT_EXPENSE}, {@link #FREIGHT_ACCRUAL} and {@link #CARRIER_PAYABLE}; the payable
 * export adds the fiscal breakdown: the VAT credit (IGV, IVA...) and the withholdings payable to the
 * tax authority, one mapping per withholding kind or one for every kind.
 */
public enum GlConcept {
    FREIGHT_EXPENSE,
    FREIGHT_ACCRUAL,
    VAT_CREDIT,
    /** Qualified by {@code GlAccountMapping.withholdingKind}: a specific kind first, then a mapping for every kind. */
    WITHHOLDING_PAYABLE,
    CARRIER_PAYABLE
}
