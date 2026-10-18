package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /**
     * The finance figures of the range (V68, ADR-026 section 8): open accrual balance at {@code to},
     * accrual against invoice for the trips served in the range and already billed, days from approval
     * to payment for the invoices paid in full in the range, and cost per kg from the current cost
     * allocations of the range. Per currency; the days are company-wide (a count, not money).
     *
     * @param zone the company's calendar, which an approval instant is a day of
     */
    FinanceKpiTotals finance(UUID companyId, ZoneId zone, LocalDate from, LocalDate to);

    /**
     * @param averageDaysToPayment approval to the last payment, in days, one decimal; null when none
     *                             was paid in full in the range
     */
    record FinanceKpiTotals(List<CurrencyFinance> byCurrency, BigDecimal averageDaysToPayment, long paidInvoices) {

        public FinanceKpiTotals {
            byCurrency = List.copyOf(byCurrency);
        }
    }

    /**
     * @param openAccrualBalance  accrued - consumed - reversed of the OPEN and PARTIALLY_SETTLED
     *                            accruals served up to the end of the range
     * @param accrualsBilled      accruals served in the range that an approved invoice consumed
     * @param accruedAmount       what those accruals provisioned
     * @param invoicedAmount      what the authorised documents billed for the same trips (net of IGV)
     * @param varianceAmount      invoiced - accrued: positive when the carrier billed more than provisioned
     * @param allocatedCost       cost allocated to orders served in the range (current runs)
     * @param allocatedWeightKg   the kilograms that cost was spread over
     * @param costPerKg           allocatedCost / allocatedWeightKg, four decimals; null without weight
     */
    record CurrencyFinance(
            String currency,
            BigDecimal openAccrualBalance,
            long openAccruals,
            long accrualsBilled,
            BigDecimal accruedAmount,
            BigDecimal invoicedAmount,
            BigDecimal varianceAmount,
            BigDecimal allocatedCost,
            BigDecimal allocatedWeightKg,
            BigDecimal costPerKg) {
    }
}
