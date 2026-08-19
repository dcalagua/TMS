package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.VehicleType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link VehicleType}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface VehicleTypeRepository extends JpaRepository<VehicleType, UUID>, JpaSpecificationExecutor<VehicleType> {

    Optional<VehicleType> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);
}
