package com.ebim.tms.masterdata.infrastructure;

import com.ebim.tms.masterdata.domain.Location;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Company-scoped persistence for {@link Location}. Every finder carries the company predicate in
 * the query, never as a filter applied to the result in Java: a row of another tenant must never
 * be loaded at all, not merely discarded after loading. See {@code OriginRepository} for the
 * same rule stated at the module's first master.
 */
public interface LocationRepository extends JpaRepository<Location, UUID>, JpaSpecificationExecutor<Location> {

    Optional<Location> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    /**
     * The external identity guard behind {@code uq_location_external}. Checked in the service so
     * a duplicate is a readable 409 rather than a constraint violation translated into a generic
     * conflict, with the unique index as the race backstop.
     */
    boolean existsByCompanyIdAndExternalSystemAndExternalReference(
            UUID companyId, String externalSystem, String externalReference);

    boolean existsByCompanyIdAndExternalSystemAndExternalReferenceAndIdNot(
            UUID companyId, String externalSystem, String externalReference, UUID id);
}
