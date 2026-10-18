package com.ebim.tms.settlement.domain.tax;

/** The regimes this product implements (ADR-027). Any country without one is {@link #GENERIC}. */
public enum TaxRegimeCode {
    PE,
    EC,
    GENERIC
}
