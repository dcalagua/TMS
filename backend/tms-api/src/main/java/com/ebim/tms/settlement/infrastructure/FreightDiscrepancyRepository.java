package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

/** Company-scoped persistence for {@link FreightDiscrepancy}. */
public interface FreightDiscrepancyRepository extends JpaRepository<FreightDiscrepancy, UUID> {

    Optional<FreightDiscrepancy> findByIdAndCompanyId(UUID id, UUID companyId);

    List<FreightDiscrepancy> findByCompanyIdAndCarrierInvoiceIdOrderByCreatedAtAsc(
            UUID companyId, UUID carrierInvoiceId);

    void deleteByCompanyIdAndCarrierInvoiceId(UUID companyId, UUID carrierInvoiceId);

    /**
     * Open discrepancies against any of the given shipments (JOB 23).
     *
     * <p>Joined through the invoice line's {@code trip_id} and <b>no further</b>. This query
     * deliberately does not name {@code Trip}: settlement has no concept of an operating day, and a
     * join to find one would be a cross-module dependency hidden inside a string, where
     * {@code ModuleBoundaryTest} cannot see it. The control tower passes in the day's shipments
     * because it is the module that knows what they are.
     *
     * <p>Projects rather than returning entities, so the caller cannot end up holding something it
     * could resolve - see {@code SettlementAdvisoryPort}.
     */
    @Query("select new com.ebim.tms.shared.reference.SettlementAdvisoryPort$SettlementAdvisory("
            + "d.id, i.id, i.invoiceNumber, l.tripId, cast(d.type as string), "
            + "d.differenceAmount, i.currency, d.detail) "
            + "from FreightDiscrepancy d "
            + "join CarrierInvoice i on i.id = d.carrierInvoiceId and i.companyId = d.companyId "
            + "join CarrierInvoiceLine l on l.id = d.invoiceLineId and l.companyId = d.companyId "
            + "where d.companyId = :companyId and d.status = 'OPEN' and l.tripId in :tripIds "
            + "order by d.createdAt desc")
    List<com.ebim.tms.shared.reference.SettlementAdvisoryPort.SettlementAdvisory>
            findOpenForTrips(@Param("companyId") UUID companyId,
                    @Param("tripIds") java.util.Collection<UUID> tripIds, Pageable pageable);

    @Query("select count(d) from FreightDiscrepancy d "
            + "join CarrierInvoiceLine l on l.id = d.invoiceLineId and l.companyId = d.companyId "
            + "where d.companyId = :companyId and d.status = 'OPEN' and l.tripId in :tripIds")
    long countOpenForTrips(@Param("companyId") UUID companyId,
            @Param("tripIds") java.util.Collection<UUID> tripIds);
}
