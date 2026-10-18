package com.ebim.tms.shared.settings;

import java.math.BigDecimal;

/**
 * What one company stored of its fiscal settings (V66, ADR-024 part 2, item 1). <b>Every field may
 * be null</b>, and a null is "use the deployment's default" (decision D-06), resolved by
 * {@code settlement.application.FiscalSettingsResolver}; nothing here is a default itself.
 */
public record FiscalSettings(
        BigDecimal igvRate,
        Boolean detractionEnabled,
        String detractionCode,
        BigDecimal detractionRate,
        BigDecimal detractionThresholdAmount,
        Boolean retentionAgent,
        BigDecimal retentionRate) {

    private static final FiscalSettings UNSET = new FiscalSettings(null, null, null, null, null, null, null);

    /** A company that stored nothing, or has no settings row. */
    public static FiscalSettings unset() {
        return UNSET;
    }
}
