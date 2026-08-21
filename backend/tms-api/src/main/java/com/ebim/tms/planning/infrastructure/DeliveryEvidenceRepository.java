package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.DeliveryEvidence;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The proof-of-delivery artefacts attached to a trip's deliveries (migration V28) - their metadata;
 * the bytes are the object store's.
 *
 * <p>Append-only, like {@link TransportEventRepository}: no delete and no update, because evidence
 * a party can quietly remove is not evidence. The database withholds both grants as well, so this
 * is a statement of intent rather than the enforcement.
 */
public interface DeliveryEvidenceRepository extends JpaRepository<DeliveryEvidence, UUID> {

    /**
     * Every artefact of a set of deliveries, oldest first - one query for a whole trip's evidence
     * rather than one per delivery.
     */
    List<DeliveryEvidence> findByCompanyIdAndOrderDeliveryIdInOrderByUploadedAtAsc(
            UUID companyId, Collection<UUID> orderDeliveryIds);

    /** The one artefact a download addresses, scoped so another tenant's id simply does not exist. */
    Optional<DeliveryEvidence> findByIdAndCompanyId(UUID id, UUID companyId);
}
