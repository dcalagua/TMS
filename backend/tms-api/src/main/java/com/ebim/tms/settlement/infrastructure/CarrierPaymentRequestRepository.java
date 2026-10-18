package com.ebim.tms.settlement.infrastructure;

import com.ebim.tms.settlement.domain.CarrierPaymentRequest;
import com.ebim.tms.settlement.domain.PaymentRequestStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Company-scoped persistence for {@link CarrierPaymentRequest} (V67). */
public interface CarrierPaymentRequestRepository extends JpaRepository<CarrierPaymentRequest, UUID> {

    Optional<CarrierPaymentRequest> findByIdAndCompanyIdAndCarrierInvoiceId(UUID id, UUID companyId,
            UUID carrierInvoiceId);

    List<CarrierPaymentRequest> findByCompanyIdAndCarrierInvoiceIdOrderByRequestedAtAsc(UUID companyId,
            UUID carrierInvoiceId);

    List<CarrierPaymentRequest> findByCompanyIdAndStatusOrderByRequestedAtAsc(UUID companyId,
            PaymentRequestStatus status, Pageable pageable);

    long countByCompanyIdAndStatus(UUID companyId, PaymentRequestStatus status);

    boolean existsByCompanyIdAndCarrierInvoiceIdAndPaymentReferenceAndStatus(UUID companyId, UUID carrierInvoiceId,
            String paymentReference, PaymentRequestStatus status);
}
