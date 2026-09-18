package com.ebim.tms.platform.application;

import com.ebim.tms.platform.application.ProvisioningExecutor.ProvisioningOutcome;
import com.ebim.tms.shared.api.ProblemType;
import com.ebim.tms.shared.api.ProblemTypedException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The provisioning use case as the controller sees it.
 *
 * <p>Deliberately <em>not</em> transactional. It brackets the transaction that
 * {@link ProvisioningExecutor} owns, so that when that transaction rolls back there is still
 * somewhere to stand to write down why - {@link ProvisioningJournal} runs afterwards, in its own
 * transaction. Putting all three in one class would not work: Spring's proxy does not intercept a
 * call an object makes to itself, so the propagation that makes the journal survive a rollback
 * would silently not apply.
 *
 * <p>The split also keeps the failure vocabulary honest. A refusal - the tenant exists, the email
 * belongs to somebody else, the key was reused - is recorded and rethrown unchanged, because the
 * control plane can act on it. Anything else becomes {@code PROVISIONING_FAILED} with a generic
 * sentence, and the real cause goes to the log under the correlation id: an integrator has no use
 * for a constraint name, and publishing one tells an attacker about the schema.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final ProvisioningExecutor executor;
    private final ProvisioningJournal journal;
    private final Clock clock;

    public TenantProvisioningService(ProvisioningExecutor executor, ProvisioningJournal journal, Clock clock) {
        this.executor = executor;
        this.journal = journal;
        this.clock = clock;
    }

    public ProvisioningOutcome provision(TenantProvisioningRequest request, ProvisioningCaller caller,
            String idempotencyKey, String correlationId) {

        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        try {
            return executor.execute(request, caller, idempotencyKey, correlationId);
        } catch (ProblemTypedException refused) {
            journal.recordFailure(request, caller, idempotencyKey, correlationId, refused.type().code(),
                    refused.getMessage(), refused.type().status().value(), receivedAt);
            throw refused;
        } catch (RuntimeException unexpected) {
            log.error("Provisioning failed for control-plane tenant {} (correlationId {})",
                    request.controlPlaneTenantId(), correlationId, unexpected);
            journal.recordFailure(request, caller, idempotencyKey, correlationId,
                    ProblemType.PROVISIONING_FAILED.code(),
                    "Provisioning could not be completed; nothing was created.",
                    ProblemType.PROVISIONING_FAILED.status().value(), receivedAt);
            throw new ProblemTypedException(ProblemType.PROVISIONING_FAILED,
                    "Provisioning could not be completed. Nothing was created; the same "
                            + "Idempotency-Key may be retried.", unexpected);
        }
    }

    public Optional<TenantProvisioningView> find(UUID controlPlaneTenantId) {
        return executor.find(controlPlaneTenantId);
    }
}
