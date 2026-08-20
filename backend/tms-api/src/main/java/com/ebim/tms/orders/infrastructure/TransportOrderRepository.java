package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TransportOrder;
import java.util.Collection;
import java.util.List;
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

    /**
     * The batched sibling of {@link #findByIdAndCompanyId}, for resolving a page's worth of
     * references in one query. The company predicate is in the query rather than applied to the
     * result in Java: a row of another tenant must never be loaded at all, not merely discarded
     * after loading.
     */
    List<TransportOrder> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    boolean existsByCompanyIdAndExternalSourceAndExternalReference(UUID companyId, String externalSource, String externalReference);

    /**
     * Resolves the order a sending system's own key names, for the inbound integration upsert.
     * The pair is unique per company ({@code uq_transport_order_external}), so at most one row
     * can match - which is what makes a redelivery an update rather than a duplicate.
     */
    Optional<TransportOrder> findByCompanyIdAndExternalSourceAndExternalReference(
            UUID companyId, String externalSource, String externalReference);

    /**
     * Which of {@code references} this company already holds under {@code externalSource} - the
     * bulk import's idempotency check, answered in one query for the whole file rather than one
     * {@code exists} per order.
     *
     * <p>Returns only the reference strings: the import needs to know <em>whether</em> an order
     * exists, never anything about it, and loading a page's worth of entities to answer a
     * membership question would be a needless read of data the caller must not act on.
     */
    @Query("""
            SELECT o.externalReference FROM TransportOrder o
             WHERE o.companyId = :companyId
               AND o.externalSource = :externalSource
               AND o.externalReference IN :references
            """)
    List<String> findExistingExternalReferences(UUID companyId, String externalSource, Collection<String> references);

    boolean existsByCompanyIdAndExternalSourceAndExternalReferenceAndIdNot(
            UUID companyId, String externalSource, String externalReference, UUID id);

    /**
     * The next value of {@code tms.transport_order_number_seq} - {@code OrderService} formats
     * it into {@code order_number}. A plain {@code nextval()} call, not an entity read, so it
     * never participates in optimistic locking or the persistence context.
     */
    @Query(value = "SELECT nextval('tms.transport_order_number_seq')", nativeQuery = true)
    long nextOrderNumberValue();

    /**
     * {@code count} sequence values in one round trip, for the bulk import. Calling
     * {@link #nextOrderNumberValue()} once per order would make a 1,000-order file 1,000
     * round trips before a single row is written, which is the difference between an import
     * that feels instant and one an operator watches a spinner through.
     *
     * <p>The values are still allocated one at a time by the sequence, so they carry the same
     * no-gap-reuse and no-collision guarantee a single {@code nextval} does; only the network
     * cost is amortised.
     */
    @Query(value = "SELECT nextval('tms.transport_order_number_seq') FROM generate_series(1, :count)",
            nativeQuery = true)
    List<Long> nextOrderNumberValues(int count);
}
