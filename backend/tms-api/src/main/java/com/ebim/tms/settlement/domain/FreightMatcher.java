package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.FreightMatchResult.Discrepancy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares what a carrier billed with what TMS expected and what the shipments actually cost
 * (migration V46).
 *
 * <p><b>A pure function.</b> No repository, no clock, no randomness - the same shape
 * {@code StopScheduleEngine} and {@code TripCostCalculator} have, and for the same reason: a figure
 * somebody authorised an expenditure against must be reproducible from its inputs a year later,
 * when the shipments have moved on and the tolerance has been rewritten.
 *
 * <h2>The rule this whole class exists to enforce</h2>
 *
 * <p><b>Nothing missing is ever treated as zero.</b> A shipment nobody estimated has no expected
 * figure; reading that as 0.00 would report the entire invoice as an overcharge and send an auditor
 * to argue with a carrier who did nothing wrong. So an invoice with no expected figure at all comes
 * back {@link MatchStatus#UNMATCHABLE}, and individual shipments missing one raise
 * {@link DiscrepancyType#MISSING_EXPECTED_COST} rather than contributing a silent zero.
 *
 * <p>This is the same rule V45 established for delivered quantities, and V46's
 * {@code ck_freight_match_unknown_is_not_matched} refuses to store a violation of it.
 *
 * <h2>The second rule: a shipment is billed once</h2>
 *
 * <p><b>A shipment contributes its expected cost once, however many lines name it.</b> Counting it
 * per line made a duplicate pay for itself - two lines of 1450 against one shipment expected at
 * 1450 produced an expected total of 2900 against an invoiced 2900, and the invoice matched
 * cleanly with no discrepancy raised. The duplicate line is reported as
 * {@link DiscrepancyType#DUPLICATE_INVOICE} and left out of the total, which is what makes the
 * header come up over by exactly the amount that would have been paid twice.
 *
 * <p>Duplicates <em>across</em> invoices need a database and arrive through {@code billedElsewhere};
 * this function still decides what they mean. Neither kind is refused outright - a carrier
 * re-billing after a credit note is legal, and the block lives in
 * {@code SettlementService.requireNoOpenDiscrepancies} where a person can lift it.
 */
public final class FreightMatcher {

    private FreightMatcher() {
    }

    /**
     * One shipment as the matcher needs it.
     *
     * @param expectedAmount what the rate card produced, or null when nobody estimated it
     * @param actualAmount   what was recorded as spent, or null when nobody recorded it
     * @param currency       the currency the shipment was priced in, or null when it has no cost row
     */
    public record TripCostSnapshot(
            UUID tripId, BigDecimal expectedAmount, BigDecimal actualAmount, String currency) {
    }

    /**
     * One line of the invoice.
     *
     * @param tripId the shipment it bills for, or null for an accessorial that names none
     */
    public record InvoiceLine(UUID lineId, UUID tripId, BigDecimal amount, String description) {
    }

    /**
     * Matches an invoice that is the only one billing its shipments.
     *
     * <p>Kept for callers with nothing to compare against - tests, and any future caller that
     * genuinely knows no other invoice exists. {@link #match(String, BigDecimal, List, Map,
     * Tolerance, Set)} is what {@code SettlementService} uses, because it is the only one that can
     * see the second invoice.
     */
    public static FreightMatchResult match(String invoiceCurrency, BigDecimal invoicedTotal,
            List<InvoiceLine> lines, Map<UUID, TripCostSnapshot> costsByTrip, Tolerance tolerance) {
        return match(invoiceCurrency, invoicedTotal, lines, costsByTrip, tolerance, Set.of());
    }

    /**
     * @param invoiceCurrency  the currency on the document
     * @param invoicedTotal    the header total, which is what is compared - not the sum of lines.
     *                         A carrier's header may legitimately differ from its own lines
     *                         (rounding, a header discount), and the document is what it is
     * @param costsByTrip      what TMS knows about each shipment the lines name
     * @param billedElsewhere  shipments some <b>other</b> live invoice of this company already
     *                         bills. Empty is a claim - "nothing else bills these" - not an
     *                         absence, so a caller that cannot check must not pass it lightly
     */
    public static FreightMatchResult match(String invoiceCurrency, BigDecimal invoicedTotal,
            List<InvoiceLine> lines, Map<UUID, TripCostSnapshot> costsByTrip, Tolerance tolerance,
            Set<UUID> billedElsewhere) {

        List<Discrepancy> discrepancies = new ArrayList<>();
        BigDecimal expectedTotal = null;
        int matchedTrips = 0;
        int unmatchedLines = 0;

        // Every shipment this invoice has already billed on an earlier line. See the duplicate
        // block below for why a set rather than a counter: the second occurrence is refused, not
        // tallied.
        Set<UUID> billedHere = new LinkedHashSet<>();

        for (InvoiceLine line : lines) {
            if (line.tripId() == null) {
                unmatchedLines++;
                discrepancies.add(new Discrepancy(DiscrepancyType.UNMATCHED_TRIP, null, line.amount(), null,
                        "Line \"" + line.description() + "\" names no shipment, so there is nothing to"
                                + " compare it against."));
                continue;
            }
            TripCostSnapshot cost = costsByTrip.get(line.tripId());
            if (cost == null) {
                unmatchedLines++;
                discrepancies.add(new Discrepancy(DiscrepancyType.UNMATCHED_TRIP, null, line.amount(), null,
                        "Line \"" + line.description() + "\" bills a shipment this invoice's carrier"
                                + " did not run, or one that does not exist."));
                continue;
            }

            // -------------------------------------------------------------- billed twice
            //
            // THE expensive failure this module exists to stop, and the one the database cannot
            // see. uq_carrier_invoice_number stops the same document arriving twice; nothing stops
            // the same *shipment* being billed twice - on two lines of one invoice, or on two
            // invoices with different numbers. DiscrepancyType.DUPLICATE_INVOICE was declared for
            // exactly this and, until now, was raised by nothing.
            //
            // A duplicate line contributes NOTHING to the expected total, and that is the whole
            // point rather than tidiness. Adding the shipment's expected cost once per line made
            // the duplicate pay for itself: expected 1450 billed twice came to an expected 2900
            // against an invoiced 2900, and the invoice matched cleanly with no discrepancy at all.
            // Leaving it out is what makes the header come up over by the duplicated amount.
            //
            // Raised for a person, not refused outright. A carrier re-billing a shipment after a
            // credit note is legal, and settlement never decides not to pay - it makes somebody
            // look. requireNoOpenDiscrepancies is what turns this into a block on approval.
            if (billedElsewhere.contains(line.tripId())) {
                discrepancies.add(new Discrepancy(DiscrepancyType.DUPLICATE_INVOICE,
                        cost.expectedAmount(), line.amount(), null,
                        "Line \"" + line.description() + "\" bills a shipment another invoice of this"
                                + " company already bills. Approving both would pay for it twice."));
                continue;
            }
            if (!billedHere.add(line.tripId())) {
                discrepancies.add(new Discrepancy(DiscrepancyType.DUPLICATE_INVOICE,
                        cost.expectedAmount(), line.amount(), null,
                        "Line \"" + line.description() + "\" bills a shipment an earlier line of this"
                                + " same invoice already bills. Approving it would pay for it twice."));
                continue;
            }

            matchedTrips++;

            if (cost.currency() != null && !cost.currency().equals(invoiceCurrency)) {
                // Never converted. Two currencies do not add up, and this product invents no rate.
                discrepancies.add(new Discrepancy(DiscrepancyType.CURRENCY_MISMATCH,
                        cost.expectedAmount(), line.amount(), null,
                        "The shipment was priced in " + cost.currency() + " and this invoice is in "
                                + invoiceCurrency + ". TMS does not convert currencies."));
                continue;
            }

            if (cost.expectedAmount() == null) {
                // Absent, not zero. Raised so an auditor is told the line cannot be checked, rather
                // than having it quietly counted as free.
                discrepancies.add(new Discrepancy(DiscrepancyType.MISSING_EXPECTED_COST,
                        null, line.amount(), null,
                        "This shipment was never priced, so line \"" + line.description()
                                + "\" cannot be checked against anything."));
                continue;
            }

            expectedTotal = expectedTotal == null
                    ? cost.expectedAmount()
                    : expectedTotal.add(cost.expectedAmount());

            if (!tolerance.covers(cost.expectedAmount(), line.amount())) {
                BigDecimal lineDifference = line.amount().subtract(cost.expectedAmount());
                discrepancies.add(new Discrepancy(DiscrepancyType.LINE_AMOUNT,
                        cost.expectedAmount(), line.amount(), lineDifference,
                        "Line \"" + line.description() + "\" was billed " + line.amount()
                                + " against an expected " + cost.expectedAmount() + " ("
                                + (lineDifference.signum() > 0 ? "+" : "") + lineDifference + ")."));
            }
        }

        BigDecimal actualTotal = sumActuals(costsByTrip, lines);

        // Nothing to compare the header against: no line reached a shipment with a price.
        if (expectedTotal == null) {
            return new FreightMatchResult(MatchStatus.UNMATCHABLE, null, actualTotal, invoicedTotal, null,
                    matchedTrips, unmatchedLines, discrepancies);
        }

        BigDecimal difference = invoicedTotal.subtract(expectedTotal);
        if (!tolerance.covers(expectedTotal, invoicedTotal)) {
            discrepancies.add(new Discrepancy(DiscrepancyType.TOTAL_AMOUNT, expectedTotal, invoicedTotal,
                    difference,
                    "The invoice totals " + invoicedTotal + " against an expected " + expectedTotal + " ("
                            + (difference.signum() > 0 ? "+" : "") + difference + "), which is outside"
                            + (tolerance.isConfigured() ? " tolerance." : " tolerance - none is configured,"
                                    + " so every difference is reported.")));
        }

        MatchStatus status = discrepancies.isEmpty() ? MatchStatus.MATCHED : MatchStatus.DISCREPANCY;
        return new FreightMatchResult(status, expectedTotal, actualTotal, invoicedTotal, difference,
                matchedTrips, unmatchedLines, discrepancies);
    }

    /**
     * What the matched shipments actually cost, or null when none of them recorded it.
     *
     * <p>Reported beside expected and invoiced so an auditor sees all three, and null rather than
     * zero for the same reason as everything else here: nobody recording a cost is not the same as
     * a cost of nothing.
     *
     * <p><b>Once per shipment, however many lines name it.</b> This walks the lines rather than the
     * cost map because only the lines say which shipments the invoice is about - but what a
     * shipment cost is a fact about the shipment, not about how many times a carrier billed it, so
     * a trip already counted is skipped. Without this, a duplicated line inflated the actual figure
     * an auditor compares against, which is the same defect as the expected total's.
     */
    private static BigDecimal sumActuals(Map<UUID, TripCostSnapshot> costsByTrip, List<InvoiceLine> lines) {
        BigDecimal total = null;
        Set<UUID> counted = new LinkedHashSet<>();
        for (InvoiceLine line : lines) {
            if (line.tripId() == null || !counted.add(line.tripId())) {
                continue;
            }
            TripCostSnapshot cost = costsByTrip.get(line.tripId());
            if (cost == null || cost.actualAmount() == null) {
                continue;
            }
            total = total == null ? cost.actualAmount() : total.add(cost.actualAmount());
        }
        return total;
    }
}
