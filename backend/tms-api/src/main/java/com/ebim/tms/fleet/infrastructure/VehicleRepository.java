package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Vehicle;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link Vehicle}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface VehicleRepository extends JpaRepository<Vehicle, UUID>, JpaSpecificationExecutor<Vehicle> {

    Optional<Vehicle> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    boolean existsByCompanyIdAndLicensePlate(UUID companyId, String licensePlate);

    boolean existsByCompanyIdAndLicensePlateAndIdNot(UUID companyId, String licensePlate, UUID id);
}
