package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Mis facturas" in the carrier portal (V67, ADR-023 section 2): what a carrier may know about its own
 * documents - the status, the due date, what was paid and the detraction - and nothing of the audit
 * behind them. No expected cost, no difference, no tolerance, no internal note, no hold reason.
 */
public final class PortalInvoiceViews {

    private PortalInvoiceViews() {
    }

    /**
     * The office's lifecycle folded into what a carrier acts on: the audit steps are one "in review",
     * and an export is an approval (handing it to accounting is not the carrier's news).
     */
    public enum PortalInvoiceStatus {
        IN_REVIEW, APPROVED, SCHEDULED, PARTIALLY_PAID, PAID, ON_HOLD, REJECTED;

        static PortalInvoiceStatus of(InvoiceStatus status) {
            return switch (status) {
                case RECEIVED, MATCHING, MATCHED, DISCREPANCY, UNDER_REVIEW -> IN_REVIEW;
                case APPROVED, EXPORTED -> APPROVED;
                case SCHEDULED -> SCHEDULED;
                case PARTIALLY_PAID -> PARTIALLY_PAID;
                case PAID -> PAID;
                case ON_HOLD -> ON_HOLD;
                case REJECTED -> REJECTED;
            };
        }
    }

    /** @param openBalance null while in review or rejected: nothing is owed until somebody authorised it */
    public record Summary(UUID id, String invoiceNumber, String documentType, LocalDate invoiceDate, String currency,
            BigDecimal totalAmount, PortalInvoiceStatus status, LocalDate dueDate, BigDecimal paidAmount,
            BigDecimal openBalance, BigDecimal detractionAmount, String detractionConstancyNumber) {
    }

    /** @param rejectionReason what the office said when it refused the document: the carrier has to answer it */
    public record Detail(UUID id, String invoiceNumber, String documentType, LocalDate invoiceDate, String currency,
            BigDecimal totalAmount, PortalInvoiceStatus status, LocalDate dueDate, BigDecimal paidAmount,
            BigDecimal openBalance, BigDecimal detractionAmount, String detractionConstancyNumber,
            LocalDate detractionConstancyDate, BigDecimal netAmount, BigDecimal retentionAmount,
            BigDecimal payableAmount, String rejectionReason, List<Line> lines, List<Payment> payments) {
    }

    public record Line(int lineNumber, String description, BigDecimal lineAmount, String chargeCode) {
    }

    public record Payment(String paymentReference, LocalDate paidOn, BigDecimal amount,
            String detractionConstancyNumber, LocalDate detractionConstancyDate, BigDecimal detractionAmount,
            BigDecimal retentionAmount) {
    }
}
