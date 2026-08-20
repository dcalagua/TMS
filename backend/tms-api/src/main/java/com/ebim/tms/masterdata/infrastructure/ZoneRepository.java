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

    /**
     * Resolves a zone by the code a human or a sending system knows it as - the inbound
     * integration API takes codes, never uuids.
     */
    Optional<Zone> findByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
