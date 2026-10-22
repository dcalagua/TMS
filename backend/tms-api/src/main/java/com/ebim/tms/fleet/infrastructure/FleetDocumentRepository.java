package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.FleetDocument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link FleetDocument}. Every finder is scoped by {@code companyId}. */
public interface FleetDocumentRepository
        extends JpaRepository<FleetDocument, UUID>, JpaSpecificationExecutor<FleetDocument> {

    Optional<FleetDocument> findByIdAndCompanyId(UUID id, UUID companyId);

    List<FleetDocument> findByCompanyIdAndVehicleIdOrderByDocumentTypeAscExpiresOnDesc(UUID companyId, UUID vehicleId);

    List<FleetDocument> findByCompanyIdAndDriverIdOrderByDocumentTypeAscExpiresOnDesc(UUID companyId, UUID driverId);

    List<FleetDocument> findByCompanyIdAndVehicleIdInAndActiveTrue(UUID companyId, Collection<UUID> vehicleIds);

    List<FleetDocument> findByCompanyIdAndDriverIdInAndActiveTrue(UUID companyId, Collection<UUID> driverIds);

    List<FleetDocument> findByCompanyIdAndActiveTrue(UUID companyId);
}
