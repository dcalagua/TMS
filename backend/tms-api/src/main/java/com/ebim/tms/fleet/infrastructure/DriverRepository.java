package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Driver;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link Driver}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface DriverRepository extends JpaRepository<Driver, UUID>, JpaSpecificationExecutor<Driver> {

    Optional<Driver> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The batched sibling of {@link #findByIdAndCompanyId} - see
     * {@link VehicleRepository#findByIdInAndCompanyId} for why the company predicate is in the
     * query rather than applied to the result in Java.
     */
    List<Driver> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    boolean existsByCompanyIdAndDocumentTypeAndDocumentNumber(
            UUID companyId, String documentType, String documentNumber);

    boolean existsByCompanyIdAndDocumentTypeAndDocumentNumberAndIdNot(
            UUID companyId, String documentType, String documentNumber, UUID id);

    boolean existsByCompanyIdAndLicenseNumber(UUID companyId, String licenseNumber);

    boolean existsByCompanyIdAndLicenseNumberAndIdNot(UUID companyId, String licenseNumber, UUID id);
}
