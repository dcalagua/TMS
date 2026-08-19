package com.ebim.tms.fleet.infrastructure;

import com.ebim.tms.fleet.domain.Carrier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Company-scoped persistence for {@link Carrier}. Every finder is scoped by {@code companyId} - no exceptions. */
public interface CarrierRepository extends JpaRepository<Carrier, UUID>, JpaSpecificationExecutor<Carrier> {

    Optional<Carrier> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndCode(UUID companyId, String code);

    boolean existsByCompanyIdAndCodeAndIdNot(UUID companyId, String code, UUID id);

    boolean existsByCompanyIdAndTaxIdTypeAndTaxIdValue(UUID companyId, String taxIdType, String taxIdValue);

    boolean existsByCompanyIdAndTaxIdTypeAndTaxIdValueAndIdNot(
            UUID companyId, String taxIdType, String taxIdValue, UUID id);
}
