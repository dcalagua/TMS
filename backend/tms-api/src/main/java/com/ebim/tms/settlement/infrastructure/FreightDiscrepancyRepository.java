package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link FreightDiscrepancy}. */
public interface FreightDiscrepancyRepository extends JpaRepository<FreightDiscrepancy, UUID> {

    Optional<FreightDiscrepancy> findByIdAndCompanyId(UUID id, UUID companyId);

    List<FreightDiscrepancy> findByCompanyIdAndCarrierInvoiceIdOrderByCreatedAtAsc(
            UUID companyId, UUID carrierInvoiceId);

    void deleteByCompanyIdAndCarrierInvoiceId(UUID companyId, UUID carrierInvoiceId);
}
