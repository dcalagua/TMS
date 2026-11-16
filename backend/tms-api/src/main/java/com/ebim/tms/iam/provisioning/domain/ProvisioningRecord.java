package com.ebim.tms.iam.provisioning.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One completed provisioning, joined with the live state of what it created.
 *
 * @param adminAuthLinked whether the administrator's profile is linked to a Supabase Auth identity
 *     today ({@code app_user.auth_user_id IS NOT NULL}). Provisioning never links it; an operator
 *     does, when the person's account exists. Read live, so a GET after that reports it.
 * @param linked whether the row adopted an organization that already existed
 *     ({@code origin = 'LINKED'}, V76, {@code tenant-inventory.v1}) instead of creating it. A linked row
 *     names no administrator, and its company is null when the organization had none.
 */
public record ProvisioningRecord(
        UUID provisioningId,
        UUID controlPlaneTenantId,
        String requestHash,
        UUID organizationId,
        String organizationCode,
        boolean organizationActive,
        UUID companyId,
        String companyCode,
        String companyTimeZone,
        boolean companyActive,
        boolean adminProfileReused,
        boolean adminAuthLinked,
        OffsetDateTime createdAt,
        boolean linked) {

    /** {@code platform_provisioning_request.origin} of a row written by the tenant link (V76). */
    public static final String ORIGIN_LINKED = "LINKED";

    /** A row created by {@code POST /tenants}: {@code origin = 'PROVISIONED'}. */
    public ProvisioningRecord(UUID provisioningId, UUID controlPlaneTenantId, String requestHash, UUID organizationId,
            String organizationCode, boolean organizationActive, UUID companyId, String companyCode,
            String companyTimeZone, boolean companyActive, boolean adminProfileReused, boolean adminAuthLinked,
            OffsetDateTime createdAt) {
        this(provisioningId, controlPlaneTenantId, requestHash, organizationId, organizationCode, organizationActive,
                companyId, companyCode, companyTimeZone, companyActive, adminProfileReused, adminAuthLinked, createdAt,
                false);
    }
}
