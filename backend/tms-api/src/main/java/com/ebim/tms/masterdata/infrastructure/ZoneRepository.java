package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Zone;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Zone}. See {@link OriginRepository} for the isolation
 * rule every finder here follows.
 */
public interface ZoneRepository extends JpaRepository<Zone, UUID>, JpaSpecificationExecutor<Zone> {

    Optional<Zone> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
