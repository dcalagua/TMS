package com.ebim.tms.settlement.domain;

/**
 * What a GL account mapping books (V65, ADR-023). The last three serve the fiscal breakdown of
 * phase 60 (ADR-024); the accrual journal uses the first two and {@link #CARRIER_PAYABLE}.
 */
public enum GlConcept {
    FREIGHT_EXPENSE,
    FREIGHT_ACCRUAL,
    IGV_CREDIT,
    DETRACCION_PAYABLE,
    RETENCION_PAYABLE,
    CARRIER_PAYABLE
}
