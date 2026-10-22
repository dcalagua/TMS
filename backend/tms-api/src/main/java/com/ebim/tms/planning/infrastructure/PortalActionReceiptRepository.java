package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.PortalActionReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The idempotency receipts of carrier-portal execution actions (V70). Append-only. */
public interface PortalActionReceiptRepository extends JpaRepository<PortalActionReceipt, UUID> {

    /** A retried portal request is found by who sent it and the key they sent (V70, ADR-018 section 5). */
    Optional<PortalActionReceipt> findByCompanyIdAndAppUserIdAndIdempotencyKey(UUID companyId, UUID appUserId,
            String idempotencyKey);
}
