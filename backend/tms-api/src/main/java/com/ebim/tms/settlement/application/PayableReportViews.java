package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The payables reports (V67, ADR-023 section 2): aging, the due list, the Control Tower card, pending confirmations. */
public final class PayableReportViews {

    private PayableReportViews() {
    }

    /** The buckets, in the order a person reads them. */
    public static final List<String> BUCKETS =
            List.of("NOT_DUE", "DAYS_1_30", "DAYS_31_60", "DAYS_61_90", "DAYS_OVER_90", "NO_DUE_DATE");

    /**
     * What is owed, by days past due on {@code asOf}. Balances are today's: the payments and notes on
     * record now, bucketed against the date asked for.
     */
    public record AgingView(LocalDate asOf, List<String> buckets, List<AgingTotal> totals,
            List<AgingCarrier> carriers) {
    }

    public record AgingTotal(String currency, Map<String, BigDecimal> amounts, BigDecimal total, long count) {
    }

    public record AgingCarrier(UUID carrierId, String carrierName, String currency, Map<String, BigDecimal> amounts,
            BigDecimal total, long count) {
    }

    /** @param daysToDue negative when overdue */
    public record DuePayableView(UUID invoiceId, String invoiceNumber, UUID carrierId, String carrierName,
            String currency, InvoiceStatus status, LocalDate invoiceDate, LocalDate dueDate, long daysToDue,
            BigDecimal openBalance, BigDecimal paidAmount, BigDecimal payableAmount) {
    }

    /** The Control Tower card "Facturas vencidas / por vencer". Counts and amounts per currency, never summed across. */
    public record SummaryView(LocalDate asOf, int dueSoonDays, long pendingPaymentRequests,
            List<CurrencySummary> currencies) {
    }

    public record CurrencySummary(String currency, long overdueCount, BigDecimal overdueAmount, long dueSoonCount,
            BigDecimal dueSoonAmount) {
    }

    public record PendingPaymentRequestView(UUID id, UUID invoiceId, String invoiceNumber, String carrierName,
            String currency, BigDecimal amount, String paymentReference, LocalDate paidOn, UUID requestedBy,
            OffsetDateTime requestedAt, BigDecimal openBalance) {
    }
}
