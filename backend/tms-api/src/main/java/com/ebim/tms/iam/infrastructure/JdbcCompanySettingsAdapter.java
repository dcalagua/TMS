package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam}'s implementation of {@link CompanySettingsPort} (migration V34).
 *
 * <p>Separate from {@link CompanyAdministrationRepository}, which owns the writes, for the same
 * reason {@link JdbcCompanyScopeLoader} is separate from {@link IdentityRepository}: this is the
 * read three other modules depend on through a port, and keeping it in its own class means the
 * dependency is visible in an import statement rather than buried among the administration
 * statements it has nothing to do with.
 *
 * <p>No caching. The statement is a primary-key lookup on a table with one row per company, issued
 * once per business write - an order creation, a shipment creation, an import preview - and a cache
 * would buy a microsecond in exchange for the one failure mode a settings screen cannot afford:
 * saving a new prefix and watching the next order still use the old one.
 */
@Repository
public class JdbcCompanySettingsAdapter implements CompanySettingsPort {

    private static final String SETTINGS_SQL = """
            SELECT default_country, order_number_prefix, shipment_number_prefix
            FROM tms.company_settings
            WHERE company_id = :companyId
            """;

    private final JdbcClient jdbcClient;

    public JdbcCompanySettingsAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * A missing row resolves to {@link CompanySettings#defaults()} rather than to an empty result.
     * V34 section 4 explains why the row can legitimately be absent: a company created after that
     * migration gets its row the first time the settings screen is saved, because the insert cannot
     * happen inside the creating company's tenant scope.
     */
    @Override
    public CompanySettings settingsOf(UUID companyId) {
        return jdbcClient.sql(SETTINGS_SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new CompanySettings(
                        rs.getString("default_country"),
                        rs.getString("order_number_prefix"),
                        rs.getString("shipment_number_prefix")))
                .optional()
                .orElseGet(CompanySettings::defaults);
    }
}
