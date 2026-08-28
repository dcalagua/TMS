package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.TolerancePolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link TolerancePolicy}. */
public interface TolerancePolicyRepository extends JpaRepository<TolerancePolicy, UUID> {

    Optional<TolerancePolicy> findByIdAndCompanyId(UUID id, UUID companyId);

    List<TolerancePolicy> findByCompanyIdAndActiveTrue(UUID companyId);

    Optional<TolerancePolicy> findByCompanyIdAndCarrierIdAndActiveTrue(UUID companyId, UUID carrierId);

    /** The company-wide default: the row with no carrier. */
    Optional<TolerancePolicy> findByCompanyIdAndCarrierIdIsNullAndActiveTrue(UUID companyId);
}
