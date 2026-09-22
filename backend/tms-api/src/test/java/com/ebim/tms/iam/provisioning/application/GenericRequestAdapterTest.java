package com.ebim.tms.iam.provisioning.application;

import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.TENANT_A;
import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.request;
import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.standard;
import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.withoutCompany;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.iam.provisioning.application.PlatformProvisioningException.FieldIssue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GenericRequestAdapterTest {

    @Test
    @DisplayName("maps the GENERIC body onto TMS: tenantCode -> organization code, company -> company")
    void mapsTheStandardBody() {
        ProvisionTenantCommand command = GenericRequestAdapter.adapt(standard(), "v1");

        assertThat(command.controlPlaneTenantId()).isEqualTo(UUID.fromString(TENANT_A));
        assertThat(command.tenantCode()).as("upper-cased to fit ck_organization_code_shape").isEqualTo("ALPHA-TMS");
        assertThat(command.organizationName()).isEqualTo("Empresa Directa Alpha");
        assertThat(command.adminEmail()).as("normalized like ck_app_user_email_normalized")
                .isEqualTo("admin@alpha.ebim.test");
        assertThat(command.effectiveCompanyCode()).isEqualTo("ALPHA-01");
        assertThat(command.effectiveCompanyName()).isEqualTo("Alpha Logistica");
        assertThat(command.companyTaxId()).isEqualTo("20500000004");
        assertThat(command.effectiveCountryCode()).isEqualTo("PE");
        assertThat(command.effectiveTimeZone()).isEqualTo("America/Lima");
        assertThat(command.adminDisplayName()).isEqualTo("admin");
    }

    @Test
    @DisplayName("company null: a default company named after the organization, coded after the tenant")
    void defaultCompany() {
        ProvisionTenantCommand command = GenericRequestAdapter.adapt(withoutCompany(standard()), null);

        assertThat(command.companyProvided()).isFalse();
        assertThat(command.effectiveCompanyCode()).isEqualTo("ALPHA-TMS");
        assertThat(command.effectiveCompanyName()).isEqualTo("Empresa Directa Alpha");
        assertThat(command.companyTaxId()).as("falls back to organization.taxId").isEqualTo("20500000004");
        assertThat(command.effectiveCountryCode()).isEqualTo("PE");
    }

    @Test
    @DisplayName("a country with several zones, or none, starts in UTC rather than a guess")
    void timeZoneFallback() {
        assertThat(CountryTimeZones.forCountry("MX")).isEqualTo("UTC");
        assertThat(CountryTimeZones.forCountry(null)).isEqualTo("UTC");
        assertThat(CountryTimeZones.forCountry("CO")).isEqualTo("America/Bogota");
    }

    @Test
    @DisplayName("the hash ignores MasterAdmin's volatile fields, so a retry hashes identically")
    void hashIgnoresVolatileFields() {
        String first = GenericRequestAdapter.adapt(standard(), "v1").requestHash();
        String retry = GenericRequestAdapter.adapt(standard(), null).requestHash(); // new requestId/correlationId

        assertThat(first).matches("^[0-9a-f]{64}$").isEqualTo(retry);
    }

    @Test
    @DisplayName("the hash changes when the functional content changes")
    void hashFollowsContent() {
        String original = GenericRequestAdapter.adapt(standard(), "v1").requestHash();
        String otherAdmin = GenericRequestAdapter.adapt(request(TENANT_A, "alpha-tms", "other@alpha.ebim.test"), "v1")
                .requestHash();
        String noCompany = GenericRequestAdapter.adapt(withoutCompany(standard()), "v1").requestHash();

        assertThat(otherAdmin).isNotEqualTo(original);
        assertThat(noCompany).isNotEqualTo(original);
    }

    @Test
    @DisplayName("every invalid field is reported, named as MasterAdmin sent it")
    void reportsEveryInvalidField() {
        GenericProvisioningRequest bad = new GenericProvisioningRequest(
                "this tenant code has spaces", null, "not-an-email", null, null, null,
                new GenericProvisioningRequest.Organization(null, null, null, "PER", null),
                new GenericProvisioningRequest.Company("bad code!", null, "P", "SOLES", null),
                null,
                new GenericProvisioningRequest.MasterAdmin("not-a-uuid", "ewm", null, null, "v2"));

        assertThatThrownBy(() -> GenericRequestAdapter.adapt(bad, "v9"))
                .isInstanceOfSatisfying(PlatformProvisioningException.class, e -> {
                    assertThat(e.code()).isEqualTo(ProvisioningErrorCode.INVALID_REQUEST);
                    assertThat(e.details()).extracting(FieldIssue::field).containsExactlyInAnyOrder(
                            "x-masteradmin-contract", "masterAdmin.tenantId", "masterAdmin.productCode",
                            "masterAdmin.contractVersion", "tenantCode", "adminEmail",
                            "organization.displayName", "organization.countryCode", "company.code",
                            "company.countryCode", "company.currency");
                });
    }

    @Test
    @DisplayName("masterAdmin and organization blocks are mandatory")
    void requiredBlocks() {
        GenericProvisioningRequest bad = new GenericProvisioningRequest(
                "alpha-tms", null, "a@b.co", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> GenericRequestAdapter.adapt(bad, null))
                .isInstanceOfSatisfying(PlatformProvisioningException.class, e ->
                        assertThat(e.details()).extracting(FieldIssue::field)
                                .containsExactlyInAnyOrder("masterAdmin", "organization"));
    }

    @Test
    @DisplayName("a tenant code longer than TMS allows is refused rather than truncated")
    void tenantCodeTooLong() {
        GenericProvisioningRequest longCode = request(TENANT_A, "a-very-long-tenant-code-that-does-not-fit-tms", "a@b.co");

        assertThatThrownBy(() -> GenericRequestAdapter.adapt(longCode, null))
                .isInstanceOfSatisfying(PlatformProvisioningException.class, e ->
                        assertThat(e.details()).extracting(FieldIssue::field).containsExactly("tenantCode"));
    }
}
