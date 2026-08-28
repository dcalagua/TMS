package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.FreightMatchResult.Discrepancy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * @param invoiceCurrency  the currency on the document
     * @param invoicedTotal    the header total, which is what is compared - not the sum of lines.
     *                         A carrier's header may legitimately differ from its own lines
     *                         (rounding, a header discount), and the document is what it is
     * @param costsByTrip      what TMS knows about each shipment the lines name
     */
    public static FreightMatchResult match(String invoiceCurrency, BigDecimal invoicedTotal,
            List<InvoiceLine> lines, Map<UUID, TripCostSnapshot> costsByTrip, Tolerance tolerance) {

        List<Discrepancy> discrepancies = new ArrayList<>();
        BigDecimal expectedTotal = null;
        int matchedTrips = 0;
        int unmatchedLines = 0;

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
     */
    private static BigDecimal sumActuals(Map<UUID, TripCostSnapshot> costsByTrip, List<InvoiceLine> lines) {
        BigDecimal total = null;
        for (InvoiceLine line : lines) {
            if (line.tripId() == null) {
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
