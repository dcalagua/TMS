package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.SettlementKpiPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Finance over the range (V68, ADR-026 section 8; {@code docs/domain/KPIS_REPORTING_V1.md}, "Finance"):
 * open accrual balance, accrual against invoice, days to payment and cost per kg. Money per currency,
 * never summed across currencies.
 *
 * <p><b>Null for a caller without {@code settlement.accrual:read}</b> - see {@code KpiReportView}.
 *
 * @param averageDaysToPayment approval to last payment of the invoices paid in full in the range;
 *                             null when none was
 */
public record KpiFinanceView(List<CurrencyRow> byCurrency, BigDecimal averageDaysToPayment, long paidInvoices) {

    /**
     * @param variancePercent invoiced over accrued, minus one, in percent; null when nothing was accrued
     */
    public record CurrencyRow(
            String currency,
            BigDecimal openAccrualBalance,
            long openAccruals,
            long accrualsBilled,
            BigDecimal accruedAmount,
            BigDecimal invoicedAmount,
            BigDecimal varianceAmount,
            BigDecimal variancePercent,
            BigDecimal allocatedCost,
            BigDecimal allocatedWeightKg,
            BigDecimal costPerKg) {
    }

    public static KpiFinanceView from(SettlementKpiPort.FinanceKpiTotals totals) {
        if (totals == null) {
            return null;
        }
        return new KpiFinanceView(totals.byCurrency().stream()
                .map(row -> new CurrencyRow(row.currency(), row.openAccrualBalance(), row.openAccruals(),
                        row.accrualsBilled(), row.accruedAmount(), row.invoicedAmount(), row.varianceAmount(),
                        row.accruedAmount() == null || row.accruedAmount().signum() == 0 ? null
                                : row.varianceAmount().multiply(BigDecimal.valueOf(100))
                                        .divide(row.accruedAmount(), 1, RoundingMode.HALF_UP),
                        row.allocatedCost(), row.allocatedWeightKg(), row.costPerKg()))
                .toList(), totals.averageDaysToPayment(), totals.paidInvoices());
    }
}
