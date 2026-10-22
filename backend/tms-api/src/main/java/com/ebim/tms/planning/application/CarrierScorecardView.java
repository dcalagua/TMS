package com.ebim.tms.planning.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The carrier scorecard ({@code docs/domain/CARRIER_SCORECARD_V1.md}): one row per carrier - and
 * one for our own fleet - over a span of operating days.
 *
 * <p><b>Every percentage is null when its denominator is zero</b>, through {@code KpiRate}: a carrier
 * nobody recorded a departure for has no on-time percentage, and "0%" would accuse it of something
 * nobody measured. Each percentage travels beside the counts it was divided from, so a screen can say
 * "92% of 5" rather than "92%".
 *
 * <p><b>Four blocks are finer than the endpoint</b> and are null - not empty, not zero - for a caller
 * without the permission that owns them: tenders ({@code planning.tender:read}), documents
 * ({@code planning.document_delivery:read}), invoices ({@code settlement.invoice:read}) and cost
 * ({@code rates.trip_cost:read}). The four {@code *Visible} flags say so once for the whole report, so
 * a screen can hide a column instead of filling it with dashes.
 *
 * @param unattributedTrips trips of the range with neither a carrier nor a vehicle: nobody's record
 *                          yet, counted here so the carriers' trips plus these add up to the range
 */
public record CarrierScorecardView(
        LocalDate from,
        LocalDate to,
        int days,
        OffsetDateTime generatedAt,
        boolean tendersVisible,
        boolean documentsVisible,
        boolean invoicesVisible,
        boolean costVisible,
        long unattributedTrips,
        List<CarrierRow> carriers) {

    public CarrierScorecardView {
        carriers = List.copyOf(carriers);
    }

    /**
     * One carrier.
     *
     * @param carrierKey      the carrier's id, or {@code OWN_FLEET}; what the drill-down is asked with
     * @param carrierId       null for our own fleet
     * @param trips           trips of the range that were not cancelled
     * @param tripsExecuted   of those, the ones that left ({@code IN_TRANSIT} or {@code COMPLETED})
     */
    public record CarrierRow(
            String carrierKey,
            UUID carrierId,
            boolean ownFleet,
            String carrierCode,
            String carrierName,
            long trips,
            long tripsCancelled,
            long tripsExecuted,
            long tripsCompleted,
            long departuresMeasured,
            long departuresLate,
            BigDecimal onTimeDeparturePercent,
            long stops,
            long serviceWindowsMeasured,
            long serviceWindowsMissed,
            BigDecimal inWindowPercent,
            long deliveriesRecorded,
            long deliveriesDelivered,
            long deliveriesShort,
            long deliveriesNotAttempted,
            BigDecimal deliverySuccessPercent,
            long exceptions,
            long exceptionsOpen,
            BigDecimal exceptionsPer100Trips,
            TenderFigures tenders,
            DocumentFigures documents,
            List<InvoiceFigures> invoices,
            List<CostFigures> cost) {
    }

    /**
     * Offers made to this carrier. Expired offers count against it: an offer left to lapse is the
     * carrier's answer as much as a refusal is, which is where this departs from the KPI report's
     * company-wide acceptance (accepted over answered).
     *
     * @param acceptancePercent      accepted / (accepted + rejected + expired)
     * @param averageResponseMinutes sent to answered, over {@code timed} answers; null when none
     */
    public record TenderFigures(long offered, long accepted, long rejected, long expired, long awaiting,
            long timed, BigDecimal acceptancePercent, BigDecimal averageResponseMinutes) {
    }

    /**
     * @param deliveredPercent    documents reported delivered / documents carried
     * @param cargoReturnPercent  signed copies returned to the office / documents delivered
     */
    public record DocumentFigures(long documents, long delivered, long returned, BigDecimal deliveredPercent,
            BigDecimal cargoReturnPercent) {
    }

    /**
     * The carrier's invoices dated in the range, in one currency - as the KPI report's settlement
     * block counts them, by {@code invoice_date} and not by the trips' planning date.
     *
     * @param amountObserved billed over what was expected, outside tolerance, on the flagged invoices
     */
    public record InvoiceFigures(String currency, long invoices, long withDiscrepancy,
            BigDecimal discrepancyRatePercent, BigDecimal amountObserved) {
    }

    /**
     * What the carrier's trips cost, in one currency: the actual where it was recorded, the estimate
     * otherwise.
     *
     * @param tripsCosted     trips of the range with a known cost in this currency
     * @param tripsWithActual of those, with a recorded actual
     * @param costPerTrip     amount / tripsCosted, two decimals
     * @param weightKg        kilograms assigned to the costed trips that carried weight
     * @param costPerKg       the cost of those trips / weightKg, four decimals; null without weight
     */
    public record CostFigures(String currency, long tripsCosted, long tripsWithActual, BigDecimal amount,
            BigDecimal costPerTrip, BigDecimal weightKg, BigDecimal costPerKg) {
    }
}
