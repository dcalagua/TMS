package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.CarrierPayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Company-scoped persistence for {@link CarrierPayment} (V67). Insert and read only: the table is append-only. */
public interface CarrierPaymentRepository extends JpaRepository<CarrierPayment, UUID> {

    List<CarrierPayment> findByCompanyIdAndCarrierInvoiceIdOrderByPaidOnAscCreatedAtAsc(UUID companyId,
            UUID carrierInvoiceId);

    /** The business idempotency key ({@code uq_carrier_payment_reference}). */
    Optional<CarrierPayment> findByCompanyIdAndCarrierInvoiceIdAndPaymentReference(UUID companyId,
            UUID carrierInvoiceId, String paymentReference);

    /** What the invoice's {@code paid_amount} must equal - the payments are the truth. */
    @Query("select coalesce(sum(p.amount), 0) from CarrierPayment p"
            + " where p.companyId = :companyId and p.carrierInvoiceId = :invoiceId")
    BigDecimal sumForInvoice(@Param("companyId") UUID companyId, @Param("invoiceId") UUID invoiceId);
}
