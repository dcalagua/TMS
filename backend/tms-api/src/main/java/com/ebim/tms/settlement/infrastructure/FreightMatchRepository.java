package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightMatch;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link FreightMatch}. One current match per invoice. */
public interface FreightMatchRepository extends JpaRepository<FreightMatch, UUID> {

    Optional<FreightMatch> findByCompanyIdAndCarrierInvoiceId(UUID companyId, UUID carrierInvoiceId);

    /** The batched sibling, so an invoice list shows every verdict in one query rather than one per row. */
    List<FreightMatch> findByCompanyIdAndCarrierInvoiceIdIn(UUID companyId, Collection<UUID> invoiceIds);
}
