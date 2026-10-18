package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.CarrierInvoiceLine;
import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import com.ebim.tms.settlement.domain.ChargeCode;
import com.ebim.tms.settlement.domain.ExpectedBasis;
import com.ebim.tms.settlement.domain.FreightMatch;
import com.ebim.tms.settlement.domain.FreightMatcher;
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
        PayableExportView export,
        /** The V66 fiscal part: breakdown, CPE identity, notes, detraction, retention. */
        FiscalView fiscal) {

    /**
     * What the document says to the tax authority (V66, ADR-024 part 2).
     *
     * @param netAmount     an invoice's total less approved credit notes plus approved debit notes;
     *                      null on a note
     * @param cpeValidation what SUNAT says of the CPE - always {@code EXTERNAL_DEPENDENCY} today,
     *                      never "valid" (ADR-024 part 2, item 4); null without a CPE identity
     */
    public record FiscalView(
            String documentType,
            int effectiveSign,
            String series,
            String correlative,
            String issuerRuc,
            BigDecimal subtotalAmount,
            BigDecimal igvRate,
            BigDecimal igvAmount,
            UUID referencedInvoiceId,
            String referencedInvoiceNumber,
            BigDecimal netAmount,
            List<NoteView> notes,
            String detractionCode,
            BigDecimal detractionRate,
            BigDecimal detractionAmount,
            String detractionConstancyNumber,
            LocalDate detractionConstancyDate,
            BigDecimal retentionRate,
            BigDecimal retentionAmount,
            String retentionOverrideReason,
            String cpeValidation) {

        public static FiscalView of(CarrierInvoice invoice, String referencedInvoiceNumber, BigDecimal netAmount,
                List<NoteView> notes, CpeValidationPort.CpeValidationStatus cpeValidation) {
            return new FiscalView(invoice.documentType().code(), invoice.effectiveSign(), invoice.series(),
                    invoice.correlative(), invoice.issuerRuc(), invoice.subtotalAmount(), invoice.igvRate(),
                    invoice.igvAmount(), invoice.referencedInvoiceId(), referencedInvoiceNumber, netAmount,
                    List.copyOf(notes), invoice.detractionCode(), invoice.detractionRate(),
                    invoice.detractionAmount(), invoice.detractionConstancyNumber(),
                    invoice.detractionConstancyDate(), invoice.retentionRate(), invoice.retentionAmount(),
                    invoice.retentionOverrideReason(), cpeValidation == null ? null : cpeValidation.name());
        }
    }

    /** A credit or debit note that corrects this invoice, as the invoice lists it. */
    public record NoteView(UUID id, String documentType, String invoiceNumber, LocalDate invoiceDate,
            BigDecimal totalAmount, InvoiceStatus status) {

        public static NoteView of(CarrierInvoice note) {
            return new NoteView(note.id(), note.documentType().code(), note.invoiceNumber(), note.invoiceDate(),
                    note.totalAmount(), note.status());
        }
    }

    /**
     * One billed line beside what TMS knows about the shipment it names.
     *
     * @param chargeCode       which charge the line bills (V64); {@code FREIGHT} for a V46 line
     * @param expectedAmount   what <b>this line</b> is compared against (V64): the estimate's
     *                         component of its charge, or for an all-in line the shipment's figure
     *                         less its other lines' components. <b>Null when it cannot be known</b> -
     *                         never zero - and for a penalty, a duplicate or a foreign shipment,
     *                         which are compared with nothing. Shown whether or not the match opened
     *                         anything: within tolerance the comparison is information, not a block
     * @param expectedBasis    what {@code expectedAmount} is: the closed actual or the estimate
     * @param differenceAmount billed minus expected, null when there is no expected figure
     * @param shipmentNumber   null when the line names no shipment, or one this carrier did not run
     */
    public record LineView(
            UUID id,
            int lineNumber,
            UUID tripId,
            String shipmentNumber,
            ChargeCode chargeCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitAmount,
            BigDecimal lineAmount,
            BigDecimal expectedAmount,
            ExpectedBasis expectedBasis,
            BigDecimal actualAmount,
            BigDecimal differenceAmount,
            /** Why the shipper deducts, on a PENALTY line (V66). */
            String penaltyReason,
            com.ebim.tms.settlement.domain.PenaltyReferenceType penaltyReferenceType,
            UUID penaltyReferenceId) {
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
            /** What {@code expectedAmount} is (V64): ACTUAL, ESTIMATED, MIXED; null before V64. */
            ExpectedBasis expectedBasis,
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
            Map<UUID, TripCostLookupPort.TripCostSummary> costs, FiscalView fiscal) {

        // The matcher's own per-line split, so the screen shows the figures a match would raise
        // differences from - computed, never stored, and therefore always against today's costs.
        Map<UUID, FreightMatcher.LineExpectation> expectations = FreightMatcher.lineExpectations(
                SettlementService.matcherLines(invoice, trips),
                SettlementService.matcherCosts(trips, costs, invoice.carrierId()));
        List<LineView> lines = invoice.lines().stream()
                .map(line -> toLineView(line, invoice.carrierId(), trips, costs, expectations.get(line.id())))
                .toList();

        return new CarrierInvoiceView(invoice.id(), invoice.carrierId(), carrierName, invoice.invoiceNumber(),
                invoice.invoiceDate(), invoice.currency(), invoice.totalAmount(), invoice.status(),
                invoice.status().allowedTransitions(), invoice.externalReference(), invoice.receivedAt(),
                invoice.notes(), invoice.version(), lines,
                match == null ? null : new MatchView(match.status(), match.expectedAmount(),
                        match.actualAmount(), match.invoicedAmount(), match.differenceAmount(),
                        match.expectedBasis(), match.toleranceAbsolute(), match.tolerancePercentage(), match.matchedTripCount(),
                        match.unmatchedLineCount(), match.computedAt()),
                discrepancies.stream().map(FreightDiscrepancyView::of).toList(),
                approvals.stream()
                        .map(approval -> new ApprovalView(approval.id(), approval.decision(),
                                approval.decidedBy(), approval.decidedAt(), approval.comment()))
                        .toList(),
                export == null ? null : PayableExportView.of(export, true),
                fiscal);
    }

    private static LineView toLineView(CarrierInvoiceLine line, UUID invoiceCarrierId,
            Map<UUID, TripSettlementLookupPort.TripSettlementSummary> trips,
            Map<UUID, TripCostLookupPort.TripCostSummary> costs, FreightMatcher.LineExpectation expectation) {
        TripSettlementLookupPort.TripSettlementSummary trip =
                line.tripId() == null ? null : trips.get(line.tripId());
        // A shipment this carrier did not run is shown as unnamed, matching how the matcher treats
        // it: comparing an amount against somebody else's work would be worse than showing a gap.
        boolean theirs = trip != null && invoiceCarrierId.equals(trip.carrierId());
        TripCostLookupPort.TripCostSummary cost =
                theirs ? costs.get(line.tripId()) : null;

        BigDecimal expected = expectation == null ? null : expectation.expectedAmount();
        return new LineView(line.id(), line.lineNumber(), line.tripId(),
                theirs ? trip.shipmentNumber() : null,
                line.chargeCode(), line.description(), line.quantity(), line.unitAmount(), line.lineAmount(),
                expected,
                expected == null ? null : expectation.basis(),
                cost == null ? null : cost.actualAmount(),
                // Null, not zero: without an expected figure there is no difference to state.
                expected == null ? null : line.lineAmount().subtract(expected),
                line.penaltyReason(), line.penaltyReferenceType(), line.penaltyReferenceId());
    }
}
