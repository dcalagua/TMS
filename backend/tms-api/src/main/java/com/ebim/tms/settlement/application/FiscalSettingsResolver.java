package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.FiscalRules;
import com.ebim.tms.shared.settings.FiscalSettings;
import com.ebim.tms.shared.settings.FiscalSettingsPort;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A company's fiscal rules: its own value where it stored one, else the deployment's
 * {@code tms.finance.pe.*} (V66, ADR-024 part 2, item 1, D-06). No rate is a literal in Java or SQL;
 * the property defaults live in {@code application.yml}, the same shape as
 * {@code LoadGeometrySettings}.
 */
@Component
public class FiscalSettingsResolver {

    private final FiscalSettingsPort fiscalSettingsPort;
    private final FiscalRules defaults;

    public FiscalSettingsResolver(FiscalSettingsPort fiscalSettingsPort,
            @Value("${tms.finance.pe.igv-rate}") BigDecimal igvRate,
            @Value("${tms.finance.pe.detraction-enabled}") boolean detractionEnabled,
            @Value("${tms.finance.pe.detraction-code}") String detractionCode,
            @Value("${tms.finance.pe.detraction-rate}") BigDecimal detractionRate,
            @Value("${tms.finance.pe.detraction-threshold-pen}") BigDecimal detractionThreshold,
            @Value("${tms.finance.pe.retention-agent}") boolean retentionAgent,
            @Value("${tms.finance.pe.retention-rate}") BigDecimal retentionRate) {
        this.fiscalSettingsPort = fiscalSettingsPort;
        this.defaults = new FiscalRules(igvRate, detractionEnabled, detractionCode, detractionRate,
                detractionThreshold, retentionAgent, retentionRate);
    }

    public FiscalRules rulesOf(UUID companyId) {
        return resolve(fiscalSettingsPort.fiscalSettingsOf(companyId), defaults);
    }

    /** The deployment's defaults, as a company that stored nothing gets them. */
    public FiscalRules defaults() {
        return defaults;
    }

    static FiscalRules resolve(FiscalSettings own, FiscalRules defaults) {
        FiscalSettings stored = own == null ? FiscalSettings.unset() : own;
        return new FiscalRules(
                or(stored.igvRate(), defaults.igvRate()),
                or(stored.detractionEnabled(), defaults.detractionEnabled()),
                or(stored.detractionCode(), defaults.detractionCode()),
                or(stored.detractionRate(), defaults.detractionRate()),
                or(stored.detractionThresholdAmount(), defaults.detractionThresholdAmount()),
                or(stored.retentionAgent(), defaults.retentionAgent()),
                or(stored.retentionRate(), defaults.retentionRate()));
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
