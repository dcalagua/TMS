package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightClaimEvidence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The evidence behind a freight claim (V71). Insert and read only; the database withholds the rest. */
public interface FreightClaimEvidenceRepository extends JpaRepository<FreightClaimEvidence, UUID> {

    Optional<FreightClaimEvidence> findByIdAndCompanyId(UUID id, UUID companyId);

    List<FreightClaimEvidence> findByCompanyIdAndFreightClaimIdOrderByCreatedAtAsc(UUID companyId,
            UUID freightClaimId);
}
