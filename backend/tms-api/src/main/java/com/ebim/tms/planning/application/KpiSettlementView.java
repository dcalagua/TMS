package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.KpiRate;
import com.ebim.tms.shared.reference.SettlementKpiPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * How freight settlement went over the range (V64, ADR-024 part 1, item 7;
 * {@code docs/domain/KPIS_REPORTING_V1.md}, section "Settlement").
 *
 * <p>Invoices are placed in the range by their {@code invoice_date}. The company figures are counts
 * and hours only; money is per carrier <b>and currency</b>, never summed across currencies.
 *
 * <p><b>Null for a caller without {@code settlement.invoice:read}</b> - see {@code KpiReportView}.
 *
 * @param discrepancyRatePercent invoices with a difference over invoices received, or null when
 *                               none was received
 * @param cycleMedianHours       received to approved, median, in hours; null when none was approved
 */
public record KpiSettlementView(
        long invoicesReceived,
        long invoicesApproved,
        long invoicesWithDiscrepancy,
        BigDecimal discrepancyRatePercent,
        BigDecimal cycleMedianHours,
        BigDecimal cycleP90Hours,
        List<CarrierRow> byCarrier) {

    /**
     * One carrier in one currency.
     *
     * @param amountObserved positive header differences matching flagged (billed over expected)
     * @param amountSaved    the part of {@code amountObserved} refused
     */
    public record CarrierRow(
            UUID carrierId,
            String carrierName,
            String currency,
            long invoices,
            long approved,
            long withDiscrepancy,
            BigDecimal discrepancyRatePercent,
            BigDecimal amountObserved,
            BigDecimal amountSaved,
            BigDecimal cycleMedianHours,
            BigDecimal cycleP90Hours) {
    }

    public static KpiSettlementView from(SettlementKpiPort.SettlementKpiTotals totals) {
        return new KpiSettlementView(totals.invoicesReceived(), totals.invoicesApproved(),
                totals.invoicesWithDiscrepancy(),
                KpiRate.percent(totals.invoicesWithDiscrepancy(), totals.invoicesReceived()),
                totals.cycleMedianHours(), totals.cycleP90Hours(),
                totals.byCarrier().stream()
                        .map(row -> new CarrierRow(row.carrierId(), row.carrierName(), row.currency(),
                                row.invoices(), row.approved(), row.withDiscrepancy(),
                                KpiRate.percent(row.withDiscrepancy(), row.invoices()),
                                row.amountObserved(), row.amountSaved(),
                                row.cycleMedianHours(), row.cycleP90Hours()))
                        .toList());
    }
}
