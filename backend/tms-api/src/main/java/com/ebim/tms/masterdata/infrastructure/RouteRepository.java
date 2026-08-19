package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Route}. See {@code OriginRepository} for the isolation
 * rule every finder here follows.
 */
public interface RouteRepository extends JpaRepository<Route, UUID>, JpaSpecificationExecutor<Route> {

    Optional<Route> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
