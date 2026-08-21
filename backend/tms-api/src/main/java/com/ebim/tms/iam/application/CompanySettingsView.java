package com.ebim.tms.iam.application;

import com.ebim.tms.shared.settings.CompanySettings;

/**
 * The operational defaults of one company, as its settings screen reads them (migration V34).
 *
 * <p>A view of its own rather than three more fields on {@link CompanyProfileView}, because the
 * two halves are governed differently: the profile is what the company <em>is</em> (its name, its
 * tax identifier, the zone its days are measured in) and this is what the product <em>does</em>
 * with new records. They are edited together and are worth reading apart.
 */
public record CompanySettingsView(
        String defaultCountry,
        String orderNumberPrefix,
        String shipmentNumberPrefix) {

    public static CompanySettingsView from(CompanySettings settings) {
        return new CompanySettingsView(
                settings.defaultCountry(), settings.orderNumberPrefix(), settings.shipmentNumberPrefix());
    }
}
