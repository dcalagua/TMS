package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DocumentDeliveryEvidence;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Photos and signatures behind document reports (ADR-018, V57). No delete: none is ever granted. */
public interface DocumentDeliveryEvidenceRepository extends JpaRepository<DocumentDeliveryEvidence, UUID> {

    List<DocumentDeliveryEvidence> findByCompanyIdAndDocumentDeliveryIdInOrderByUploadedAtAsc(UUID companyId,
            Collection<UUID> documentDeliveryIds);

    Optional<DocumentDeliveryEvidence> findByIdAndCompanyIdAndDocumentDeliveryId(UUID id, UUID companyId,
            UUID documentDeliveryId);
}
