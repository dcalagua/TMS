package com.ebim.tms.iam.provisioning.application;

import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.standard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository.ExistingProfile;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository.NewProvisioning;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The provisioning decisions against a mocked repository: which rows are written, in which order,
 * and - as importantly - which are not.
 */
class PlatformTenantProvisionerTest {

    private static final ProvisioningMetadata METADATA = new ProvisioningMetadata(
            "ma-prov-v1-0123456789abcdef", "corr-1", "masteradmin-provisioning", "jti-1",
            "10000000-0000-4000-a000-000000000002", "TECH_LEAD");

    private final ProvisionTenantCommand command = GenericRequestAdapter.adapt(standard(), "v1");
    private final UUID organizationId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();

    private PlatformProvisioningRepository repository;
    private PlatformTenantProvisioner provisioner;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformProvisioningRepository.class);
        provisioner = new PlatformTenantProvisioner(repository);
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.findByControlPlaneTenantId(any())).thenReturn(Optional.empty());
        when(repository.findProfileByEmail(anyString())).thenReturn(Optional.empty());
        when(repository.insertOrganization(anyString(), anyString())).thenReturn(organizationId);
        when(repository.insertCompany(any(), anyString(), anyString(), any(), anyString())).thenReturn(companyId);
        when(repository.insertPreprovisionedProfile(anyString(), anyString())).thenReturn(adminId);
        when(repository.insertOrganizationWideMembership(adminId, organizationId)).thenReturn(membershipId);
        when(repository.grantRole(membershipId, "ORGANIZATION_ADMIN")).thenReturn(1);
    }

    private ProvisioningRecord record(String hash) {
        return new ProvisioningRecord(UUID.randomUUID(), command.controlPlaneTenantId(), hash, organizationId,
                "ALPHA-TMS", true, companyId, "ALPHA-01", "America/Lima", true, false, false, OffsetDateTime.now());
    }

    @Test
    @DisplayName("first call: organization, company, settings, preprovisioned admin, org-wide admin role, mapping, audit")
    void firstCreate() {
        ProvisioningRecord created = record(command.requestHash());
        when(repository.findByControlPlaneTenantId(command.controlPlaneTenantId()))
                .thenReturn(Optional.empty(), Optional.of(created));

        PlatformTenantProvisioner.Outcome outcome = provisioner.provision(command, METADATA);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.record()).isEqualTo(created);

        InOrder order = inOrder(repository);
        order.verify(repository).lock("key:" + METADATA.idempotencyKey());
        order.verify(repository).lock("tenant:" + command.controlPlaneTenantId());
        order.verify(repository).findByIdempotencyKey(METADATA.idempotencyKey());
        order.verify(repository).findByControlPlaneTenantId(command.controlPlaneTenantId());
        order.verify(repository).organizationCodeTaken("ALPHA-TMS");
        order.verify(repository).findProfileByEmail("admin@alpha.ebim.test");
        order.verify(repository).insertOrganization("ALPHA-TMS", "Empresa Directa Alpha");
        order.verify(repository).insertCompany(organizationId, "ALPHA-01", "Alpha Logistica", "20500000004",
                "America/Lima");
        order.verify(repository).insertCompanySettings(companyId, "PE");
        order.verify(repository).insertPreprovisionedProfile("admin@alpha.ebim.test", "admin");
        order.verify(repository).insertOrganizationWideMembership(adminId, organizationId);
        order.verify(repository).grantRole(membershipId, "ORGANIZATION_ADMIN");

        ArgumentCaptor<NewProvisioning> row = ArgumentCaptor.forClass(NewProvisioning.class);
        order.verify(repository).insertProvisioning(row.capture(), eq(METADATA));
        assertThat(row.getValue().controlPlaneTenantId()).as("tenant mapping")
                .isEqualTo(command.controlPlaneTenantId());
        assertThat(row.getValue().organizationId()).isEqualTo(organizationId);
        assertThat(row.getValue().companyId()).isEqualTo(companyId);
        assertThat(row.getValue().requestHash()).isEqualTo(command.requestHash());
        assertThat(row.getValue().adminProfileReused()).isFalse();

        order.verify(repository).findByControlPlaneTenantId(command.controlPlaneTenantId());
        ArgumentCaptor<ProvisioningAuditEntry> audit = ArgumentCaptor.forClass(ProvisioningAuditEntry.class);
        order.verify(repository).recordAudit(audit.capture());
        assertThat(audit.getValue().result()).isEqualTo(ProvisioningAuditEntry.Result.CREATED);
        assertThat(audit.getValue().actorRole()).isEqualTo("TECH_LEAD");

        // Nothing else: in particular no statement that could link or activate a login.
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("same key, same content: replayed, nothing written but the REPLAYED audit entry")
    void replay() {
        ProvisioningRecord existing = record(command.requestHash());
        when(repository.findByIdempotencyKey(METADATA.idempotencyKey())).thenReturn(Optional.of(existing));

        PlatformTenantProvisioner.Outcome outcome = provisioner.provision(command, METADATA);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.record()).isEqualTo(existing);
        verifyNothingCreated();
        verify(repository).recordAudit(any());
    }

    @Test
    @DisplayName("same key, different content: IDEMPOTENCY_CONFLICT, nothing written")
    void sameKeyDifferentBody() {
        when(repository.findByIdempotencyKey(METADATA.idempotencyKey()))
                .thenReturn(Optional.of(record("f".repeat(64))));

        assertConflict(ProvisioningErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("another key for an already provisioned tenant, same content: TENANT_ALREADY_PROVISIONED")
    void duplicateTenantSameContent() {
        when(repository.findByControlPlaneTenantId(command.controlPlaneTenantId()))
                .thenReturn(Optional.of(record(command.requestHash())));

        assertConflict(ProvisioningErrorCode.TENANT_ALREADY_PROVISIONED);
    }

    @Test
    @DisplayName("another key for an already provisioned tenant, other content: TENANT_CONFLICT")
    void duplicateTenantOtherContent() {
        when(repository.findByControlPlaneTenantId(command.controlPlaneTenantId()))
                .thenReturn(Optional.of(record("e".repeat(64))));

        assertConflict(ProvisioningErrorCode.TENANT_CONFLICT);
    }

    @Test
    @DisplayName("an organization code TMS already has, not created by provisioning, is never adopted")
    void organizationCodeTaken() {
        when(repository.organizationCodeTaken("ALPHA-TMS")).thenReturn(true);

        assertConflict(ProvisioningErrorCode.TENANT_CONFLICT);
    }

    @Test
    @DisplayName("a deactivated profile with the admin email is not silently reactivated")
    void deactivatedAdmin() {
        when(repository.findProfileByEmail("admin@alpha.ebim.test"))
                .thenReturn(Optional.of(new ExistingProfile(UUID.randomUUID(), false)));

        assertConflict(ProvisioningErrorCode.ADMIN_EMAIL_CONFLICT);
    }

    @Test
    @DisplayName("an active profile with the admin email is reused, as the invite screen does")
    void reusesActiveProfile() {
        UUID existing = UUID.randomUUID();
        when(repository.findProfileByEmail("admin@alpha.ebim.test"))
                .thenReturn(Optional.of(new ExistingProfile(existing, true)));
        when(repository.insertOrganizationWideMembership(existing, organizationId)).thenReturn(membershipId);
        when(repository.findByControlPlaneTenantId(command.controlPlaneTenantId()))
                .thenReturn(Optional.empty(), Optional.of(record(command.requestHash())));

        provisioner.provision(command, METADATA);

        verify(repository, never()).insertPreprovisionedProfile(anyString(), anyString());
        ArgumentCaptor<NewProvisioning> row = ArgumentCaptor.forClass(NewProvisioning.class);
        verify(repository).insertProvisioning(row.capture(), any());
        assertThat(row.getValue().adminAppUserId()).isEqualTo(existing);
        assertThat(row.getValue().adminProfileReused()).isTrue();
    }

    @Test
    @DisplayName("a missing ORGANIZATION_ADMIN role fails the call instead of leaving an admin with no role")
    void missingRoleFails() {
        when(repository.grantRole(membershipId, "ORGANIZATION_ADMIN")).thenReturn(0);

        assertThatThrownBy(() -> provisioner.provision(command, METADATA))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insertProvisioning(any(), any());
        verify(repository, never()).recordAudit(any());
    }

    private void assertConflict(ProvisioningErrorCode expected) {
        assertThatThrownBy(() -> provisioner.provision(command, METADATA))
                .isInstanceOfSatisfying(PlatformProvisioningException.class,
                        e -> assertThat(e.code()).isEqualTo(expected));
        verifyNothingCreated();
        verify(repository, never()).recordAudit(any());
    }

    private void verifyNothingCreated() {
        verify(repository, never()).insertOrganization(anyString(), anyString());
        verify(repository, never()).insertCompany(any(), anyString(), anyString(), any(), anyString());
        verify(repository, never()).insertCompanySettings(any(), anyString());
        verify(repository, never()).insertPreprovisionedProfile(anyString(), anyString());
        verify(repository, never()).insertOrganizationWideMembership(any(), any());
        verify(repository, never()).grantRole(any(), anyString());
        verify(repository, never()).insertProvisioning(any(), any());
    }
}
