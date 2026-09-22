package com.ebim.tms.iam.provisioning.application;

import java.util.UUID;

/**
 * GENERIC bodies as MasterAdmin builds them (see its {@code provisioning_execution_context}).
 */
public final class GenericProvisioningFixtures {

    public static final String TENANT_A = "50000000-0000-4000-a000-00000000000a";
    public static final String TENANT_B = "50000000-0000-4000-a000-00000000000b";

    private GenericProvisioningFixtures() {}

    public static GenericProvisioningRequest request(String tenantId, String tenantCode, String adminEmail) {
        return new GenericProvisioningRequest(
                tenantCode,
                "Empresa Directa Alpha - TMS",
                adminEmail,
                "PRODUCTION",
                "QAS",
                "SHARED",
                new GenericProvisioningRequest.Organization("empresa-directa-alpha", "Empresa Directa Alpha S.A.C.",
                        "Empresa Directa Alpha", "PE", "20500000004"),
                new GenericProvisioningRequest.Company("ALPHA-01", "Alpha Logistica", "PE", "PEN", "20500000004"),
                new GenericProvisioningRequest.Plan("tms-standard", "TMS Standard"),
                new GenericProvisioningRequest.MasterAdmin(tenantId, "tms", UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "v1"));
    }

    public static GenericProvisioningRequest standard() {
        return request(TENANT_A, "alpha-tms", "Admin@Alpha.Ebim.Test");
    }

    public static GenericProvisioningRequest withoutCompany(GenericProvisioningRequest request) {
        return new GenericProvisioningRequest(request.tenantCode(), request.tenantName(), request.adminEmail(),
                request.tenantType(), request.environment(), request.deploymentMode(), request.organization(), null,
                request.plan(), request.masterAdmin());
    }

    public static String json(String tenantId, String tenantCode, String adminEmail, String companyName) {
        return """
                {
                  "tenantCode": "%s",
                  "tenantName": "Empresa Directa Alpha - TMS",
                  "adminEmail": "%s",
                  "tenantType": "PRODUCTION",
                  "environment": "QAS",
                  "deploymentMode": "SHARED",
                  "organization": {
                    "code": "empresa-directa-alpha",
                    "legalName": "Empresa Directa Alpha S.A.C.",
                    "displayName": "Empresa Directa Alpha",
                    "countryCode": "PE",
                    "taxId": "20500000004"
                  },
                  "company": { "code": "ALPHA-01", "name": "%s", "countryCode": "PE", "currency": "PEN", "taxId": "20500000004" },
                  "plan": { "code": "tms-standard", "name": "TMS Standard" },
                  "masterAdmin": {
                    "tenantId": "%s",
                    "productCode": "tms",
                    "requestId": "%s",
                    "correlationId": "%s",
                    "contractVersion": "v1"
                  },
                  "someFutureField": { "ignored": true }
                }
                """.formatted(tenantCode, adminEmail, companyName, tenantId, UUID.randomUUID(), UUID.randomUUID());
    }
}
