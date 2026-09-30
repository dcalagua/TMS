package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Frequency;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Company-scoped persistence for {@link Frequency}. See {@code LocationRepository} for the
 * isolation rule every finder here follows.
 */
public interface FrequencyRepository extends JpaRepository<Frequency, UUID>, JpaSpecificationExecutor<Frequency> {

    Optional<Frequency> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, with the weekly rules fetched in the same
     * query - a release board evaluates a handful of frequencies against many orders (ADR-014).
     */
    @Query("""
            select distinct f from Frequency f left join fetch f.weeklyRules
             where f.id in :ids and f.companyId = :companyId
            """)
    List<Frequency> findByIdInAndCompanyId(@Param("ids") Collection<UUID> ids, @Param("companyId") UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
