package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TenderWaterfall;
import com.ebim.tms.planning.domain.WaterfallStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for the tender waterfall (migration V40). */
public interface TenderWaterfallRepository extends JpaRepository<TenderWaterfall, UUID> {

    Optional<TenderWaterfall> findByIdAndCompanyId(UUID id, UUID companyId);

    Optional<TenderWaterfall> findByCompanyIdAndTripIdAndStatus(UUID companyId, UUID tripId,
            WaterfallStatus status);

    List<TenderWaterfall> findByCompanyIdAndTripIdOrderByStartedAtDesc(UUID companyId, UUID tripId);

    // Removed in JOB 15: findByIdForUpdate(UUID) took a waterfall by its own id with no company
    // predicate and had no callers - an unscoped own-id finder waiting for somebody to reach for
    // it, which is exactly the shape a cross-tenant read takes. TenantScopedRepositoryTest now
    // refuses to let one back in. If a locking read is ever needed, it takes the company too.
}
