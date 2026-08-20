package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.infrastructure.IntegrationRequestRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the integration inbox.
 *
 * <p>Both methods run in their <b>own transaction</b>, and that is the entire design. A delivery
 * that fails rolls its business transaction back; if the inbox row shared that transaction it
 * would roll back too, and the one record explaining what went wrong would be the one record that
 * never survives. {@code REQUIRES_NEW} makes the audit trail independent of the outcome it
 * describes.
 */
@Service
public class IntegrationInboxService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationInboxService.class);

    private final IntegrationRequestRepository requestRepository;

    public IntegrationInboxService(IntegrationRequestRepository requestRepository) {
        this.requestRepository = requestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IntegrationRequest> findByIdempotencyKey(UUID companyId, UUID integrationClientId,
            IntegrationOperation operation, String idempotencyKey) {
        return requestRepository.findByCompanyIdAndIntegrationClientIdAndOperationAndIdempotencyKey(
                companyId, integrationClientId, operation.code(), idempotencyKey);
    }

    /**
     * Records one delivery.
     *
     * <p>A duplicate-key violation here means two identical deliveries raced and both finished:
     * the unique index on {@code (company, client, operation, idempotency_key)} let exactly one
     * row through. That is not a request failure - the business work already committed, and the
     * work itself is idempotent, so both callers got a correct answer. It is logged and swallowed
     * rather than propagated, because turning a successful write into a 500 would make a partner
     * retry an operation that already succeeded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(IntegrationRequest request) {
        try {
            requestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException raced) {
            log.warn("Integration inbox row not written for client {} operation {}: a concurrent delivery with the "
                            + "same idempotency key won the race", request.integrationClientId(), request.operation());
        }
    }

}
