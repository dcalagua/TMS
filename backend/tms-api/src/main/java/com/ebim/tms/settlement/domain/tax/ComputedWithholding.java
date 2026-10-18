package com.ebim.tms.settlement.domain.tax;

import java.math.BigDecimal;

/** One withholding the rules call for on one document (V66, ADR-027). Amounts scale 2. */
public record ComputedWithholding(WithholdingKind kind, String code, WithholdingBase base, BigDecimal baseAmount,
        BigDecimal rate, BigDecimal amount) {
}
