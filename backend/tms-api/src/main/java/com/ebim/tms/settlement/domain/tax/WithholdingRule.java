package com.ebim.tms.settlement.domain.tax;

import java.math.BigDecimal;

/**
 * One withholding of a company's regime, resolved: the company's own value where it stored one, the
 * deployment's property otherwise (V66, ADR-027, D-06).
 *
 * @param code            the authority's code (SPOT 027, SRI 310); may be null
 * @param rate            percentage of the kind's base, never null
 * @param thresholdAmount in the fiscal currency; the document's total must exceed it. Null = none
 */
public record WithholdingRule(WithholdingKind kind, boolean enabled, String code, BigDecimal rate,
        BigDecimal thresholdAmount) {
}
