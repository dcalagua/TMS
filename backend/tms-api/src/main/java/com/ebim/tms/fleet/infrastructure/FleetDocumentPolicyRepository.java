package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.FleetDocumentPolicy;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link FleetDocumentPolicy}. */
public interface FleetDocumentPolicyRepository extends JpaRepository<FleetDocumentPolicy, UUID> {

    List<FleetDocumentPolicy> findByCompanyId(UUID companyId);
}
