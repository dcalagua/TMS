package com.ebim.tms.iam.provisioning.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One completed provisioning, joined with the live state of what it created.
 *
 * @param adminAuthLinked whether the administrator's profile is linked to a Supabase Auth identity
 *     today ({@code app_user.auth_user_id IS NOT NULL}). Provisioning never links it; an operator
 *     does, when the person's account exists. Read live, so a GET after that reports it.
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
        OffsetDateTime createdAt) {}
