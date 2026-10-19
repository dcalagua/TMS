package com.ebim.tms.iam.infrastructure;

import com.ebim.tms.shared.settings.PaymentTermsSettingsPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code iam}'s implementation of {@link PaymentTermsSettingsPort} (V67): a primary-key read of one
 * nullable column of {@code tms.company_settings}. A company without a settings row has no default.
 */
@Repository
public class JdbcPaymentTermsSettingsAdapter implements PaymentTermsSettingsPort {

    private static final String SQL = """
            SELECT default_payment_terms_days
            FROM tms.company_settings
            WHERE company_id = :companyId
            """;

    private final JdbcClient jdbcClient;

    public JdbcPaymentTermsSettingsAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Integer> defaultPaymentTermsDaysOf(UUID companyId) {
        return jdbcClient.sql(SQL)
                .param("companyId", companyId)
                .query((rs, rowNum) -> (Integer) rs.getObject("default_payment_terms_days"))
                .list().stream()
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
