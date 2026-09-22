package com.ebim.tms.iam.provisioning.application;

import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.standard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Mapping failures onto contract codes, and auditing calls that created nothing.
 */
class PlatformProvisioningServiceTest {

    private static final PlatformCaller CALLER =
            new PlatformCaller("masteradmin-provisioning", "jti-9", "actor-1", "TECH_LEAD");
    private static final String KEY = "ma-prov-v1-service-test-key";

    private PlatformTenantProvisioner provisioner;
    private PlatformProvisioningRepository repository;
    private PlatformProvisioningService service;

    @BeforeEach
    void setUp() {
        provisioner = mock(PlatformTenantProvisioner.class);
        repository = mock(PlatformProvisioningRepository.class);
        service = new PlatformProvisioningService(provisioner, repository, mock(PlatformTransactionManager.class));
    }

    @Test
    @DisplayName("an unexpected database failure is PROVISIONING_FAILED (500, retryable) and audited as ERROR")
    void databaseFailure() {
        when(provisioner.provision(any(), any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertCode(ProvisioningErrorCode.PROVISIONING_FAILED);
        assertThat(audited().result()).isEqualTo(Result.ERROR);
    }

    @Test
    @DisplayName("a unique key lost to a concurrent call is TENANT_CONFLICT, audited as CONFLICT")
    void raceLost() {
        when(provisioner.provision(any(), any())).thenThrow(new DataIntegrityViolationException("uq"));

        assertCode(ProvisioningErrorCode.TENANT_CONFLICT);
        assertThat(audited().result()).isEqualTo(Result.CONFLICT);
    }

    @Test
    @DisplayName("an invalid Idempotency-Key is refused before the body is even read, and not stored")
    void invalidKey() {
        assertThatThrownBy(() -> service.create(CALLER, "short", "v1", standard(), "corr"))
                .isInstanceOfSatisfying(PlatformProvisioningException.class,
                        e -> assertThat(e.code()).isEqualTo(ProvisioningErrorCode.INVALID_IDEMPOTENCY_KEY));
        verify(provisioner, never()).provision(any(), any());
        assertThat(audited().idempotencyKey()).isNull();
    }

    @Test
    @DisplayName("an audit failure does not change the answer")
    void auditFailureIsNotTheCallersProblem() {
        when(provisioner.provision(any(), any())).thenThrow(new DataIntegrityViolationException("uq"));
        doThrow(new DataAccessResourceFailureException("audit down")).when(repository).recordAudit(any());

        assertCode(ProvisioningErrorCode.TENANT_CONFLICT);
    }

    private void assertCode(ProvisioningErrorCode expected) {
        assertThatThrownBy(() -> service.create(CALLER, KEY, "v1", standard(), "corr"))
                .isInstanceOfSatisfying(PlatformProvisioningException.class,
                        e -> assertThat(e.code()).isEqualTo(expected));
    }

    private ProvisioningAuditEntry audited() {
        ArgumentCaptor<ProvisioningAuditEntry> entry = ArgumentCaptor.forClass(ProvisioningAuditEntry.class);
        verify(repository).recordAudit(entry.capture());
        assertThat(entry.getValue().actorRole()).isEqualTo("TECH_LEAD");
        return entry.getValue();
    }
}
