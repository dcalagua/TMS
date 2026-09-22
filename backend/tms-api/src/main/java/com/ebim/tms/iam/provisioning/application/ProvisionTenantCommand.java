package com.ebim.tms.iam.provisioning.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The GENERIC request after validation and normalization: what TMS understood MasterAdmin to ask.
 *
 * <p>This, and only this, is what {@link #requestHash()} fingerprints. Deliberately absent from the
 * hash: the Idempotency-Key, the correlation id, MasterAdmin's {@code requestId}, the token, and
 * every value TMS <em>derives</em> (the default company code, the time zone, the administrator's
 * display name). A retry therefore hashes identically, and a future change to a derivation rule
 * cannot turn a legitimate replay into a 409.
 *
 * @param companyProvided whether MasterAdmin sent a {@code company} object at all
 * @param companyTaxId    {@code company.taxId}, or {@code organization.taxId} when no company was sent
 */
public record ProvisionTenantCommand(
        UUID controlPlaneTenantId,
        String productCode,
        String contractVersion,
        String tenantCode,
        String tenantName,
        String tenantType,
        String environment,
        String deploymentMode,
        String adminEmail,
        String organizationName,
        String organizationLegalName,
        String organizationCountryCode,
        String organizationTaxId,
        boolean companyProvided,
        String companyCode,
        String companyName,
        String companyCountryCode,
        String companyCurrency,
        String companyTaxId,
        String planCode,
        String planName) {

    /** Bumped only together with a documented migration path for already-provisioned tenants. */
    static final String HASH_VERSION = "tms-generic-v1";

    /**
     * Lower-case hex SHA-256 over a fixed-order, length-prefixed encoding of the fields above.
     *
     * <p>Hand-built rather than Jackson-serialized so that no mapper setting (property order,
     * null inclusion) can ever change it: the value is persisted and compared across deployments.
     */
    public String requestHash() {
        List<Object> fields = Arrays.asList(HASH_VERSION, controlPlaneTenantId, productCode, contractVersion,
                tenantCode, tenantName, tenantType, environment, deploymentMode, adminEmail,
                organizationName, organizationLegalName, organizationCountryCode, organizationTaxId,
                companyProvided, companyCode, companyName, companyCountryCode, companyCurrency, companyTaxId,
                planCode, planName);
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            if (field == null) {
                canonical.append("-1:");
            } else {
                String text = field.toString();
                canonical.append(text.length()).append(':').append(text);
            }
            canonical.append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** The TMS company code: MasterAdmin's company code, else the organization (tenant) code. */
    public String effectiveCompanyCode() {
        return companyCode != null ? companyCode : tenantCode;
    }

    /** The TMS company name: MasterAdmin's company name, else the organization name. */
    public String effectiveCompanyName() {
        return companyName != null ? companyName : organizationName;
    }

    /** The country the company operates in, if MasterAdmin stated one. */
    public String effectiveCountryCode() {
        return companyCountryCode != null ? companyCountryCode : organizationCountryCode;
    }

    /** The IANA zone the company's operating day is read in. See {@link CountryTimeZones}. */
    public String effectiveTimeZone() {
        return CountryTimeZones.forCountry(effectiveCountryCode());
    }

    /** {@code tms.app_user.full_name} is mandatory and GENERIC carries no name: the mailbox is used. */
    public String adminDisplayName() {
        return adminEmail.substring(0, adminEmail.indexOf('@'));
    }
}
