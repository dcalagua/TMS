package com.ebim.tms.costing.infrastructure;

import com.ebim.tms.costing.domain.OwnFleetCostProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every finder is company-scoped, without exception (ADR-003, and the guard JOB 15 added).
 */
public interface OwnFleetCostProfileRepository extends JpaRepository<OwnFleetCostProfile, UUID> {

    Optional<OwnFleetCostProfile> findByIdAndCompanyId(UUID id, UUID companyId);

    List<OwnFleetCostProfile> findByCompanyIdOrderByEffectiveFromDesc(UUID companyId);

    /**
     * The candidates for one truck: its own profiles and its type's, in one read.
     *
     * <p>Precedence between the two is decided by {@code OwnFleetProfileResolver} and deliberately
     * not by an {@code ORDER BY} here - a rule spelled out in a query is a rule a later index hint
     * can quietly reorder, and this one decides which cost a company sees.
     */
    @Query("select p from OwnFleetCostProfile p where p.companyId = :companyId "
            + "and (p.vehicleId = :vehicleId or p.vehicleTypeId = :vehicleTypeId)")
    List<OwnFleetCostProfile> findCandidates(@Param("companyId") UUID companyId,
            @Param("vehicleId") UUID vehicleId, @Param("vehicleTypeId") UUID vehicleTypeId);
}
