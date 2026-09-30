package com.ebim.tms.iam.provisioning.application;

/**
 * The body MasterAdmin sends to every product under the GENERIC contract v1, exactly as it arrives.
 *
 * <p>Everything is a nullable {@code String} or a nested record: presence, shape and normalization
 * are decided by {@link GenericRequestAdapter}, which can then name the offending field in the
 * GENERIC vocabulary. Unknown properties are ignored (Spring Boot's default), so MasterAdmin may
 * add fields without breaking TMS.
 */
public record GenericProvisioningRequest(
        String tenantCode,
        String tenantName,
        String adminEmail,
        String tenantType,
        String environment,
        String deploymentMode,
        Organization organization,
        Company company,
        Plan plan,
        MasterAdmin masterAdmin) {

    public record Organization(String code, String legalName, String displayName, String countryCode, String taxId) {}

    public record Company(String code, String name, String countryCode, String currency, String taxId) {}

    public record Plan(String code, String name) {}

    public record MasterAdmin(
            String tenantId, String productCode, String requestId, String correlationId, String contractVersion) {}
}
