package com.ebim.tms.iam.provisioning.application;

import static com.ebim.tms.iam.provisioning.application.GenericProvisioningFixtures.standard;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The transaction boundary, proven through Spring's real {@code @Transactional} proxy.
 *
 * <p>The transaction manager is a mock, so what is asserted is what Spring asked of it: a failure
 * anywhere in the provisioning - after the organization and the company were already written - ends
 * in {@code rollback} and never in {@code commit}. With the real manager that is PostgreSQL
 * discarding every row of the attempt. A database-backed proof of the same needs Docker
 * (Testcontainers) and is not part of this class.
 */
@SpringJUnitConfig(PlatformTenantProvisionerTransactionTest.Context.class)
class PlatformTenantProvisionerTransactionTest {

    @Configuration
    @EnableTransactionManagement
    static class Context {

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            return manager;
        }

        @Bean
        PlatformProvisioningRepository repository() {
            return mock(PlatformProvisioningRepository.class);
        }

        @Bean
        PlatformTenantProvisioner provisioner(PlatformProvisioningRepository repository) {
            return new PlatformTenantProvisioner(repository);
        }
    }

    private static final ProvisioningMetadata METADATA = new ProvisioningMetadata(
            "ma-prov-v1-tx-0123456789", "corr", "masteradmin-provisioning", "jti", null, null);

    @Autowired
    private PlatformTenantProvisioner provisioner;

    @Autowired
    private PlatformProvisioningRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ProvisionTenantCommand command = GenericRequestAdapter.adapt(standard(), "v1");

    @BeforeEach
    void setUp() {
        reset(repository, transactionManager);
        TransactionStatus status = new SimpleTransactionStatus(true);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repository.findByControlPlaneTenantId(any())).thenReturn(Optional.empty());
        when(repository.findProfileByEmail(anyString())).thenReturn(Optional.empty());
        when(repository.insertOrganization(anyString(), anyString())).thenReturn(UUID.randomUUID());
        when(repository.insertCompany(any(), anyString(), anyString(), any(), anyString())).thenReturn(UUID.randomUUID());
        when(repository.insertPreprovisionedProfile(anyString(), anyString())).thenReturn(UUID.randomUUID());
        when(repository.insertOrganizationWideMembership(any(), any())).thenReturn(UUID.randomUUID());
    }

    @Test
    @DisplayName("a database failure after the organization and company were written rolls everything back")
    void failureRollsBack() {
        when(repository.grantRole(any(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> provisioner.provision(command, METADATA))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(repository).insertOrganization(anyString(), anyString());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("a contract conflict also rolls back: nothing of the attempt is kept")
    void conflictRollsBack() {
        when(repository.organizationCodeTaken(anyString())).thenReturn(true);

        assertThatThrownBy(() -> provisioner.provision(command, METADATA))
                .isInstanceOf(PlatformProvisioningException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("a successful provisioning commits exactly once")
    void successCommits() {
        when(repository.grantRole(any(), anyString())).thenReturn(1);
        when(repository.findByControlPlaneTenantId(any())).thenReturn(Optional.empty(), Optional.of(
                new ProvisioningRecord(UUID.randomUUID(), command.controlPlaneTenantId(), command.requestHash(),
                        UUID.randomUUID(), "ALPHA-TMS", true, UUID.randomUUID(), "ALPHA-01", "America/Lima", true,
                        false, false, OffsetDateTime.now())));

        provisioner.provision(command, METADATA);

        verify(transactionManager, times(1)).commit(any());
        verify(transactionManager, never()).rollback(any());
    }
}
