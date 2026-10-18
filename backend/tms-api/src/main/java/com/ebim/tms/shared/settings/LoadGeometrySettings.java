package com.ebim.tms.shared.settings;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two configurable numbers load geometry needs (ADR-022 sections 4-5, V62), resolved in one
 * place: the company's own value, else the deployment's property. Neither is a literal in code or
 * SQL; the properties live in {@code application.yml} under {@code tms.planning.geometry}.
 *
 * <ul>
 *   <li><b>Reference height</b> - the usable height {@code H} an order's lines are stacked against
 *       when they are written, before any vehicle is known.</li>
 *   <li><b>Floor fill factor</b> - the share of {@code length x width} a load can really cover. A
 *       vehicle type's own factor wins over both; that precedence is fleet's
 *       ({@code EffectiveCapacityResolver}), which asks {@link #floorFillFactorOf} for the fallback.</li>
 * </ul>
 */
@Component
public class LoadGeometrySettings {

    private final CompanySettingsPort companySettingsPort;
    private final BigDecimal defaultReferenceHeightM;
    private final BigDecimal defaultFloorFillFactor;

    public LoadGeometrySettings(CompanySettingsPort companySettingsPort,
            @Value("${tms.planning.geometry.default-reference-height-m:2.40}") BigDecimal defaultReferenceHeightM,
            @Value("${tms.planning.geometry.default-floor-fill-factor:0.85}") BigDecimal defaultFloorFillFactor) {
        this.companySettingsPort = companySettingsPort;
        this.defaultReferenceHeightM = defaultReferenceHeightM;
        this.defaultFloorFillFactor = defaultFloorFillFactor;
    }

    /** The usable height order lines of {@code companyId} are stacked against. Never null. */
    public BigDecimal referenceHeightOf(UUID companyId) {
        return referenceHeight(companySettingsPort.settingsOf(companyId));
    }

    /** The same, for a caller that already holds the settings (a whole import file reads them once). */
    public BigDecimal referenceHeight(CompanySettings settings) {
        BigDecimal own = settings == null ? null : settings.referenceLoadHeightM();
        return own != null ? own : defaultReferenceHeightM;
    }

    /** The company's floor fill factor, else the deployment's. Never null. */
    public BigDecimal floorFillFactorOf(UUID companyId) {
        BigDecimal own = companySettingsPort.settingsOf(companyId).loadFloorFillFactor();
        return own != null ? own : defaultFloorFillFactor;
    }
}
