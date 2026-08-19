package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Destination;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Destination}. See {@code OriginRepository} for the
 * isolation rule every finder here follows.
 */
public interface DestinationRepository extends JpaRepository<Destination, UUID>, JpaSpecificationExecutor<Destination> {

    Optional<Destination> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
