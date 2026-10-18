package com.ebim.tms.shared.settings;

import java.util.UUID;

/**
 * Reads one company's fiscal settings (V66) without {@code settlement} depending on {@code iam},
 * which owns {@code tms.company_settings} - the fiscal sibling of {@link CompanySettingsPort}.
 */
public interface FiscalSettingsPort {

    /** What the company stored, or {@link FiscalSettings#unset()} when it has no row. Never throws. */
    FiscalSettings fiscalSettingsOf(UUID companyId);
}
