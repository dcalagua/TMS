package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.IntegrationRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the integration inbox. Every finder is company-scoped: the inbox is tenant
 * data, and "which deliveries has this partner sent" is a question only that partner's company
 * may ask.
 */
public interface IntegrationRequestRepository extends JpaRepository<IntegrationRequest, UUID> {

    /**
     * The idempotency lookup, keyed exactly like {@code uq_integration_request_idempotency}. The
     * company predicate is redundant given the credential predicate - the composite foreign key
     * already ties the two - and is present anyway, because a query that reads correctly on its
     * own is worth more than one that relies on a constraint three tables away.
     */
    Optional<IntegrationRequest> findByCompanyIdAndIntegrationClientIdAndOperationAndIdempotencyKey(
            UUID companyId, UUID integrationClientId, String operation, String idempotencyKey);

    Page<IntegrationRequest> findByCompanyId(UUID companyId, Pageable pageable);

    Page<IntegrationRequest> findByCompanyIdAndIntegrationClientId(
            UUID companyId, UUID integrationClientId, Pageable pageable);
}
