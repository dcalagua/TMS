package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.CarrierInvoiceLine;
import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import com.ebim.tms.settlement.domain.FreightMatch;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.settlement.domain.MatchStatus;
import com.ebim.tms.settlement.domain.PayableExport;
import com.ebim.tms.settlement.domain.SettlementApproval;
import com.ebim.tms.shared.reference.TripCostLookupPort;
import com.ebim.tms.shared.reference.TripSettlementLookupPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One invoice, with everything a freight auditor needs to answer <em>why</em> (migration V46).
 *
 * <p>The design rule for this record: <b>the reasoning is not hidden behind a status.</b> A screen
 * showing DISCREPANCY and nothing else forces somebody to reconstruct the comparison by hand, which
 * is the work this module exists to remove. So every line carries what TMS expected beside what was
 * billed, and every difference carries the sentence that explains it.
 */
public record CarrierInvoiceView(
        UUID id,
        UUID carrierId,
        String carrierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        String currency,
        BigDecimal totalAmount,
        InvoiceStatus status,
        Set<InvoiceStatus> allowedTransitions,
        String externalReference,
        OffsetDateTime receivedAt,
        String notes,
        long version,
        List<LineView> lines,
        MatchView match,
        List<FreightDiscrepancyView> discrepancies,
        List<ApprovalView> approvals,
        PayableExportView export) {

    /**
     * One billed line beside what TMS knows about the shipment it names.
     *
     * @param expectedAmount   <b>null when the shipment was never priced</b>, never zero
     * @param differenceAmount billed minus expected, null when there is no expected figure
     * @param shipmentNumber   null when the line names no shipment, or one this carrier did not run
     */
    public record LineView(
            UUID id,
            int lineNumber,
            UUID tripId,
            String shipmentNumber,
            String description,
            BigDecimal quantity,
            BigDecimal unitAmount,
            BigDecimal lineAmount,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            BigDecimal differenceAmount) {
    }

    /**
     * The verdict and what produced it.
     *
     * @param toleranceAbsolute   what was compared against, snapshotted - a tolerance widened next
     *                            month must not restate why this invoice matched
     */
    public record MatchView(
            MatchStatus status,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            BigDecimal invoicedAmount,
            BigDecimal differenceAmount,
            BigDecimal toleranceAbsolute,
            BigDecimal tolerancePercentage,
            int matchedTripCount,
            int unmatchedLineCount,
            OffsetDateTime computedAt) {
    }

    public record ApprovalView(
            UUID id, SettlementApproval.Decision decision, UUID decidedBy, OffsetDateTime decidedAt,
            String comment) {
    }

    public static CarrierInvoiceView of(CarrierInvoice invoice, String carrierName, FreightMatch match,
            List<FreightDiscrepancy> discrepancies, List<SettlementApproval> approvals, PayableExport export,
            Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips,
            Map<UUID, TripCostLookupPort.TripCostSummary> costs) {

        List<LineView> lines = invoice.lines().stream()
                .map(line -> toLineView(line, invoice.carrierId(), trips, costs))
                .toList();

        return new CarrierInvoiceView(invoice.id(), invoice.carrierId(), carrierName, invoice.invoiceNumber(),
                invoice.invoiceDate(), invoice.currency(), invoice.totalAmount(), invoice.status(),
                invoice.status().allowedTransitions(), invoice.externalReference(), invoice.receivedAt(),
                invoice.notes(), invoice.version(), lines,
                match == null ? null : new MatchView(match.status(), match.expectedAmount(),
                        match.actualAmount(), match.invoicedAmount(), match.differenceAmount(),
                        match.toleranceAbsolute(), match.tolerancePercentage(), match.matchedTripCount(),
                        match.unmatchedLineCount(), match.computedAt()),
                discrepancies.stream().map(FreightDiscrepancyView::of).toList(),
                approvals.stream()
                        .map(approval -> new ApprovalView(approval.id(), approval.decision(),
                                approval.decidedBy(), approval.decidedAt(), approval.comment()))
                        .toList(),
                export == null ? null : PayableExportView.of(export, true));
    }

    private static LineView toLineView(CarrierInvoiceLine line, UUID invoiceCarrierId,
            Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips,
            Map<UUID, TripCostLookupPort.TripCostSummary> costs) {
        TripSettlementLookupPort.TripSettlementSummary trip =
                line.tripId() == null ? null : trips.get(line.tripId());
        // A shipment this carrier did not run is shown as unnamed, matching how the matcher treats
        // it: comparing an amount against somebody else's work would be worse than showing a gap.
        boolean theirs = trip != null && invoiceCarrierId.equals(trip.carrierId());
        TripCostLookupPort.TripCostSummary cost =
                theirs ? costs.get(line.tripId()) : null;

        BigDecimal expected = cost == null ? null : cost.expectedAmount();
        return new LineView(line.id(), line.lineNumber(), line.tripId(),
                theirs ? trip.shipmentNumber() : null,
                line.description(), line.quantity(), line.unitAmount(), line.lineAmount(),
                expected,
                cost == null ? null : cost.actualAmount(),
                // Null, not zero: without an expected figure there is no difference to state.
                expected == null ? null : line.lineAmount().subtract(expected));
    }
}
