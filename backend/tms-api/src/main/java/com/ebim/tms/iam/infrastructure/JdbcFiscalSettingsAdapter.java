package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.FiscalSettings;
import com.ebim.tms.shared.settings.FiscalSettingsPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam}'s implementation of {@link FiscalSettingsPort} (V66, ADR-027): the fiscal columns of
 * {@code tms.company_settings} and the per-kind rows of {@code tms.company_tax_withholding_setting},
 * apart from {@link JdbcCompanySettingsAdapter} so the callers of that one do not grow values they
 * never read. Writes exactly those, nothing else of the settings row.
 */
@Repository
public class JdbcFiscalSettingsAdapter implements FiscalSettingsPort {

    private static final String SETTINGS_SQL = """
            SELECT default_country, fiscal_country, vat_rate, freight_vat_rate
            FROM tms.company_settings
            WHERE company_id = :companyId
            """;

    private static final String WITHHOLDINGS_SQL = """
            SELECT kind, enabled, code, rate, threshold_amount
            FROM tms.company_tax_withholding_setting
            WHERE company_id = :companyId
            ORDER BY kind
            """;

    /** Same upsert shape as {@code CompanyAdministrationRepository.saveSettings}, fiscal columns only. */
    private static final String UPSERT_SETTINGS_SQL = """
            INSERT INTO tms.company_settings (company_id, fiscal_country, vat_rate, freight_vat_rate,
                                              created_by, updated_by)
            VALUES (:companyId, :fiscalCountry, :vatRate, :freightVatRate, :actorId, :actorId)
            ON CONFLICT (company_id) DO UPDATE
               SET fiscal_country = EXCLUDED.fiscal_country,
                   vat_rate = EXCLUDED.vat_rate,
                   freight_vat_rate = EXCLUDED.freight_vat_rate,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = now()
            """;

    private static final String UPSERT_WITHHOLDING_SQL = """
            INSERT INTO tms.company_tax_withholding_setting (company_id, kind, enabled, code, rate,
                                                             threshold_amount, created_by, updated_by)
            VALUES (:companyId, :kind, :enabled, :code, :rate, :thresholdAmount, :actorId, :actorId)
            ON CONFLICT (company_id, kind) DO UPDATE
               SET enabled = EXCLUDED.enabled,
                   code = EXCLUDED.code,
                   rate = EXCLUDED.rate,
                   threshold_amount = EXCLUDED.threshold_amount,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = now(),
                   version = tms.company_tax_withholding_setting.version + 1
            """;

    private static final String DELETE_OTHER_WITHHOLDINGS_SQL = """
            DELETE FROM tms.company_tax_withholding_setting
            WHERE company_id = :companyId AND kind NOT IN (:kinds)
            """;

    private static final String DELETE_ALL_WITHHOLDINGS_SQL = """
            DELETE FROM tms.company_tax_withholding_setting WHERE company_id = :companyId
            """;

    private final JdbcClient jdbcClient;

    public JdbcFiscalSettingsAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public FiscalSettings fiscalSettingsOf(UUID companyId) {
        List<FiscalSettings.WithholdingSetting> withholdings = jdbcClient.sql(WITHHOLDINGS_SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new FiscalSettings.WithholdingSetting(
                        rs.getString("kind"),
                        (Boolean) rs.getObject("enabled"),
                        rs.getString("code"),
                        rs.getBigDecimal("rate"),
                        rs.getBigDecimal("threshold_amount")))
                .list();
        return jdbcClient.sql(SETTINGS_SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> new FiscalSettings(
                        rs.getString("default_country"),
                        rs.getString("fiscal_country"),
                        rs.getBigDecimal("vat_rate"),
                        rs.getBigDecimal("freight_vat_rate"),
                        withholdings))
                .optional()
                .orElseGet(() -> new FiscalSettings(CompanySettings.DEFAULT_COUNTRY, null, null, null,
                        withholdings));
    }

    @Override
    public void replaceFiscalSettings(UUID companyId, FiscalSettings settings, UUID actorId) {
        jdbcClient.sql(UPSERT_SETTINGS_SQL)
                .param("companyId", companyId)
                .param("fiscalCountry", settings.fiscalCountry())
                .param("vatRate", settings.vatRate())
                .param("freightVatRate", settings.freightVatRate())
                .param("actorId", actorId)
                .update();
        List<String> kinds = settings.withholdings().stream()
                .map(FiscalSettings.WithholdingSetting::kind)
                .toList();
        if (kinds.isEmpty()) {
            jdbcClient.sql(DELETE_ALL_WITHHOLDINGS_SQL).param("companyId", companyId).update();
        } else {
            jdbcClient.sql(DELETE_OTHER_WITHHOLDINGS_SQL)
                    .param("companyId", companyId)
                    .param("kinds", kinds)
                    .update();
        }
        for (FiscalSettings.WithholdingSetting withholding : settings.withholdings()) {
            jdbcClient.sql(UPSERT_WITHHOLDING_SQL)
                    .param("companyId", companyId)
                    .param("kind", withholding.kind())
                    .param("enabled", withholding.enabled())
                    .param("code", withholding.code())
                    .param("rate", withholding.rate())
                    .param("thresholdAmount", withholding.thresholdAmount())
                    .param("actorId", actorId)
                    .update();
        }
    }
}
