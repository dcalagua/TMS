package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link CarrierInvoice}. Every finder is scoped by {@code companyId}. */
public interface CarrierInvoiceRepository extends JpaRepository<CarrierInvoice, UUID> {

    Optional<CarrierInvoice> findByIdAndCompanyId(UUID id, UUID companyId);

    Page<CarrierInvoice> findByCompanyId(UUID companyId, Pageable pageable);

    Page<CarrierInvoice> findByCompanyIdAndStatusIn(UUID companyId, Collection<InvoiceStatus> statuses,
            Pageable pageable);

    Page<CarrierInvoice> findByCompanyIdAndCarrierId(UUID companyId, UUID carrierId, Pageable pageable);

    boolean existsByCompanyIdAndCarrierIdAndInvoiceNumber(UUID companyId, UUID carrierId, String invoiceNumber);

    /**
     * The invoice under a write lock, for the decisions that must happen once.
     *
     * <p>Approval and export both read the invoice, check its state and write a row that must not
     * exist twice. The {@code @Version} column catches the stale write; this lock stops the two
     * transactions interleaving in the first place, so the loser fails on a state check with a
     * sentence rather than on an optimistic-lock exception nobody can act on.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CarrierInvoice i where i.id = :id and i.companyId = :companyId")
    Optional<CarrierInvoice> findByIdAndCompanyIdForUpdate(@Param("id") UUID id,
            @Param("companyId") UUID companyId);

    /**
     * Which of these shipments some <b>other</b> live invoice of this company already bills.
     *
     * <p>The question {@code uq_carrier_invoice_number} cannot answer. That constraint stops one
     * document arriving twice; it says nothing about the same shipment turning up on two documents
     * with different numbers, which is how a trip gets paid for twice. There is deliberately no
     * unique index behind this: re-billing a shipment is legal, so this is evidence for a person
     * rather than a refusal from the database.
     *
     * <p><b>{@code REJECTED} is excluded, and only {@code REJECTED}.</b> A refused invoice bills
     * nothing - {@link com.ebim.tms.settlement.domain.InvoiceStatus} says a carrier who disagrees
     * issues a credit note and a new number - so counting it would make every legitimate re-bill
     * look like a duplicate. Every other state is a live claim on the money, {@code RECEIVED}
     * included: an invoice nobody has matched yet is still going to be paid.
     *
     * <p>Not carrier-scoped on purpose. Two carriers billing one shipment is the worse case, not
     * the excusable one.
     */
    default List<UUID> findTripIdsBilledOnOtherInvoices(UUID companyId, Collection<UUID> tripIds,
            UUID excludedInvoiceId) {
        return findTripIdsBilledOnOtherInvoices(companyId, tripIds, excludedInvoiceId,
                InvoiceStatus.REJECTED);
    }

    /**
     * Which (shipment, charge) pairs of these shipments some <b>other</b> live invoice of this
     * company already bills (V64) - the question {@link #findTripIdsBilledOnOtherInvoices} asks,
     * at the grain the matcher now decides duplicates on. Same exclusions, same reasons. A null
     * charge code (every V46 line) reads as {@code FREIGHT}.
     */
    default java.util.Set<com.ebim.tms.settlement.domain.FreightMatcher.TripCharge> findTripChargesBilledOnOtherInvoices(
            UUID companyId, Collection<UUID> tripIds, UUID excludedInvoiceId) {
        java.util.Set<com.ebim.tms.settlement.domain.FreightMatcher.TripCharge> charges = new java.util.LinkedHashSet<>();
        for (Object[] row : findTripChargeRowsBilledOnOtherInvoices(companyId, tripIds, excludedInvoiceId,
                InvoiceStatus.REJECTED)) {
            charges.add(new com.ebim.tms.settlement.domain.FreightMatcher.TripCharge((UUID) row[0],
                    (com.ebim.tms.settlement.domain.ChargeCode) row[1]));
        }
        return charges;
    }

    @Query("""
            select distinct l.tripId, l.chargeCode
              from CarrierInvoiceLine l
              join l.invoice i
             where l.companyId = :companyId
               and l.tripId in :tripIds
               and i.id <> :excludedInvoiceId
               and i.status <> :excludedStatus
            """)
    List<Object[]> findTripChargeRowsBilledOnOtherInvoices(@Param("companyId") UUID companyId,
            @Param("tripIds") Collection<UUID> tripIds,
            @Param("excludedInvoiceId") UUID excludedInvoiceId,
            @Param("excludedStatus") InvoiceStatus excludedStatus);

