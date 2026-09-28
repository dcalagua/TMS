package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.WarehouseMilestone;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link WarehouseMilestone}. */
public interface WarehouseMilestoneRepository extends JpaRepository<WarehouseMilestone, UUID> {

    boolean existsByCompanyIdAndSourceSystemAndEventId(UUID companyId, String sourceSystem, String eventId);

    List<WarehouseMilestone> findByCompanyIdAndTripIdOrderByOccurredAtAsc(UUID companyId, UUID tripId);
}
