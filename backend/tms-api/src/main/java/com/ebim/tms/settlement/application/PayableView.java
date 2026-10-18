package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierPayment;
import com.ebim.tms.settlement.domain.CarrierPaymentRequest;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.PaymentRequestStatus;
import com.ebim.tms.settlement.domain.PaymentSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One invoice as a payable (V67, ADR-023 section 2): what is owed, when, what was paid and what is
 * waiting for a second person. TMS records payments; it makes none (D-01).
 *
 * @param payableAmount  net, less detraction and retention: what the carrier itself is paid
 * @param openBalance    payable less paid
 * @param daysOverdue    days past the due date while something is still owed; null otherwise
 * @param allowedActions what a person may do now, so the screen renders what the server allows
 */
public record PayableView(
        UUID invoiceId,
        String invoiceNumber,
        String documentType,
        UUID carrierId,
        String carrierName,
        String currency,
        InvoiceStatus status,
        LocalDate invoiceDate,
        LocalDate dueDate,
        Integer paymentTermsDays,
        LocalDate scheduledPaymentDate,
        String onHoldReason,
        BigDecimal netAmount,
        BigDecimal detractionAmount,
        String detractionConstancyNumber,
        LocalDate detractionConstancyDate,
        BigDecimal retentionAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal openBalance,
        Integer daysOverdue,
        Set<Action> allowedActions,
        List<PaymentView> payments,
        List<PaymentRequestView> requests) {

    /** The office's moves on a payable. Recording a payment is always the two-step request/confirm. */
    public enum Action { SCHEDULE, HOLD, RELEASE, REQUEST_PAYMENT }

    public record PaymentView(UUID id, String paymentReference, LocalDate paidOn, BigDecimal amount, String currency,
            String detractionConstancyNumber, LocalDate detractionConstancyDate, BigDecimal detractionAmount,
            BigDecimal retentionAmount, PaymentSource source, UUID recordedBy, UUID confirmedBy,
            OffsetDateTime createdAt) {

        static PaymentView of(CarrierPayment payment) {
            return new PaymentView(payment.id(), payment.paymentReference(), payment.paidOn(), payment.amount(),
                    payment.currency(), payment.detractionConstancyNumber(), payment.detractionConstancyDate(),
                    payment.detractionAmount(), payment.retentionAmount(), payment.source(), payment.recordedBy(),
                    payment.confirmedBy(), payment.createdAt());
        }
    }

    public record PaymentRequestView(UUID id, String paymentReference, LocalDate paidOn, BigDecimal amount,
            String currency, String detractionConstancyNumber, LocalDate detractionConstancyDate,
            BigDecimal detractionAmount, BigDecimal retentionAmount, PaymentRequestStatus status, UUID requestedBy,
            OffsetDateTime requestedAt, UUID decidedBy, OffsetDateTime decidedAt, String decisionComment) {

        static PaymentRequestView of(CarrierPaymentRequest request) {
            var detail = request.detail();
            return new PaymentRequestView(request.id(), detail.paymentReference(), detail.paidOn(), detail.amount(),
                    detail.currency(), detail.detractionConstancyNumber(), detail.detractionConstancyDate(),
                    detail.detractionAmount(), detail.retentionAmount(), request.status(), request.requestedBy(),
                    request.requestedAt(), request.decidedBy(), request.decidedAt(), request.decisionComment());
        }
    }
}
