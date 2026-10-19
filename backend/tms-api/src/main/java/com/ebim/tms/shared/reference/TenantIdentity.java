package com.ebim.tms.shared.reference;

/**
 * The product-specific facts about a provisioned tenant that a control plane cannot derive: the
 * codes TMS generated for the organization and the company, and the time zone the company ended up
 * with.
 *
 * <p>Read back rather than remembered, so the answer stays true if an administrator later renames a
 * code inside TMS. Nothing here is business data.
 */
public record TenantIdentity(String organizationCode, String companyCode, String timeZone) {
}