    /**
     * The query behind it. {@code REJECTED} arrives as a bound parameter rather than as an enum
     * literal in the JPQL - the two are equivalent to Hibernate, and a parameter is the form that
     * cannot be broken by a dialect or a version. The default method above is what keeps the rule
     * itself here rather than at the call site.
     */
    @Query("""
            select distinct l.tripId
              from CarrierInvoiceLine l
              join l.invoice i
             where l.companyId = :companyId
               and l.tripId in :tripIds
               and i.id <> :excludedInvoiceId
               and i.status <> :excludedStatus
            """)
    List<UUID> findTripIdsBilledOnOtherInvoices(@Param("companyId") UUID companyId,
            @Param("tripIds") Collection<UUID> tripIds,
            @Param("excludedInvoiceId") UUID excludedInvoiceId,
            @Param("excludedStatus") InvoiceStatus excludedStatus);

    /**
     * Settlement KPIs over a range of invoice dates, per carrier and currency, plus one grand row
     * (carrier and currency null) whose counts and hours span them all (V64, ADR-024 item 7).
     *
     * <p>"Observed" is the positive {@code TOTAL_AMOUNT} difference - one per invoice at most, so
     * line and header differences are never counted twice; "saved" is the part of it refused, by
     * rejecting the difference or the invoice. The cycle runs from {@code created_at} (when TMS
     * received it) to the first APPROVED decision; {@code percentile_cont} skips the unapproved.
     */
    @Query(value = """
            WITH inv AS (
                SELECT i.carrier_id, i.currency, i.created_at,
                       (SELECT min(a.decided_at) FROM tms.settlement_approval a
                         WHERE a.company_id = i.company_id AND a.carrier_invoice_id = i.id
                           AND a.decision = 'APPROVED') AS approved_at,
                       EXISTS (SELECT 1 FROM tms.freight_discrepancy d
                                WHERE d.company_id = i.company_id AND d.carrier_invoice_id = i.id)
                           AS has_discrepancy,
                       (SELECT coalesce(sum(d.difference_amount), 0) FROM tms.freight_discrepancy d
                         WHERE d.company_id = i.company_id AND d.carrier_invoice_id = i.id
                           AND d.type = 'TOTAL_AMOUNT' AND d.difference_amount > 0) AS observed,
                       (SELECT coalesce(sum(d.difference_amount), 0) FROM tms.freight_discrepancy d
                         WHERE d.company_id = i.company_id AND d.carrier_invoice_id = i.id
                           AND d.type = 'TOTAL_AMOUNT' AND d.difference_amount > 0
                           AND (d.status = 'REJECTED' OR i.status = 'REJECTED')) AS saved
                  FROM tms.carrier_invoice i
                 WHERE i.company_id = :companyId
                   AND i.invoice_date BETWEEN :fromDate AND :toDate
            )
            SELECT carrier_id AS "carrierId",
                   currency AS "currency",
                   count(*) AS "invoices",
                   count(approved_at) AS "approved",
                   count(*) FILTER (WHERE has_discrepancy) AS "withDiscrepancy",
                   sum(observed) AS "amountObserved",
                   sum(saved) AS "amountSaved",
                   percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY extract(epoch FROM approved_at - created_at) / 3600.0) AS "cycleMedianHours",
                   percentile_cont(0.9) WITHIN GROUP (
                       ORDER BY extract(epoch FROM approved_at - created_at) / 3600.0) AS "cycleP90Hours",
                   (GROUPING(carrier_id, currency) <> 0) AS "grandTotal"
              FROM inv
             GROUP BY GROUPING SETS ((carrier_id, currency), ())
             ORDER BY "grandTotal", carrier_id, currency
            """, nativeQuery = true)
    List<SettlementKpiRow> settlementKpis(@Param("companyId") UUID companyId,
            @Param("fromDate") java.time.LocalDate from, @Param("toDate") java.time.LocalDate to);

    /** The projection behind {@link #settlementKpis}; translated to a port type by the adapter. */
    interface SettlementKpiRow {
        UUID getCarrierId();

        String getCurrency();

        long getInvoices();

        long getApproved();

        long getWithDiscrepancy();

        java.math.BigDecimal getAmountObserved();

        java.math.BigDecimal getAmountSaved();

        Double getCycleMedianHours();

        Double getCycleP90Hours();

        boolean getGrandTotal();
    }
}
