package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.PayableExport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Company-scoped persistence for {@link PayableExport}.
 *
 * <p>One row per invoice, ever - {@code uq_payable_export_invoice}. The finder below is what makes a
 * repeated export idempotent rather than a second obligation.
 */
public interface PayableExportRepository extends JpaRepository<PayableExport, UUID> {

    Optional<PayableExport> findByCompanyIdAndCarrierInvoiceId(UUID companyId, UUID carrierInvoiceId);
}
