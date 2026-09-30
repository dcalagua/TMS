package com.ebim.tms.iam.provisioning.application;

import com.ebim.tms.iam.provisioning.application.PlatformProvisioningException.FieldIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and normalizes a GENERIC body into a {@link ProvisionTenantCommand}.
 *
 * <p>Pure and static: every rule of the field table in
 * {@code docs/platform-provisioning/MASTERADMIN_GENERIC_CONTRACT.md} is here and is unit-tested
 * without Spring. Every problem is collected, not just the first, and each one is named by the
 * GENERIC path MasterAdmin sent - an operator reading the 400 must not have to know TMS's columns.
 */
public final class GenericRequestAdapter {

    public static final String PRODUCT_CODE = "tms";
    public static final String CONTRACT_VERSION = "v1";

    /** {@code ck_organization_code_shape} / {@code ck_company_code_shape} (V2). */
    static final Pattern TMS_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");
    /** {@code ck_app_user_email_shape} (V2). */
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+[.][^@\\s]+$");
    static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    static final int MAX_NAME = 200;
    static final int MAX_EMAIL = 254;
    static final int MAX_TAX_ID = 50;
    static final int MAX_LABEL = 60;

    private GenericRequestAdapter() {}

    /**
     * @param contractHeader the {@code X-MasterAdmin-Contract} header, which may be absent
     * @throws PlatformProvisioningException {@code INVALID_REQUEST} listing every field at fault
     */
    public static ProvisionTenantCommand adapt(GenericProvisioningRequest request, String contractHeader) {
        List<FieldIssue> issues = new ArrayList<>();
        if (request == null) {
            throw invalid(List.of(new FieldIssue("$", "a JSON object is required")));
        }

        String header = clean(contractHeader);
        if (header != null && !CONTRACT_VERSION.equals(header)) {
            issues.add(new FieldIssue("x-masteradmin-contract", "must be \"" + CONTRACT_VERSION + "\""));
        }

        // masterAdmin ---------------------------------------------------------------------------
        GenericProvisioningRequest.MasterAdmin masterAdmin = request.masterAdmin();
        UUID controlPlaneTenantId = null;
        String contractVersion = CONTRACT_VERSION;
        if (masterAdmin == null) {
            issues.add(new FieldIssue("masterAdmin", "required object"));
        } else {
            controlPlaneTenantId = uuid(masterAdmin.tenantId());
            if (controlPlaneTenantId == null) {
                issues.add(new FieldIssue("masterAdmin.tenantId", "must be a UUID"));
            }
            if (!PRODUCT_CODE.equals(clean(masterAdmin.productCode()))) {
                issues.add(new FieldIssue("masterAdmin.productCode", "must be \"" + PRODUCT_CODE + "\""));
            }
            String version = clean(masterAdmin.contractVersion());
            if (version != null && !CONTRACT_VERSION.equals(version)) {
                issues.add(new FieldIssue("masterAdmin.contractVersion", "must be \"" + CONTRACT_VERSION + "\""));
            }
        }

        // tenant --------------------------------------------------------------------------------
        String tenantCode = upper(request.tenantCode());
        if (tenantCode == null) {
            issues.add(new FieldIssue("tenantCode", "required"));
        } else if (!TMS_CODE.matcher(tenantCode).matches()) {
            issues.add(new FieldIssue("tenantCode",
                    "must be 2-32 characters of A-Z, 0-9, '_' or '-' (upper-cased by TMS), starting with a letter or digit"));
        }
        String tenantName = bounded(request.tenantName(), "tenantName", MAX_NAME, issues);
        String tenantType = bounded(request.tenantType(), "tenantType", MAX_LABEL, issues);
        String environment = bounded(request.environment(), "environment", MAX_LABEL, issues);
        String deploymentMode = bounded(request.deploymentMode(), "deploymentMode", MAX_LABEL, issues);

        String adminEmail = lower(request.adminEmail());
        if (adminEmail == null) {
            issues.add(new FieldIssue("adminEmail", "required"));
        } else if (adminEmail.length() > MAX_EMAIL || !EMAIL.matcher(adminEmail).matches()) {
            issues.add(new FieldIssue("adminEmail", "must be an email address"));
        }

        // organization --------------------------------------------------------------------------
        GenericProvisioningRequest.Organization organization = request.organization();
        String organizationName = null;
        String organizationLegalName = null;
        String organizationCountry = null;
        String organizationTaxId = null;
        if (organization == null) {
            issues.add(new FieldIssue("organization", "required object"));
        } else {
            organizationLegalName = bounded(organization.legalName(), "organization.legalName", MAX_NAME, issues);
            String displayName = bounded(organization.displayName(), "organization.displayName", MAX_NAME, issues);
            organizationName = firstPresent(displayName, organizationLegalName, tenantName);
            if (organizationName == null) {
                issues.add(new FieldIssue("organization.displayName",
                        "required (or organization.legalName, or tenantName)"));
            }
            organizationCountry = country(organization.countryCode(), "organization.countryCode", issues);
            organizationTaxId = bounded(organization.taxId(), "organization.taxId", MAX_TAX_ID, issues);
        }

        // company (nullable) --------------------------------------------------------------------
        GenericProvisioningRequest.Company company = request.company();
        boolean companyProvided = company != null;
        String companyCode = null;
        String companyName = null;
        String companyCountry = null;
        String companyCurrency = null;
        String companyTaxId = organizationTaxId;
        if (companyProvided) {
            companyCode = upper(company.code());
            if (companyCode != null && !TMS_CODE.matcher(companyCode).matches()) {
                issues.add(new FieldIssue("company.code",
                        "must be 2-32 characters of A-Z, 0-9, '_' or '-' (upper-cased by TMS), starting with a letter or digit"));
            }
            companyName = bounded(company.name(), "company.name", MAX_NAME, issues);
            companyCountry = country(company.countryCode(), "company.countryCode", issues);
            companyCurrency = upper(company.currency());
            if (companyCurrency != null && !CURRENCY.matcher(companyCurrency).matches()) {
                issues.add(new FieldIssue("company.currency", "must be an ISO 4217 code"));
            }
            companyTaxId = bounded(company.taxId(), "company.taxId", MAX_TAX_ID, issues);
        }

        // plan (informational) ------------------------------------------------------------------
        String planCode = null;
        String planName = null;
        if (request.plan() != null) {
            planCode = bounded(request.plan().code(), "plan.code", MAX_LABEL, issues);
            planName = bounded(request.plan().name(), "plan.name", MAX_NAME, issues);
        }

        if (!issues.isEmpty()) {
            throw invalid(issues);
        }
        return new ProvisionTenantCommand(controlPlaneTenantId, PRODUCT_CODE, contractVersion, tenantCode,
                tenantName, tenantType, environment, deploymentMode, adminEmail, organizationName,
                organizationLegalName, organizationCountry, organizationTaxId, companyProvided, companyCode,
                companyName, companyCountry, companyCurrency, companyTaxId, planCode, planName);
    }

    /** A UUID in canonical lower-case form, or {@code null} when the value is not one. */
    public static UUID uuid(String value) {
        String text = clean(value);
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String country(String value, String field, List<FieldIssue> issues) {
        String code = upper(value);
        if (code != null && !COUNTRY.matcher(code).matches()) {
            issues.add(new FieldIssue(field, "must be an ISO 3166-1 alpha-2 code"));
            return null;
        }
        return code;
    }

    private static String bounded(String value, String field, int max, List<FieldIssue> issues) {
        String text = clean(value);
        if (text != null && text.length() > max) {
            issues.add(new FieldIssue(field, "must be at most " + max + " characters"));
            return null;
        }
        return text;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upper(String value) {
        String text = clean(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        String text = clean(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static PlatformProvisioningException invalid(List<FieldIssue> issues) {
        return new PlatformProvisioningException(ProvisioningErrorCode.INVALID_REQUEST, issues);
    }
}
