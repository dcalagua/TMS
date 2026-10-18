package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.settings.FiscalSettings;
import com.ebim.tms.shared.settings.FiscalSettingsPort;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam}'s implementation of {@link FiscalSettingsPort} (V66): a primary-key read of the fiscal
 * columns of {@code tms.company_settings}, apart from {@link JdbcCompanySettingsAdapter} so the
 * eleven callers of that one do not grow seven values they never read.
 */
@Repository
public class JdbcFiscalSettingsAdapter implements FiscalSettingsPort {

    private static final String SQL = """
            SELECT igv_rate, detraction_enabled, detraction_code, detraction_rate,
                   detraction_threshold_amount, retention_agent, retention_rate
            FROM tms.company_settings
            WHERE company_id = :companyId
            """;

    private final JdbcClient jdbcClient;

    public JdbcFiscalSettingsAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public FiscalSettings fiscalSettingsOf(UUID companyId) {
        return jdbcClient.sql(SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new FiscalSettings(
                        rs.getBigDecimal("igv_rate"),
                        (Boolean) rs.getObject("detraction_enabled"),
                        rs.getString("detraction_code"),
                        rs.getBigDecimal("detraction_rate"),
                        rs.getBigDecimal("detraction_threshold_amount"),
                        (Boolean) rs.getObject("retention_agent"),
                        rs.getBigDecimal("retention_rate")))
                .optional()
                .orElseGet(FiscalSettings::unset);
    }
}
