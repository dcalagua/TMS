package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.SettlementApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped, append-only persistence for {@link SettlementApproval}. */
public interface SettlementApprovalRepository extends JpaRepository<SettlementApproval, UUID> {

    List<SettlementApproval> findByCompanyIdAndCarrierInvoiceIdOrderByDecidedAtAsc(
            UUID companyId, UUID carrierInvoiceId);
}
