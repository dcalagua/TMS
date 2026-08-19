package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TransportOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * Company-scoped persistence for {@link TransportOrder}. See {@code OriginRepository} for the
 * isolation rule every finder here follows.
 */
public interface TransportOrderRepository extends JpaRepository<TransportOrder, UUID>, JpaSpecificationExecutor<TransportOrder> {

    Optional<TransportOrder> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndExternalSourceAndExternalReference(UUID companyId, String externalSource, String externalReference);

    boolean existsByCompanyIdAndExternalSourceAndExternalReferenceAndIdNot(
            UUID companyId, String externalSource, String externalReference, UUID id);

    /**
     * The next value of {@code tms.transport_order_number_seq} - {@code OrderService} formats
     * it into {@code order_number}. A plain {@code nextval()} call, not an entity read, so it
     * never participates in optimistic locking or the persistence context.
     */
    @Query(value = "SELECT nextval('tms.transport_order_number_seq')", nativeQuery = true)
    long nextOrderNumberValue();
}
