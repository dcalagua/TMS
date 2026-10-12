package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DocumentDeliveryEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The append-only history of tracked documents (ADR-018, V57). */
public interface DocumentDeliveryEventRepository extends JpaRepository<DocumentDeliveryEvent, UUID> {

    List<DocumentDeliveryEvent> findByCompanyIdAndDocumentDeliveryIdOrderByRecordedAtAsc(UUID companyId,
            UUID documentDeliveryId);

    /** ADR-018 section 5: a retried portal request is found by who sent it and the key they sent. */
    Optional<DocumentDeliveryEvent> findByCompanyIdAndActorIdAndIdempotencyKey(UUID companyId, UUID actorId,
            String idempotencyKey);
}
