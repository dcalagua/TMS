package com.ebim.tms.shared.settings;

import java.util.UUID;

/**
 * Reads and writes one company's fiscal settings (V66, ADR-027) without {@code settlement} depending
 * on {@code iam}, which owns {@code tms.company_settings} - the fiscal sibling of
 * {@link CompanySettingsPort}.
 *
 * <p><b>The one write a settings port allows</b>, and deliberately narrow (ADR-027 section 4):
 * {@link CompanySettingsPort} is read-only so that no module can change a tenant's settings as a side
 * effect of a business write. The fiscal regime is the opposite case - an explicit edit with its own
 * permission ({@code settlement.tax:manage}) whose validation (which withholdings a country has) is
 * {@code settlement}'s - so {@code settlement} validates and audits, and {@code iam}'s adapter writes
 * exactly the fiscal columns and the per-kind table, nothing else.
 */
public interface FiscalSettingsPort {

    /**
     * What the company stored, or {@link FiscalSettings#unset(String)} with the product's default
     * country when it has no row. Never throws.
     */
    FiscalSettings fiscalSettingsOf(UUID companyId);

    /**
     * Replaces the company's fiscal overrides: the three columns, and the per-kind rows (a kind not in
     * {@code settings.withholdings()} loses its row). Creates the settings row when it is missing.
     * {@code settings.defaultCountry()} is ignored: it is the company profile's.
     */
    void replaceFiscalSettings(UUID companyId, FiscalSettings settings, UUID actorId);
}
