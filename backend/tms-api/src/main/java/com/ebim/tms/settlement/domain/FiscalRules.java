package com.ebim.tms.settlement.domain;

import java.math.BigDecimal;

/**
 * One company's fiscal settings, resolved: its own value where it set one, the deployment's
 * {@code tms.finance.pe.*} property otherwise (V66, ADR-024 part 2, item 1, decision D-06). Never
 * null in any field - {@code FiscalSettingsResolver} is what guarantees it - and never a literal:
 * the percentages are the ones an accountant will want to check first.
 *
 * @param igvRate                   percentage, e.g. 18.00
 * @param detractionRate            percentage, e.g. 4.00
 * @param detractionThresholdAmount in PEN; detraction applies strictly above it
 * @param retentionRate             percentage, e.g. 3.00
 */
public record FiscalRules(
        BigDecimal igvRate,
        boolean detractionEnabled,
        String detractionCode,
        BigDecimal detractionRate,
        BigDecimal detractionThresholdAmount,
        boolean retentionAgent,
        BigDecimal retentionRate) {
}
