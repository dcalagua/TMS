package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleAvailabilityStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link Vehicle}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface VehicleRepository extends JpaRepository<Vehicle, UUID>, JpaSpecificationExecutor<Vehicle> {

    Optional<Vehicle> findByIdAndCompanyId(UUID id, UUID companyId);

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, for resolving a page's worth of
     * references in one query. The company predicate is in the query rather than applied to the
     * result in Java: a row of another tenant must never be loaded at all, not merely discarded
     * after loading.
     */
    List<Vehicle> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    boolean existsByCompanyIdAndLicensePlate(UUID companyId, String licensePlate);

    boolean existsByCompanyIdAndLicensePlateAndIdNot(UUID companyId, String licensePlate, UUID id);

    /** Which of a bulk import file's codes/plates this company already holds - see {@code VehicleImportService}. */
    List<Vehicle> findByCompanyIdAndCodeIn(UUID companyId, Collection<String> codes);

    /**
     * The assignable fleet, for {@code VehicleLookupService.findAssignableInCompany}: active and
     * currently available. Ordering is applied in Java, on the resolved effective capacity - see
     * that method.
     */
    List<Vehicle> findByCompanyIdAndActiveTrueAndAvailabilityStatus(
            UUID companyId, VehicleAvailabilityStatus availabilityStatus);

    List<Vehicle> findByCompanyIdAndLicensePlateIn(UUID companyId, Collection<String> licensePlates);
}
