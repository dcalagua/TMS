package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.FreightMatchResult.Discrepancy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares a credit or debit note with the invoice it corrects (V66, ADR-024 part 2, item 5).
 *
 * <p>A pure function, like {@link FreightMatcher}. A note is not compared with the shipments'
 * cost the way an invoice is - a credit of 100 against a shipment that cost 1450 is not "1350
 * under" - but with what it corrects:
 *
 * <ul>
 *   <li>Every line names a shipment the referenced invoice bills; another is
 *       {@link DiscrepancyType#UNMATCHED_TRIP}.
 *   <li>A <b>credit note</b> cannot take back more than remains of the invoice: its comparable amount
 *       (subtotal when broken down) against the invoice's, less the other live credit notes and plus
 *       the approved debit notes. Above it is {@link DiscrepancyType#TOTAL_AMOUNT}.
 *   <li>A <b>debit note</b> adds to what the shipment was billed: the invoice's lines for the
 *       shipment plus this note's are compared, under the tolerance, with the shipment's figure (actual
 *       when closed, else the estimate). Out of tolerance is {@link DiscrepancyType#LINE_AMOUNT}; no
 *       figure is {@link DiscrepancyType#MISSING_EXPECTED_COST}, never a zero.
 *   <li>Penalty lines are listed for the approver, as on an invoice.
 * </ul>
 */
public final class NoteMatcher {

    private NoteMatcher() {
    }

    /**
     * The invoice a note corrects, as the note needs it.
     *
     * @param remainingComparable what a credit note may still take back: the invoice's comparable
     *                            amount less other live credit notes plus approved debit notes
     * @param billedByTrip        the invoice's non-penalty line amounts, per shipment
     */
    public record ReferencedInvoice(String invoiceNumber, String currency, BigDecimal remainingComparable,
            Map<UUID, BigDecimal> billedByTrip) {

        public ReferencedInvoice {
            billedByTrip = Map.copyOf(billedByTrip);
        }
    }

    public static FreightMatchResult match(DocumentType type, String currency, BigDecimal noteComparable,
            List<FreightMatcher.InvoiceLine> lines, ReferencedInvoice referenced,
            Map<UUID, FreightMatcher.TripCostSnapshot> costsByTrip, Tolerance tolerance) {
        if (!type.isNote()) {
            throw new IllegalArgumentException("only a credit or debit note is matched against an invoice");
        }
        List<Discrepancy> discrepancies = new ArrayList<>();
        int unmatched = 0;
        Map<UUID, BigDecimal> noteByTrip = new LinkedHashMap<>();

        if (!referenced.currency().equals(currency)) {
            discrepancies.add(new Discrepancy(DiscrepancyType.CURRENCY_MISMATCH, null, noteComparable, null,
                    "This note is in " + currency + " and invoice " + referenced.invoiceNumber() + " is in "
                            + referenced.currency() + ". TMS does not convert currencies.", null));
        }

        for (FreightMatcher.InvoiceLine line : lines) {
            if (line.chargeCode() == ChargeCode.PENALTY) {
                discrepancies.add(FreightMatcher.penaltyDiscrepancy(line));
                continue;
            }
            if (line.tripId() == null || !referenced.billedByTrip().containsKey(line.tripId())) {
                unmatched++;
                discrepancies.add(new Discrepancy(DiscrepancyType.UNMATCHED_TRIP, null, line.amount(), null,
                        "Line \"" + line.description() + "\" names "
                                + (line.tripId() == null ? "no shipment" : "a shipment invoice "
                                        + referenced.invoiceNumber() + " does not bill")
                                + ", so this note does not correct anything on it.", line.lineId()));
                continue;
            }
            noteByTrip.merge(line.tripId(), line.amount(), BigDecimal::add);
        }

        if (type == DocumentType.CREDIT_NOTE) {
            BigDecimal remaining = referenced.remainingComparable();
            BigDecimal difference = noteComparable.subtract(remaining);
            if (noteComparable.compareTo(remaining) > 0) {
                discrepancies.add(new Discrepancy(DiscrepancyType.TOTAL_AMOUNT, remaining, noteComparable, difference,
                        "This credit note takes back " + noteComparable + " and only " + remaining
                                + " remains of invoice " + referenced.invoiceNumber() + ".", null));
            }
            MatchStatus status = discrepancies.isEmpty() ? MatchStatus.MATCHED : MatchStatus.DISCREPANCY;
            return new FreightMatchResult(status, remaining, null, noteComparable, difference, noteByTrip.size(),
                    unmatched, discrepancies, null);
        }

        // Debit note: the shipment billed again, compared with what it should have cost.
        BigDecimal headroom = null;
        List<ExpectedBasis> bases = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : noteByTrip.entrySet()) {
            FreightMatcher.TripCostSnapshot cost = costsByTrip.get(entry.getKey());
            BigDecimal billedBefore = referenced.billedByTrip().get(entry.getKey());
            BigDecimal combined = billedBefore.add(entry.getValue());
            BigDecimal expected = cost == null ? null : cost.comparisonAmount();
            if (expected == null) {
                discrepancies.add(new Discrepancy(DiscrepancyType.MISSING_EXPECTED_COST, null, combined, null,
                        "This shipment was never priced, so the debit note cannot be checked against anything.",
                        null));
                continue;
            }
            bases.add(cost.basis());
            BigDecimal room = expected.subtract(billedBefore);
            headroom = headroom == null ? room : headroom.add(room);
            if (!tolerance.covers(expected, combined)) {
                BigDecimal difference = combined.subtract(expected);
                discrepancies.add(new Discrepancy(DiscrepancyType.LINE_AMOUNT, expected, combined, difference,
                        "With this debit note the shipment is billed " + combined + " against an expected "
                                + expected + " (" + (difference.signum() > 0 ? "+" : "") + difference + ").",
                        null));
            }
        }
        if (headroom == null) {
            return new FreightMatchResult(MatchStatus.UNMATCHABLE, null, null, noteComparable, null,
                    noteByTrip.size(), unmatched, discrepancies, null);
        }
        MatchStatus status = discrepancies.isEmpty() ? MatchStatus.MATCHED : MatchStatus.DISCREPANCY;
        return new FreightMatchResult(status, headroom, null, noteComparable, noteComparable.subtract(headroom),
                noteByTrip.size(), unmatched, discrepancies, ExpectedBasis.combine(bases));
    }
}
