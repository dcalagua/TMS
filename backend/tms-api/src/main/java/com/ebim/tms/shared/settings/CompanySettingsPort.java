package com.ebim.tms.shared.settings;

import java.util.UUID;

/**
 * Reads one company's operational defaults, without a business module depending on
 * {@code com.ebim.tms.iam} - the settings sibling of {@link com.ebim.tms.shared.audit.AuditRecorder}.
 *
 * <p>{@code com.ebim.tms.iam.infrastructure.JdbcCompanySettingsAdapter} is the only implementation.
 * {@code iam} owns {@code tms.company_settings} (migration V34) and is the only module that writes
 * it; {@code orders}, {@code planning} and {@code masterdata} read it through here.
 *
 * <p>Read-only by design. A module that could write another module's settings row would be able to
 * change what a tenant's documents are called as a side effect of creating one, and the audit trail
 * would record the wrong aggregate.
 */
public interface CompanySettingsPort {

    /**
     * The settings of {@code companyId}, or {@link CompanySettings#defaults()} when the row is
     * missing.
     *
     * <p>Never empty and never throwing, deliberately. Every caller is in the middle of a business
     * write - creating an order, creating a shipment, previewing an import - and none of them has a
     * sensible way to fail because a configuration row was not there. The defaults are exactly the
     * literals those call sites used before V34, so a missing row costs a prefix, not a request.
     *
     * <p>The read is a primary-key lookup on a table with one row per company and is issued once
     * per business write, not once per row written: {@code OrderImportService} resolves it once for
     * a whole file.
     */
    CompanySettings settingsOf(UUID companyId);
}
