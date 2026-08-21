package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.LocationFrequency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link LocationFrequency}. Scoped by {@code locationId}/{@code companyId}
 * directly since, unlike {@code FrequencyException}, this row carries its own {@code companyId} -
 * see the entity's class comment for why.
 */
public interface LocationFrequencyRepository extends JpaRepository<LocationFrequency, UUID> {

    List<LocationFrequency> findByLocationIdAndCompanyIdOrderByEffectiveFromAsc(UUID locationId, UUID companyId);

    Optional<LocationFrequency> findByIdAndLocationIdAndCompanyId(UUID id, UUID locationId, UUID companyId);

    boolean existsByLocationIdAndFrequencyIdAndActiveTrue(UUID locationId, UUID frequencyId);

    boolean existsByLocationIdAndFrequencyIdAndActiveTrueAndIdNot(UUID locationId, UUID frequencyId, UUID id);
}
