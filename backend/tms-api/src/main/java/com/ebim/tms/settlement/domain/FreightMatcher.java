package com.ebim.tms.settlement.domain;

import com.ebim.tms.settlement.domain.FreightMatchResult.Discrepancy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <h2>Charges, not lines (V64, ADR-024 part 1)</h2>
 *
 * <p>A line says which charge it bills ({@link ChargeCode}). <b>What is billed once is a (shipment,
 * charge) pair</b>: freight and a toll for one shipment on two lines are one shipment billed in two
 * parts and are summed; the same shipment and charge twice is the duplicate. A V46 line has no code
 * and reads as {@link ChargeCode#FREIGHT}, so two uncoded lines of one shipment are still a
 * duplicate, exactly as before.
 *
 * <p><b>The shipment total decides; the components explain.</b> The lines of one shipment are summed
 * and compared, under the tolerance, with the shipment's figure - its closed actual cost when V30
 * closed it, its estimate otherwise, and {@link ExpectedBasis} says which. Only when that total is
 * out of tolerance are the lines compared one by one, each against the cost-breakdown component of
 * its name (an all-in line against what the other lines leave of the shipment's figure), and each
 * difference is raised on its line. Within tolerance nothing is opened: a difference the tolerance
 * accepted must not block an approval line by line. {@link #lineExpectations} gives the same per-line
 * figures to a screen, which shows them either way.
 *
 * <p>The breakdown is the <b>estimate's</b>: TMS records an actual as one amount, with no
 * components, so a component line is always compared with the estimate, and says so.
 *
 * <p>{@link ChargeCode#PENALTY} lines are a deduction, not a cost: they are left out of every
 * comparison, and out of the header total that is compared (ADR-024 part 2, item 6).
 */
public final class FreightMatcher {

    private FreightMatcher() {
    }

    /**
     * One shipment as the matcher needs it.
     *
     * @param expectedAmount      what the rate card produced, or null when nobody estimated it
     * @param actualAmount        what was recorded as spent, or null when nobody recorded it
     * @param currency            the currency the shipment was priced in, or null when it has no
     *                            cost row
     * @param closed              whether V30 closed the cost: the actual is final, and is what the
     *                            invoice is compared against (V64)
     * @param estimatedComponents the estimate's breakdown, by {@code RateComponent} name. Empty when
     *                            there is no estimate - never a map of zeros
     */
    public record TripCostSnapshot(
            UUID tripId, BigDecimal expectedAmount, BigDecimal actualAmount, String currency,
            boolean closed, Map<String, BigDecimal> estimatedComponents) {

        public TripCostSnapshot {
            estimatedComponents = estimatedComponents == null ? Map.of() : Map.copyOf(estimatedComponents);
        }

        /** A shipment whose cost is open and whose estimate has no breakdown - every V46 caller. */
        public TripCostSnapshot(UUID tripId, BigDecimal expectedAmount, BigDecimal actualAmount, String currency) {
            this(tripId, expectedAmount, actualAmount, currency, false, Map.of());
        }

        /** What the invoice is compared against: the closed actual, else the estimate, else null. */
        public BigDecimal comparisonAmount() {
            return closed && actualAmount != null ? actualAmount : expectedAmount;
        }

        /** Which of the two {@link #comparisonAmount} is, or null when it is null. */
        public ExpectedBasis basis() {
            if (closed && actualAmount != null) {
                return ExpectedBasis.ACTUAL;
            }
            return expectedAmount == null ? null : ExpectedBasis.ESTIMATED;
        }
    }

    /**
     * One line of the invoice.
     *
     * @param tripId     the shipment it bills for, or null for an accessorial that names none
     * @param chargeCode which charge it bills; null reads as {@link ChargeCode#FREIGHT}
     */
    public record InvoiceLine(UUID lineId, UUID tripId, BigDecimal amount, String description,
            ChargeCode chargeCode) {

        public InvoiceLine {
            chargeCode = ChargeCode.orFreight(chargeCode);
        }

        /** An all-in line, as every V46 line was. */
        public InvoiceLine(UUID lineId, UUID tripId, BigDecimal amount, String description) {
            this(lineId, tripId, amount, description, ChargeCode.FREIGHT);
        }
    }

    /** What is billed once: one charge of one shipment. */
    public record TripCharge(UUID tripId, ChargeCode chargeCode) {

        public TripCharge {
            chargeCode = ChargeCode.orFreight(chargeCode);
        }
    }

    /**
     * What one line was, or would be, compared against.
     *
     * @param expectedAmount null when it cannot be known - never zero for unknown
     * @param basis          what {@code expectedAmount} is: a component line is always
     *                       {@link ExpectedBasis#ESTIMATED}, because only the estimate has a breakdown
     */
    public record LineExpectation(BigDecimal expectedAmount, ExpectedBasis basis) {
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
     *                         (rounding, a header discount), and the document is what it is.
     *                         Penalty lines are taken out of it before the comparison
     * @param costsByTrip      what TMS knows about each shipment the lines name
     * @param billedElsewhere  (shipment, charge) pairs some <b>other</b> live invoice of this
     *                         company already bills. Empty is a claim - "nothing else bills these"
     *                         - not an absence, so a caller that cannot check must not pass it
     *                         lightly
     */
    public static FreightMatchResult match(String invoiceCurrency, BigDecimal invoicedTotal,
            List<InvoiceLine> lines, Map<UUID, TripCostSnapshot> costsByTrip, Tolerance tolerance,
            Set<TripCharge> billedElsewhere) {

        List<Discrepancy> discrepancies = new ArrayList<>();
        int unmatchedLines = 0;
        BigDecimal penalties = BigDecimal.ZERO;

        // Every (shipment, charge) this invoice has already billed on an earlier line. See the
        // duplicate block below for why a set rather than a counter: the second occurrence is
        // refused, not tallied.
        Set<TripCharge> billedHere = new LinkedHashSet<>();
        // The lines that survive, grouped by shipment in the order the invoice names them.
        Map<UUID, List<InvoiceLine>> linesByTrip = new LinkedHashMap<>();

        for (InvoiceLine line : lines) {
            if (line.chargeCode() == ChargeCode.PENALTY) {
                // A deduction, not a cost: compared with nothing, and taken out of the header.
                penalties = penalties.add(line.amount());
                continue;
            }
            if (line.tripId() == null) {
                unmatchedLines++;
                discrepancies.add(new Discrepancy(DiscrepancyType.UNMATCHED_TRIP, null, line.amount(), null,
                        "Line \"" + line.description() + "\" names no shipment, so there is nothing to"
                                + " compare it against.", line.lineId()));
                continue;
            }
            TripCostSnapshot cost = costsByTrip.get(line.tripId());
            if (cost == null) {
                unmatchedLines++;
                discrepancies.add(new Discrepancy(DiscrepancyType.UNMATCHED_TRIP, null, line.amount(), null,
                        "Line \"" + line.description() + "\" bills a shipment this invoice's carrier"
                                + " did not run, or one that does not exist.", line.lineId()));
                continue;
            }

            // -------------------------------------------------------------- billed twice
            //
            // THE expensive failure this module exists to stop, and the one the database cannot
            // see. uq_carrier_invoice_number stops the same document arriving twice; nothing stops
            // the same *charge of a shipment* being billed twice - on two lines of one invoice, or
            // on two invoices with different numbers.
            //
            // A duplicate line contributes NOTHING to the expected total, and that is the whole
            // point rather than tidiness. Adding the shipment's expected cost once per line made
            // the duplicate pay for itself: expected 1450 billed twice came to an expected 2900
            // against an invoiced 2900, and the invoice matched cleanly with no discrepancy at all.
            // Leaving it out is what makes the header come up over by the duplicated amount.
            //
            // Since V64 the key is (shipment, charge): freight and a toll of one shipment are two
            // halves of one bill, not a duplicate.
            //
            // Raised for a person, not refused outright. A carrier re-billing a shipment after a
            // credit note is legal, and settlement never decides not to pay - it makes somebody
            // look. requireNoOpenDiscrepancies is what turns this into a block on approval.
            TripCharge charge = new TripCharge(line.tripId(), line.chargeCode());
            if (billedElsewhere.contains(charge)) {
                discrepancies.add(new Discrepancy(DiscrepancyType.DUPLICATE_INVOICE,
                        cost.comparisonAmount(), line.amount(), null,
                        "Line \"" + line.description() + "\" bills " + chargeName(line.chargeCode())
                                + " of a shipment another invoice of this company already bills."
                                + " Approving both would pay for it twice.", line.lineId()));
                continue;
            }
            if (!billedHere.add(charge)) {
                discrepancies.add(new Discrepancy(DiscrepancyType.DUPLICATE_INVOICE,
                        cost.comparisonAmount(), line.amount(), null,
                        "Line \"" + line.description() + "\" bills " + chargeName(line.chargeCode())
                                + " of a shipment an earlier line of this same invoice already bills."
                                + " Approving it would pay for it twice.", line.lineId()));
                continue;
            }
            linesByTrip.computeIfAbsent(line.tripId(), ignored -> new ArrayList<>()).add(line);
        }

        BigDecimal expectedTotal = null;
        List<ExpectedBasis> bases = new ArrayList<>();
        int matchedTrips = 0;

        for (Map.Entry<UUID, List<InvoiceLine>> entry : linesByTrip.entrySet()) {
            List<InvoiceLine> tripLines = entry.getValue();
            TripCostSnapshot cost = costsByTrip.get(entry.getKey());
            matchedTrips++;
            // A shipment-level finding is about one line when the shipment has one line.
            UUID onlyLine = tripLines.size() == 1 ? tripLines.get(0).lineId() : null;
            BigDecimal tripInvoiced = sum(tripLines);
            String what = describe(tripLines);

            if (cost.currency() != null && !cost.currency().equals(invoiceCurrency)) {
                // Never converted. Two currencies do not add up, and this product invents no rate.
                discrepancies.add(new Discrepancy(DiscrepancyType.CURRENCY_MISMATCH,
                        cost.comparisonAmount(), tripInvoiced, null,
                        "The shipment was priced in " + cost.currency() + " and this invoice is in "
                                + invoiceCurrency + ". TMS does not convert currencies.", onlyLine));
                continue;
            }

            BigDecimal expected = cost.comparisonAmount();
            if (expected == null) {
                // Absent, not zero. Raised so an auditor is told the line cannot be checked, rather
                // than having it quietly counted as free.
                discrepancies.add(new Discrepancy(DiscrepancyType.MISSING_EXPECTED_COST,
                        null, tripInvoiced, null,
                        "This shipment was never priced, so " + what + " cannot be checked against"
                                + " anything.", onlyLine));
                continue;
            }

            expectedTotal = expectedTotal == null ? expected : expectedTotal.add(expected);
            bases.add(cost.basis());

            if (tolerance.covers(expected, tripInvoiced)) {
                // Within tolerance: the per-component comparison is for the screen, and opens nothing.
                continue;
            }
            discrepancies.addAll(componentDiscrepancies(tripLines, cost, tolerance, tripInvoiced, what));
        }

        BigDecimal actualTotal = sumActuals(costsByTrip, linesByTrip.keySet());

        // Nothing to compare the header against: no line reached a shipment with a price.
        if (expectedTotal == null) {
            return new FreightMatchResult(MatchStatus.UNMATCHABLE, null, actualTotal, invoicedTotal, null,
                    matchedTrips, unmatchedLines, discrepancies, null);
        }

        ExpectedBasis basis = ExpectedBasis.combine(bases);
        // Penalties are a deduction the carrier's document carries; the cost comparison is about
        // what the shipments cost, so they leave the header before it is compared.
        BigDecimal comparedTotal = invoicedTotal.subtract(penalties);
        BigDecimal difference = comparedTotal.subtract(expectedTotal);
        if (!tolerance.covers(expectedTotal, comparedTotal)) {
            discrepancies.add(new Discrepancy(DiscrepancyType.TOTAL_AMOUNT, expectedTotal, comparedTotal,
                    difference,
                    "The invoice totals " + comparedTotal
                            + (penalties.signum() == 0 ? "" : " before penalties")
                            + " against an expected " + expectedTotal + " (" + basisWords(basis) + ", "
                            + (difference.signum() > 0 ? "+" : "") + difference + "), which is outside"
                            + (tolerance.isConfigured() ? " tolerance." : " tolerance - none is configured,"
                                    + " so every difference is reported."), null));
        }

        MatchStatus status = discrepancies.isEmpty() ? MatchStatus.MATCHED : MatchStatus.DISCREPANCY;
        return new FreightMatchResult(status, expectedTotal, actualTotal, invoicedTotal, difference,
                matchedTrips, unmatchedLines, discrepancies, basis);
    }

    /**
     * The differences of a shipment whose total is out of tolerance, raised on the lines that make
     * them.
     *
     * <p>One all-in line is the V46 case and stays exactly as it was: the line against the
     * shipment's figure. Otherwise each line is compared with its component under the same
     * tolerance; when no single line is out (the shipment total drifted by small amounts, or the
     * estimate has no breakdown to split by), one shipment-level difference is raised instead, so an
     * out-of-tolerance shipment never passes without a row a person has to resolve.
     */
    private static List<Discrepancy> componentDiscrepancies(List<InvoiceLine> tripLines,
            TripCostSnapshot cost, Tolerance tolerance, BigDecimal tripInvoiced, String what) {
        List<Discrepancy> found = new ArrayList<>();
        Map<UUID, LineExpectation> expectations = expectationsForTrip(tripLines, cost);
        for (InvoiceLine line : tripLines) {
            LineExpectation expectation = expectations.get(line.lineId());
            BigDecimal expected = expectation == null ? null : expectation.expectedAmount();
            if (expected == null || tolerance.covers(expected, line.amount())) {
                continue;
            }
            BigDecimal lineDifference = line.amount().subtract(expected);
            found.add(new Discrepancy(DiscrepancyType.LINE_AMOUNT, expected, line.amount(), lineDifference,
                    "Line \"" + line.description() + "\" (" + chargeName(line.chargeCode()) + ") was billed "
                            + line.amount() + " against an expected " + expected + " ("
                            + basisWords(expectation.basis()) + ", "
                            + (lineDifference.signum() > 0 ? "+" : "") + lineDifference + ").",
                    line.lineId()));
        }
        if (found.isEmpty()) {
            BigDecimal expected = cost.comparisonAmount();
            BigDecimal difference = tripInvoiced.subtract(expected);
            found.add(new Discrepancy(DiscrepancyType.LINE_AMOUNT, expected, tripInvoiced, difference,
                    "This shipment was billed " + tripInvoiced + " over " + what + " against an expected "
                            + expected + " (" + basisWords(cost.basis()) + ", "
                            + (difference.signum() > 0 ? "+" : "") + difference + ").",
                    tripLines.size() == 1 ? tripLines.get(0).lineId() : null));
        }
        return found;
    }

    /**
     * What each line of an invoice was, or would be, compared against, keyed by line id.
     *
     * <p>The same figures the matcher raises differences from, offered to a screen so it can show
     * the per-component comparison whether or not it opened anything. A line absent from the result
     * is compared with nothing: a penalty, a line naming no shipment or a foreign one, or a
     * duplicate of an earlier line.
     */
    public static Map<UUID, LineExpectation> lineExpectations(List<InvoiceLine> lines,
            Map<UUID, TripCostSnapshot> costsByTrip) {
        Set<TripCharge> seen = new LinkedHashSet<>();
        Map<UUID, List<InvoiceLine>> linesByTrip = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            if (line.chargeCode() == ChargeCode.PENALTY || line.tripId() == null
                    || !costsByTrip.containsKey(line.tripId())
                    || !seen.add(new TripCharge(line.tripId(), line.chargeCode()))) {
                continue;
            }
            linesByTrip.computeIfAbsent(line.tripId(), ignored -> new ArrayList<>()).add(line);
        }
        Map<UUID, LineExpectation> result = new LinkedHashMap<>();
        linesByTrip.forEach((tripId, tripLines) ->
                result.putAll(expectationsForTrip(tripLines, costsByTrip.get(tripId))));
        return result;
    }

    /**
     * Splits one shipment's figure across its lines.
     *
     * <ul>
     *   <li>A component line: the estimate's component of its name ({@link ChargeCode#BASE} with the
     *       tariff's floor and ceiling adjustments). Zero when the estimate has a breakdown that
     *       charges nothing for it - a known zero, the tariff's - and null when there is no
     *       breakdown at all.
     *   <li>The all-in line: the shipment's figure (actual if closed) less what the component lines
     *       are expected to be - "everything else". Null when those are unknown.
     * </ul>
     */
    private static Map<UUID, LineExpectation> expectationsForTrip(List<InvoiceLine> tripLines,
            TripCostSnapshot cost) {
        Map<UUID, LineExpectation> result = new LinkedHashMap<>();
        Map<String, BigDecimal> components = cost.estimatedComponents();
        boolean breakdown = !components.isEmpty();

        BigDecimal componentsExpected = BigDecimal.ZERO;
        boolean componentsKnown = true;
        for (InvoiceLine line : tripLines) {
            if (!line.chargeCode().isComponent()) {
                continue;
            }
            if (!breakdown) {
                componentsKnown = false;
                result.put(line.lineId(), new LineExpectation(null, null));
                continue;
            }
            BigDecimal expected = BigDecimal.ZERO;
            for (String name : line.chargeCode().componentNames()) {
                BigDecimal amount = components.get(name);
                if (amount != null) {
                    expected = expected.add(amount);
                }
            }
            componentsExpected = componentsExpected.add(expected);
            result.put(line.lineId(), new LineExpectation(expected, ExpectedBasis.ESTIMATED));
        }

        for (InvoiceLine line : tripLines) {
            if (line.chargeCode() != ChargeCode.FREIGHT) {
                continue;
            }
            BigDecimal figure = cost.comparisonAmount();
            if (figure == null || !componentsKnown) {
                result.put(line.lineId(), new LineExpectation(null, null));
            } else {
                result.put(line.lineId(), new LineExpectation(figure.subtract(componentsExpected), cost.basis()));
            }
        }
        return result;
    }

    /**
     * What the matched shipments actually cost, or null when none of them recorded it.
     *
     * <p>Reported beside expected and invoiced so an auditor sees all three, and null rather than
     * zero for the same reason as everything else here: nobody recording a cost is not the same as
     * a cost of nothing.
     *
     * <p><b>Once per shipment, however many lines name it.</b> What a shipment cost is a fact about
     * the shipment, not about how many times a carrier billed it. Without this, a duplicated line
     * inflated the actual figure an auditor compares against, which is the same defect as the
     * expected total's.
     */
    private static BigDecimal sumActuals(Map<UUID, TripCostSnapshot> costsByTrip, Set<UUID> tripIds) {
        BigDecimal total = null;
        for (UUID tripId : tripIds) {
            TripCostSnapshot cost = costsByTrip.get(tripId);
            if (cost == null || cost.actualAmount() == null) {
                continue;
            }
            total = total == null ? cost.actualAmount() : total.add(cost.actualAmount());
        }
        return total;
    }

    private static BigDecimal sum(List<InvoiceLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            total = total.add(line.amount());
        }
        return total;
    }

    private static String describe(List<InvoiceLine> tripLines) {
        if (tripLines.size() == 1) {
            return "line \"" + tripLines.get(0).description() + "\"";
        }
        return tripLines.size() + " lines";
    }

    private static String chargeName(ChargeCode code) {
        return code == ChargeCode.FREIGHT ? "the freight" : "the " + code.name() + " charge";
    }

    private static String basisWords(ExpectedBasis basis) {
        if (basis == null) {
            return "no basis";
        }
        return switch (basis) {
            case ACTUAL -> "closed actual cost";
            case ESTIMATED -> "estimate";
            case MIXED -> "actual where closed, estimate elsewhere";
        };
    }
}
