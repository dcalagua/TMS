package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How the KPI report asks how freight settlement went over a range, without {@code planning}
 * depending on {@code settlement} (V64, ADR-024 part 1, item 7). Implemented by
 * {@code settlement.infrastructure.SettlementKpiAdapter}.
 *
 * <p>Aggregates only, like {@link TripCostAnalyticsPort}: no invoice id and no line, so the report
 * is not a second door onto the audit queue. Amounts are per carrier <b>and currency</b> - TMS holds
 * no rates of exchange - and the company-wide figures are counts and hours, never a sum of money.
 */
public interface SettlementKpiPort {

    /** Invoices dated (their {@code invoice_date}) between {@code from} and {@code to}, both inclusive. */
    SettlementKpiTotals totals(UUID companyId, LocalDate from, LocalDate to);

    /**
     * @param invoicesWithDiscrepancy invoices whose current match raised at least one difference
     * @param cycleMedianHours        received (recorded in TMS) to approved, in hours, over the
     *                                approved ones; null when none was approved
     */
    record SettlementKpiTotals(
            long invoicesReceived,
            long invoicesApproved,
            long invoicesWithDiscrepancy,
            BigDecimal cycleMedianHours,
            BigDecimal cycleP90Hours,
            List<CarrierSettlementTotals> byCarrier) {

        public SettlementKpiTotals {
            byCarrier = List.copyOf(byCarrier);
        }
    }

    /**
     * One carrier in one currency.
     *
     * @param amountObserved the positive header differences matching flagged: billed over what was
     *                       expected, outside tolerance
     * @param amountSaved    the part of that refused - the difference rejected, or the invoice
     */
    record CarrierSettlementTotals(
            UUID carrierId,
            String carrierName,
            String currency,
            long invoices,
            long approved,
            long withDiscrepancy,
            BigDecimal amountObserved,
            BigDecimal amountSaved,
            BigDecimal cycleMedianHours,
            BigDecimal cycleP90Hours) {
    }
}
