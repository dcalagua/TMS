package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.IntegrationRequest;
import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * How many inbound requests landed in each state since {@code since} (JOB 13).
     *
     * <p>Windowed rather than lifetime, because "is this integration healthy" is a question about
     * now: a partner that failed a hundred times last month and has worked all week is working, and
     * a lifetime count would say the opposite forever.
     */
    @Query("SELECT r.status AS status, COUNT(r) AS requestCount FROM IntegrationRequest r "
            + "WHERE r.companyId = :companyId AND r.receivedAt >= :since GROUP BY r.status")
    List<RequestStatusCount> countByStatusSince(@Param("companyId") UUID companyId,
            @Param("since") OffsetDateTime since);

    /** One row of {@link #countByStatusSince}. */
    interface RequestStatusCount {
        IntegrationRequestStatus getStatus();

        long getRequestCount();
    }

    Page<IntegrationRequest> findByCompanyIdAndIntegrationClientId(
            UUID companyId, UUID integrationClientId, Pageable pageable);
}
