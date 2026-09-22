package com.ebim.tms.iam.provisioning.application;

import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The provisioning response, in the shape MasterAdmin's GENERIC reader validates.
 *
 * <ul>
 *   <li>{@code status} is {@code ACTIVE}: the organization, company and administrator membership
 *       exist and are committed when this is returned. It says nothing about the administrator's
 *       login, which {@code resources.adminStatus} reports.</li>
 *   <li>{@code externalTenantId} is the TMS <b>organization</b> id, because the organization is the
 *       tenant boundary in TMS (ADR-003). It is repeated as {@code externalOrganizationId} so a
 *       reader of either field gets the same answer.</li>
 *   <li>{@code resources} holds flat, non-sensitive scalars only.</li>
 *   <li>{@code replayed} is present on create responses only.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisionedTenantView(
        String status,
        String controlPlaneTenantId,
        String externalTenantId,
        String externalOrganizationId,
        String externalCompanyId,
        Map<String, Object> resources,
        String rawReference,
        String provisionedAt,
        Boolean replayed,
        String correlationId) {

    /** The administrator exists in TMS but cannot sign in until an operator links the Auth account. */
    public static final String ADMIN_PREPROVISIONED = "PREPROVISIONED";
    /** The administrator's profile is linked to a Supabase Auth identity. */
    public static final String ADMIN_ACTIVE = "ACTIVE";

    public static ProvisionedTenantView of(ProvisioningRecord record, Boolean replayed, String correlationId) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("organizationCode", record.organizationCode());
        resources.put("companyCode", record.companyCode());
        resources.put("companyTimeZone", record.companyTimeZone());
        resources.put("adminStatus", record.adminAuthLinked() ? ADMIN_ACTIVE : ADMIN_PREPROVISIONED);
        resources.put("adminProfileReused", record.adminProfileReused());
        resources.put("organizationActive", record.organizationActive());
        resources.put("companyActive", record.companyActive());
        return new ProvisionedTenantView(
                "ACTIVE",
                record.controlPlaneTenantId().toString(),
                record.organizationId().toString(),
                record.organizationId().toString(),
                record.companyId().toString(),
                resources,
                record.provisioningId().toString(),
                record.createdAt() != null ? record.createdAt().toString() : null,
                replayed,
                correlationId);
    }
}
