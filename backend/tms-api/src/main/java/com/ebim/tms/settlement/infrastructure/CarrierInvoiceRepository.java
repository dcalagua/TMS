package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
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
}
