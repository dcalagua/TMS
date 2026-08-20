package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Route;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Route}. See {@code LocationRepository} for the isolation
 * rule every finder here follows.
 */
public interface RouteRepository extends JpaRepository<Route, UUID>, JpaSpecificationExecutor<Route> {

    Optional<Route> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, for resolving a whole planning
     * board's worth of route references in one query ({@code RouteTemplateLookupAdapter}). The
     * company predicate is in the query rather than applied to the result in Java: a row of
     * another tenant must never be loaded at all, not merely discarded after loading - the same
     * rule {@code CarrierRepository.findByIdInAndCompanyId} documents.
     */
    List<Route> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
