package com.ebim.tms.iam.provisioning.application;

import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Operation;
import com.ebim.tms.iam.provisioning.domain.ProvisioningAuditEntry.Result;
import com.ebim.tms.iam.provisioning.domain.ProvisioningMetadata;
import com.ebim.tms.iam.provisioning.domain.ProvisioningRecord;
import com.ebim.tms.iam.provisioning.infrastructure.PlatformProvisioningRepository;
import com.ebim.tms.iam.provisioning.security.PlatformCaller;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The provisioning use cases behind {@code /internal/platform-provisioning}: create a tenant, and
 * report on one.
 *
 * <p>Owns the parts of a call that are not the tenant itself: the Idempotency-Key check, the
 * translation of the GENERIC body, the mapping of every failure onto a stable contract code, and the
 * audit trail of calls that did <em>not</em> create anything. Those audit rows are written in a
 * transaction of their own ({@code REQUIRES_NEW}) because the provisioning transaction they describe
 * has just been rolled back.
 *
 * <p>Logs carry codes, ids and the correlation id - never the token, the Authorization header or the
 * request body, which holds a person's email and a company's tax id.
 */
@Service
public class PlatformProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(PlatformProvisioningService.class);

    static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,200}$");

    private final PlatformTenantProvisioner provisioner;
    private final PlatformProvisioningRepository repository;
    private final TransactionTemplate ownTransaction;

    public PlatformProvisioningService(PlatformTenantProvisioner provisioner,
            PlatformProvisioningRepository repository, PlatformTransactionManager transactionManager) {
        this.provisioner = provisioner;
        this.repository = repository;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code POST /tenants}. Returns the created or replayed tenant; throws a contract error otherwise. */
    public ProvisionedTenantView create(PlatformCaller caller, String idempotencyKey, String contractHeader,
            GenericProvisioningRequest request, String correlationId) {
        ProvisioningMetadata metadata = new ProvisioningMetadata(idempotencyKey, correlationId,
                caller.subject(), caller.jti(), caller.actorId(), caller.actorRole());

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw rejected(Operation.CREATE_TENANT, ProvisioningErrorCode.IDEMPOTENCY_KEY_REQUIRED, null,
                    metadata.withoutKey(), new PlatformProvisioningException(ProvisioningErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw rejected(Operation.CREATE_TENANT, ProvisioningErrorCode.INVALID_IDEMPOTENCY_KEY, null,
                    metadata.withoutKey(), new PlatformProvisioningException(ProvisioningErrorCode.INVALID_IDEMPOTENCY_KEY));
        }

        ProvisionTenantCommand command;
        try {
            command = GenericRequestAdapter.adapt(request, contractHeader);
        } catch (PlatformProvisioningException invalid) {
            throw rejected(Operation.CREATE_TENANT, invalid.code(), null, metadata, invalid);
        }

        try {
            PlatformTenantProvisioner.Outcome outcome = provisioner.provision(command, metadata);
            log.info("MasterAdmin provisioning {} for control-plane tenant {}: organization {}, company {}",
                    outcome.replayed() ? "replayed" : "created", command.controlPlaneTenantId(),
                    outcome.record().organizationId(), outcome.record().companyId());
            return ProvisionedTenantView.of(outcome.record(), outcome.replayed(), correlationId);
        } catch (PlatformProvisioningException contractOutcome) {
            throw rejected(Operation.CREATE_TENANT, contractOutcome.code(), command.controlPlaneTenantId(),
                    metadata, contractOutcome);
        } catch (DataIntegrityViolationException raced) {
            // A unique key lost to a concurrent call the advisory locks do not cover (another tenant
            // taking the same organization code or email at the same instant). Nothing was kept.
            throw rejected(Operation.CREATE_TENANT, ProvisioningErrorCode.TENANT_CONFLICT,
                    command.controlPlaneTenantId(), metadata,
                    new PlatformProvisioningException(ProvisioningErrorCode.TENANT_CONFLICT));
        } catch (RuntimeException failure) {
            log.error("MasterAdmin provisioning failed for control-plane tenant {} (correlation {}); "
                    + "the transaction was rolled back", command.controlPlaneTenantId(), correlationId, failure);
            throw rejected(Operation.CREATE_TENANT, ProvisioningErrorCode.PROVISIONING_FAILED,
                    command.controlPlaneTenantId(), metadata,
                    new PlatformProvisioningException(ProvisioningErrorCode.PROVISIONING_FAILED));
        }
    }

    /** {@code GET /tenants/{controlPlaneTenantId}}. Read-only; never changes state. */
    public ProvisionedTenantView status(PlatformCaller caller, String controlPlaneTenantId, String correlationId) {
        ProvisioningMetadata metadata = new ProvisioningMetadata(null, correlationId,
                caller.subject(), caller.jti(), caller.actorId(), caller.actorRole());
        UUID tenantId = GenericRequestAdapter.uuid(controlPlaneTenantId);
        if (tenantId == null) {
            throw rejected(Operation.GET_TENANT_STATUS, ProvisioningErrorCode.INVALID_REQUEST, null, metadata,
                    new PlatformProvisioningException(ProvisioningErrorCode.INVALID_REQUEST, List.of(
                            new PlatformProvisioningException.FieldIssue("controlPlaneTenantId", "must be a UUID"))));
        }
        ProvisioningRecord record;
        try {
            record = repository.findByControlPlaneTenantId(tenantId).orElse(null);
        } catch (RuntimeException failure) {
            log.error("MasterAdmin provisioning status read failed (correlation {})", correlationId, failure);
            throw rejected(Operation.GET_TENANT_STATUS, ProvisioningErrorCode.PROVISIONING_FAILED, tenantId, metadata,
                    new PlatformProvisioningException(ProvisioningErrorCode.PROVISIONING_FAILED));
        }
        if (record == null) {
            throw rejected(Operation.GET_TENANT_STATUS, ProvisioningErrorCode.PROVISIONING_NOT_FOUND, tenantId,
                    metadata, new PlatformProvisioningException(ProvisioningErrorCode.PROVISIONING_NOT_FOUND));
        }
        audit(new ProvisioningAuditEntry(Operation.GET_TENANT_STATUS, Result.FOUND, 200, null, tenantId, null,
                record.provisioningId(), correlationId, metadata.m2mSubject(), metadata.m2mJti(),
                metadata.actorId(), metadata.actorRole()));
        return ProvisionedTenantView.of(record, null, correlationId);
    }

    /** Audits a call that created nothing, then hands back the exception to throw. */
    private PlatformProvisioningException rejected(Operation operation, ProvisioningErrorCode code,
            UUID controlPlaneTenantId, ProvisioningMetadata metadata, PlatformProvisioningException toThrow) {
        Result result = code == ProvisioningErrorCode.PROVISIONING_FAILED ? Result.ERROR
                : code.isConflict() ? Result.CONFLICT : Result.REJECTED;
        audit(new ProvisioningAuditEntry(operation, result, code.httpStatus(), code.name(), controlPlaneTenantId,
                metadata.idempotencyKey(), null, metadata.correlationId(), metadata.m2mSubject(), metadata.m2mJti(),
                metadata.actorId(), metadata.actorRole()));
        log.warn("MasterAdmin provisioning {} refused with {} (correlation {})", operation, code,
                metadata.correlationId());
        return toThrow;
    }

    /**
     * An audit failure never changes the answer MasterAdmin gets - the call's outcome is already
     * decided - but it is logged, so a broken trail is visible.
     */
    private void audit(ProvisioningAuditEntry entry) {
        try {
            ownTransaction.executeWithoutResult(status -> repository.recordAudit(entry));
        } catch (RuntimeException auditFailure) {
            log.error("Could not write the MasterAdmin provisioning audit entry (correlation {})",
                    entry.correlationId(), auditFailure);
        }
    }
}
