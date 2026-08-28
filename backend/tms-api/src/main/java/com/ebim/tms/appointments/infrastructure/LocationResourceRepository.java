package com.ebim.tms.appointments.infrastructure;

import com.ebim.tms.appointments.domain.LocationResource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for docks. Every finder carries the company predicate in the query. */
public interface LocationResourceRepository
        extends JpaRepository<LocationResource, UUID>, JpaSpecificationExecutor<LocationResource> {

    Optional<LocationResource> findByIdAndCompanyId(UUID id, UUID companyId);

    List<LocationResource> findByCompanyIdAndLocationIdOrderByCodeAsc(UUID companyId, UUID locationId);

    List<LocationResource> findByCompanyIdAndIdIn(UUID companyId, Collection<UUID> ids);

    boolean existsByLocationIdAndCode(UUID locationId, String code);

    boolean existsByLocationIdAndCodeAndIdNot(UUID locationId, String code, UUID id);
}
