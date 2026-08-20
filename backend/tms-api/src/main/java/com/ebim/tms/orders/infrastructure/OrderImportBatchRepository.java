package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.OrderImportBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Company-scoped persistence for {@link OrderImportBatch}. See {@code TransportOrderRepository}
 * for the isolation rule every finder here follows.
 */
public interface OrderImportBatchRepository extends JpaRepository<OrderImportBatch, UUID> {

    /** The import history of one company, most recent first. */
    List<OrderImportBatch> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);
}
