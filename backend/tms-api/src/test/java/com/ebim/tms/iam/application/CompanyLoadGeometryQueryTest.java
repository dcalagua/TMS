package com.ebim.tms.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import com.ebim.tms.shared.settings.DispatchConfirmationMode;
import com.ebim.tms.shared.settings.LoadGeometrySettings;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The settings screen states the geometry numbers the planner really uses: the company's own when it
 * has one, the deployment's otherwise, and says which.
 */
class CompanyLoadGeometryQueryTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private final CompanySettingsPort port = mock(CompanySettingsPort.class);
    private final LoadGeometrySettings settings =
            new LoadGeometrySettings(port, new BigDecimal("2.40"), new BigDecimal("0.85"));
    private final CompanyLoadGeometryQuery query = new CompanyLoadGeometryQuery(port, settings);

    @Test
    void without_company_values_the_deployment_defaults_are_reported_as_such() {
        when(port.settingsOf(COMPANY)).thenReturn(CompanySettings.defaults());

        CompanyLoadGeometryView view = query.current(SCOPE);

        assertThat(view.referenceHeightM()).isEqualByComparingTo("2.40");
        assertThat(view.referenceHeightSource()).isEqualTo("DEPLOYMENT");
        assertThat(view.floorFillFactor()).isEqualByComparingTo("0.85");
        assertThat(view.floorFillFactorSource()).isEqualTo("DEPLOYMENT");
    }

    @Test
    void company_values_win_and_are_reported_as_the_company_s() {
        when(port.settingsOf(COMPANY)).thenReturn(new CompanySettings("PE", "TO-", "SH-",
                DispatchConfirmationMode.MANUAL, new BigDecimal("2.20"), new BigDecimal("0.90")));

        CompanyLoadGeometryView view = query.current(SCOPE);

        assertThat(view.referenceHeightM()).isEqualByComparingTo("2.20");
        assertThat(view.referenceHeightSource()).isEqualTo("COMPANY");
        assertThat(view.floorFillFactor()).isEqualByComparingTo("0.90");
        assertThat(view.floorFillFactorSource()).isEqualTo("COMPANY");
    }
}
