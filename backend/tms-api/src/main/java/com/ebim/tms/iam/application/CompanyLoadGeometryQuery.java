package com.ebim.tms.iam.application;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.settings.LoadGeometrySettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the load-geometry settings in force for one company. A thin read beside
 * {@link CompanyAdministrationService}: it resolves through {@link LoadGeometrySettings}, the one
 * place the company value and the deployment default are reconciled, so the screen cannot state a
 * number the planner does not use.
 */
@Service
public class CompanyLoadGeometryQuery {

    static final String COMPANY = "COMPANY";
    static final String DEPLOYMENT = "DEPLOYMENT";

    private final CompanySettingsPort companySettingsPort;
    private final LoadGeometrySettings loadGeometrySettings;

    public CompanyLoadGeometryQuery(CompanySettingsPort companySettingsPort, LoadGeometrySettings loadGeometrySettings) {
        this.companySettingsPort = companySettingsPort;
        this.loadGeometrySettings = loadGeometrySettings;
    }

    @Transactional(readOnly = true)
    public CompanyLoadGeometryView current(CompanyScope scope) {
        CompanySettings own = companySettingsPort.settingsOf(scope.companyId());
        return new CompanyLoadGeometryView(
                loadGeometrySettings.referenceHeight(own),
                own.referenceLoadHeightM() != null ? COMPANY : DEPLOYMENT,
                loadGeometrySettings.floorFillFactorOf(scope.companyId()),
                own.loadFloorFillFactor() != null ? COMPANY : DEPLOYMENT);
    }
}
