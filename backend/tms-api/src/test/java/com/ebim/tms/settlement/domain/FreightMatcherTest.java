package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.FreightMatcher.InvoiceLine;
import com.ebim.tms.settlement.domain.FreightMatcher.TripCostSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Freight matching (migration V46).
 *
 * <p>A pure function, so all of this runs without a database - which is the point. A figure somebody
 * authorised an expenditure against must be reproducible from its inputs a year later, and a test
 * that needs a container to check the arithmetic is a test nobody runs while changing it.
 *
 * <p>The nest that matters most is {@link UnknownIsNotZero}. Everything else here is arithmetic;
 * that one is the rule the module's honesty rests on.
 */
class FreightMatcherTest {

    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID OTHER_TRIP = UUID.randomUUID();
    private static final Tolerance THREE_PERCENT =
            new Tolerance(null, new BigDecimal("3"));

    private static InvoiceLine line(UUID tripId, String amount) {
        return new InvoiceLine(UUID.randomUUID(), tripId, new BigDecimal(amount), "Linehaul");
    }

    private static Map<UUID, TripCostSnapshot> priced(UUID tripId, String expected) {
        return Map.of(tripId, new TripCostSnapshot(tripId, new BigDecimal(expected), null, "PEN"));
    }

    @Nested
    @DisplayName("within tolerance")
    class WithinTolerance {

        /** The brief's first worked example. */
        @Test
        @DisplayName("expected 1450, invoiced 1480, tolerance 3% -> MATCHED")
        void thirtyOverOnFourteenFiftyIsWithinThreePercent() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1480"),
                    List.of(line(TRIP, "1480")), priced(TRIP, "1450"), THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.discrepancies()).isEmpty();
            assertThat(result.differenceAmount()).isEqualByComparingTo("30");
        }

        /** The brief's second worked example. */
        @Test
        @DisplayName("expected 1450, invoiced 1800, tolerance 3% -> DISCREPANCY")
        void threeFiftyOverIsOutsideThreePercent() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1800"),
                    List.of(line(TRIP, "1800")), priced(TRIP, "1450"), THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .contains(DiscrepancyType.TOTAL_AMOUNT, DiscrepancyType.LINE_AMOUNT);
        }

        /**
         * An undercharge is treated exactly like an overcharge. A carrier billing less than agreed
         * is as much a sign the two systems disagree as one billing more, and a freight audit that
         * only looked upward would miss every case where TMS is the one that is wrong.
         */
        @Test
        @DisplayName("an undercharge outside tolerance is a discrepancy too")
        void underchargeIsAlsoADiscrepancy() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("900"),
                    List.of(line(TRIP, "900")), priced(TRIP, "1450"), THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.differenceAmount()).isEqualByComparingTo("-550");
        }

        @Test
        @DisplayName("an exact match needs no tolerance at all")
        void exactMatchNeedsNoTolerance() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1450"),
                    List.of(line(TRIP, "1450")), priced(TRIP, "1450"), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        }

        /**
         * No tolerance configured means no tolerance - the safe default. A company that has not said
         * what it will accept has not authorised anything.
         */
        @Test
        @DisplayName("with no tolerance configured, any difference is a discrepancy")
        void noToleranceMeansNoTolerance() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1450.01"),
                    List.of(line(TRIP, "1450.01")), priced(TRIP, "1450"), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
        }

        /**
         * The absolute floor. 3% of a small invoice is pennies, so without it every rounding
         * difference becomes a discrepancy queue nobody reads.
         */
        @Test
        @DisplayName("an absolute bound admits a difference the percentage would refuse")
        void absoluteBoundCoversSmallInvoices() {
            Tolerance withFloor = new Tolerance(new BigDecimal("5.00"), new BigDecimal("3"));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("44"),
                    List.of(line(TRIP, "44")), priced(TRIP, "40"), withFloor);

            // 4 is 10% of 40 - outside the percentage - but inside the 5.00 floor.
            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        }
    }

    @Nested
    @DisplayName("unknown is never zero")
    class UnknownIsNotZero {

        /**
         * <b>The rule the module's honesty rests on.</b> Reading a missing estimate as 0.00 would
         * report the entire invoice as an overcharge and send an auditor to argue with a carrier
         * who did nothing wrong.
         */
        @Test
        @DisplayName("an invoice whose shipments were never priced is UNMATCHABLE, not a discrepancy")
        void neverPricedIsUnmatchable() {
            Map<UUID, TripCostSnapshot> unpriced =
                    Map.of(TRIP, new TripCostSnapshot(TRIP, null, null, null));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1800"),
                    List.of(line(TRIP, "1800")), unpriced, THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHABLE);
            // Null, never zero - and so no difference either.
            assertThat(result.expectedAmount()).isNull();
            assertThat(result.differenceAmount()).isNull();
        }

        @Test
        @DisplayName("and it says so, rather than passing the line over in silence")
        void missingCostIsReported() {
            Map<UUID, TripCostSnapshot> unpriced =
                    Map.of(TRIP, new TripCostSnapshot(TRIP, null, null, null));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1800"),
                    List.of(line(TRIP, "1800")), unpriced, THREE_PERCENT);

            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactly(DiscrepancyType.MISSING_EXPECTED_COST);
        }

        @Test
        @DisplayName("a shipment with no recorded actual contributes nothing, not zero")
        void missingActualIsNull() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1450"),
                    List.of(line(TRIP, "1450")), priced(TRIP, "1450"), THREE_PERCENT);

            assertThat(result.actualAmount()).isNull();
        }

        @Test
        @DisplayName("a priced line beside an unpriced one still totals only what is known")
        void partialKnowledgeSumsOnlyTheKnown() {
            Map<UUID, TripCostSnapshot> mixed = Map.of(
                    TRIP, new TripCostSnapshot(TRIP, new BigDecimal("1000"), null, "PEN"),
                    OTHER_TRIP, new TripCostSnapshot(OTHER_TRIP, null, null, null));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2000"),
                    List.of(line(TRIP, "1000"), line(OTHER_TRIP, "1000")), mixed, THREE_PERCENT);

            // 1000, not 1000 + 0. The unpriced shipment is reported, not counted.
            assertThat(result.expectedAmount()).isEqualByComparingTo("1000");
            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .contains(DiscrepancyType.MISSING_EXPECTED_COST);
        }
    }

    @Nested
    @DisplayName("what cannot be compared")
    class Uncomparable {

        @Test
        @DisplayName("a line naming no shipment is an unmatched trip, not a price difference")
        void lineWithoutATripIsUnmatched() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("200"),
                    List.of(line(null, "200")), Map.of(), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHABLE);
            assertThat(result.unmatchedLineCount()).isEqualTo(1);
            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactly(DiscrepancyType.UNMATCHED_TRIP);
        }

        @Test
        @DisplayName("a line billing a shipment this carrier did not run is unmatched")
        void lineNamingAnUnknownTripIsUnmatched() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("200"),
                    List.of(line(OTHER_TRIP, "200")), priced(TRIP, "200"), Tolerance.NONE);

            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactly(DiscrepancyType.UNMATCHED_TRIP);
        }

        /** Two currencies do not add up, and this product invents no FX rate. */
        @Test
        @DisplayName("a shipment priced in another currency is a mismatch, never a conversion")
        void currencyIsNeverConverted() {
            Map<UUID, TripCostSnapshot> inDollars =
                    Map.of(TRIP, new TripCostSnapshot(TRIP, new BigDecimal("400"), null, "USD"));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1450"),
                    List.of(line(TRIP, "1450")), inDollars, THREE_PERCENT);

            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactly(DiscrepancyType.CURRENCY_MISMATCH);
            // Nothing was added to the expected total from a currency that cannot be added.
            assertThat(result.expectedAmount()).isNull();
            assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHABLE);
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        /** No clock, no repository, no randomness. The same inputs give the same verdict, always. */
        @Test
        @DisplayName("the same invoice matches the same way twice")
        void isDeterministic() {
            List<InvoiceLine> lines = List.of(line(TRIP, "1480"));
            Map<UUID, TripCostSnapshot> costs = priced(TRIP, "1450");

            assertThat(FreightMatcher.match("PEN", new BigDecimal("1480"), lines, costs, THREE_PERCENT))
                    .isEqualTo(FreightMatcher.match("PEN", new BigDecimal("1480"), lines, costs, THREE_PERCENT));
        }

        /** Scale must not decide a verdict: 30.0 and 30.00 are one amount. */
        @Test
        @DisplayName("trailing zeros do not change the answer")
        void scaleDoesNotMatter() {
            FreightMatchResult plain = FreightMatcher.match("PEN", new BigDecimal("1450"),
                    List.of(line(TRIP, "1450")), priced(TRIP, "1450"), Tolerance.NONE);
            FreightMatchResult scaled = FreightMatcher.match("PEN", new BigDecimal("1450.00"),
                    List.of(line(TRIP, "1450.00")), priced(TRIP, "1450.000"), Tolerance.NONE);

            assertThat(plain.status()).isEqualTo(scaled.status()).isEqualTo(MatchStatus.MATCHED);
        }
    }

    /**
     * A shipment is billed once.
     *
     * <p>The expensive failure, and the one the database cannot see.
     * {@code uq_carrier_invoice_number} stops the same document arriving twice and says nothing
     * about the same shipment appearing twice - on one invoice, or on two with different numbers.
     *
     * <p>{@link #twoLinesForOneShipmentUsedToMatchCleanly} is the regression that motivated all of
     * this: before the duplicate rule, an invoice billing one shipment twice at its correct price
     * came out {@code MATCHED} with an empty discrepancy list, because the expected total was
     * accumulated per line and so grew to meet the inflated invoice exactly.
     */
    @Nested
    @DisplayName("a shipment is billed once")
    class BilledOnce {

        @Test
        @DisplayName("two lines for one shipment: the duplicate is reported, not silently matched")
        void twoLinesForOneShipmentUsedToMatchCleanly() {
            // One shipment priced at 1450, billed twice at 1450. The header agrees with itself.
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2900"),
                    List.of(line(TRIP, "1450"), line(TRIP, "1450")), priced(TRIP, "1450"),
                    THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .contains(DiscrepancyType.DUPLICATE_INVOICE);
            // The shipment counted once, so the header is over by exactly the duplicated amount.
            assertThat(result.expectedAmount()).isEqualByComparingTo("1450");
            assertThat(result.differenceAmount()).isEqualByComparingTo("1450");
            assertThat(result.matchedTripCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("and the total difference is raised beside it, so the money is visible")
        void theHeaderIsOverByTheDuplicatedAmount() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2900"),
                    List.of(line(TRIP, "1450"), line(TRIP, "1450")), priced(TRIP, "1450"),
                    THREE_PERCENT);

            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactlyInAnyOrder(DiscrepancyType.DUPLICATE_INVOICE,
                            DiscrepancyType.TOTAL_AMOUNT);
        }

        /**
         * A re-bill of a single shipment leaves nothing comparable, so the verdict is
         * {@link MatchStatus#UNMATCHABLE} rather than {@code DISCREPANCY} - the same answer this
         * matcher gives for any invoice whose lines all failed to reach a priced shipment. It is
         * not a weaker outcome: {@code SettlementService} routes everything that is not
         * {@code MATCHED} to {@code InvoiceStatus.DISCREPANCY}, and the discrepancy row is what
         * blocks the approval.
         */
        @Test
        @DisplayName("a shipment another invoice already bills is a duplicate too")
        void billedOnAnotherInvoice() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1450"),
                    List.of(line(TRIP, "1450")), priced(TRIP, "1450"), THREE_PERCENT,
                    Set.of(new FreightMatcher.TripCharge(TRIP, ChargeCode.FREIGHT)));

            assertThat(result.status()).isNotEqualTo(MatchStatus.MATCHED);
            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .contains(DiscrepancyType.DUPLICATE_INVOICE);
            // Nothing left to compare the header against: the only line was refused.
            assertThat(result.expectedAmount()).isNull();
        }

        /** Beside a clean line, the re-bill shows up as the money it is. */
        @Test
        @DisplayName("a re-billed shipment beside a good one leaves the header over by its amount")
        void billedOnAnotherInvoiceBesideACleanLine() {
            Map<UUID, TripCostSnapshot> costs = Map.of(
                    TRIP, new TripCostSnapshot(TRIP, new BigDecimal("1450"), null, "PEN"),
                    OTHER_TRIP, new TripCostSnapshot(OTHER_TRIP, new BigDecimal("550"), null, "PEN"));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2000"),
                    List.of(line(TRIP, "1450"), line(OTHER_TRIP, "550")), costs, THREE_PERCENT,
                    Set.of(new FreightMatcher.TripCharge(TRIP, ChargeCode.FREIGHT)));

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .containsExactlyInAnyOrder(DiscrepancyType.DUPLICATE_INVOICE,
                            DiscrepancyType.TOTAL_AMOUNT);
            assertThat(result.expectedAmount()).isEqualByComparingTo("550");
            assertThat(result.differenceAmount()).isEqualByComparingTo("1450");
        }

        /** What a shipment cost is a fact about the shipment, not about how often it was billed. */
        @Test
        @DisplayName("the actual cost is counted once as well")
        void actualIsNotDoubleCounted() {
            Map<UUID, TripCostSnapshot> costs = Map.of(TRIP,
                    new TripCostSnapshot(TRIP, new BigDecimal("1450"), new BigDecimal("1400"), "PEN"));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2900"),
                    List.of(line(TRIP, "1450"), line(TRIP, "1450")), costs, THREE_PERCENT);

            assertThat(result.actualAmount()).isEqualByComparingTo("1400");
        }

        /** The ordinary case must not have moved. Two shipments are two shipments. */
        @Test
        @DisplayName("two lines for two shipments still add up")
        void distinctShipmentsStillSum() {
            Map<UUID, TripCostSnapshot> costs = Map.of(
                    TRIP, new TripCostSnapshot(TRIP, new BigDecimal("1450"), null, "PEN"),
                    OTHER_TRIP, new TripCostSnapshot(OTHER_TRIP, new BigDecimal("550"), null, "PEN"));

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2000"),
                    List.of(line(TRIP, "1450"), line(OTHER_TRIP, "550")), costs, THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.expectedAmount()).isEqualByComparingTo("2000");
            assertThat(result.matchedTripCount()).isEqualTo(2);
        }

        /** Accessorials name no shipment. Several of them are not duplicates of each other. */
        @Test
        @DisplayName("lines naming no shipment are never duplicates of one another")
        void linesWithoutATripAreNotDuplicates() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1550"),
                    List.of(line(TRIP, "1450"), line(null, "50"), line(null, "50")),
                    priced(TRIP, "1450"), THREE_PERCENT);

            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .doesNotContain(DiscrepancyType.DUPLICATE_INVOICE);
            assertThat(result.unmatchedLineCount()).isEqualTo(2);
        }
    }

    // ======================================================================== V64 · ADR-024 part 1

    private static InvoiceLine coded(UUID lineId, UUID tripId, String amount, ChargeCode code) {
        return new InvoiceLine(lineId, tripId, new BigDecimal(amount), code.name().toLowerCase(), code);
    }

    /** Estimate 1000 = BASE 900 + MINIMUM_ADJUSTMENT 0 + FUEL 80 + TOLL 20; open unless closed. */
    private static TripCostSnapshot broken(UUID tripId, String actual, boolean closed) {
        return new TripCostSnapshot(tripId, new BigDecimal("1000"), actual == null ? null : new BigDecimal(actual),
                "PEN", closed, Map.of(
                        "BASE", new BigDecimal("900"),
                        "MINIMUM_ADJUSTMENT", new BigDecimal("0"),
                        "FUEL_SURCHARGE", new BigDecimal("80"),
                        "TOLL", new BigDecimal("20")));
    }

    /**
     * Several lines per shipment, charge codes, and what an invoice is compared against
     * (V64, ADR-024 part 1, item 5). A table of cases: one row per rule the matcher now holds.
     */
    @Nested
    @DisplayName("charge codes and the per-component comparison (V64)")
    class ChargeCodes {

        private final UUID base = UUID.randomUUID();
        private final UUID fuel = UUID.randomUUID();
        private final UUID toll = UUID.randomUUID();

        @Test
        @DisplayName("freight and toll lines of one shipment are summed, not a duplicate")
        void differentChargesOfOneShipmentAreSummed() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1000"),
                    List.of(coded(base, TRIP, "980", ChargeCode.FREIGHT), coded(toll, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.discrepancies()).isEmpty();
            assertThat(result.matchedTripCount()).isEqualTo(1);
            assertThat(result.expectedAmount()).isEqualByComparingTo("1000");
            assertThat(result.expectedBasis()).isEqualTo(ExpectedBasis.ESTIMATED);
        }

        @Test
        @DisplayName("the same shipment and charge twice on one invoice is the duplicate, on its line")
        void sameChargeTwiceIsADuplicate() {
            UUID second = UUID.randomUUID();
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1020"),
                    List.of(coded(base, TRIP, "1000", ChargeCode.FREIGHT), coded(toll, TRIP, "20", ChargeCode.TOLL),
                            coded(second, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)), new Tolerance(new BigDecimal("50"), null));

            assertThat(result.discrepancies())
                    .filteredOn(d -> d.type() == DiscrepancyType.DUPLICATE_INVOICE)
                    .singleElement()
                    .satisfies(d -> assertThat(d.lineId()).isEqualTo(second));
        }

        @Test
        @DisplayName("a charge another invoice bills is a duplicate; a different charge of that shipment is not")
        void duplicateAcrossInvoicesIsPerCharge() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1000"),
                    List.of(coded(base, TRIP, "980", ChargeCode.FREIGHT), coded(toll, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)), Tolerance.NONE,
                    Set.of(new FreightMatcher.TripCharge(TRIP, ChargeCode.TOLL)));

            assertThat(result.discrepancies())
                    .filteredOn(d -> d.type() == DiscrepancyType.DUPLICATE_INVOICE)
                    .singleElement()
                    .satisfies(d -> assertThat(d.lineId()).isEqualTo(toll));
        }

        @Test
        @DisplayName("out of tolerance: each component out is raised on its own line")
        void outOfTolerancePerComponentWithLineId() {
            // BASE 900 billed 900, FUEL 80 billed 200, TOLL 20 billed 20: 1120 against 1000.
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1120"),
                    List.of(coded(base, TRIP, "900", ChargeCode.BASE), coded(fuel, TRIP, "200", ChargeCode.FUEL_SURCHARGE),
                            coded(toll, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)), THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.discrepancies())
                    .filteredOn(d -> d.type() == DiscrepancyType.LINE_AMOUNT)
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.lineId()).isEqualTo(fuel);
                        assertThat(d.expectedAmount()).isEqualByComparingTo("80");
                        assertThat(d.differenceAmount()).isEqualByComparingTo("120");
                        assertThat(d.detail()).contains("FUEL_SURCHARGE").contains("estimate");
                    });
            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::type)
                    .contains(DiscrepancyType.TOTAL_AMOUNT);
        }

        @Test
        @DisplayName("within tolerance: a component difference opens nothing")
        void withinToleranceOpensNothing() {
            // FUEL billed 100 against 80, but the shipment is 1020 against 1000: within 3%.
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1020"),
                    List.of(coded(base, TRIP, "900", ChargeCode.BASE), coded(fuel, TRIP, "100", ChargeCode.FUEL_SURCHARGE),
                            coded(toll, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)), THREE_PERCENT);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.discrepancies()).isEmpty();
            // ...and the screen still sees the comparison.
            Map<UUID, FreightMatcher.LineExpectation> expectations = FreightMatcher.lineExpectations(
                    List.of(coded(base, TRIP, "900", ChargeCode.BASE), coded(fuel, TRIP, "100", ChargeCode.FUEL_SURCHARGE),
                            coded(toll, TRIP, "20", ChargeCode.TOLL)),
                    Map.of(TRIP, broken(TRIP, null, false)));
            assertThat(expectations.get(fuel).expectedAmount()).isEqualByComparingTo("80");
            assertThat(expectations.get(base).expectedAmount()).isEqualByComparingTo("900");
        }

        @Test
        @DisplayName("BASE is compared with the base plus the tariff's minimum adjustment")
        void baseIncludesAdjustments() {
            TripCostSnapshot withMinimum = new TripCostSnapshot(TRIP, new BigDecimal("1000"), null, "PEN", false,
                    Map.of("BASE", new BigDecimal("700"), "MINIMUM_ADJUSTMENT", new BigDecimal("300")));
            Map<UUID, FreightMatcher.LineExpectation> expectations = FreightMatcher.lineExpectations(
                    List.of(coded(base, TRIP, "1000", ChargeCode.BASE)), Map.of(TRIP, withMinimum));

            assertThat(expectations.get(base).expectedAmount()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("a closed cost is compared against its ACTUAL, and the result says so")
        void closedCostComparesAgainstActual() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1100"),
                    List.of(coded(base, TRIP, "1100", ChargeCode.FREIGHT)),
                    Map.of(TRIP, broken(TRIP, "1100", true)), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.expectedAmount()).isEqualByComparingTo("1100");
            assertThat(result.expectedBasis()).isEqualTo(ExpectedBasis.ACTUAL);
        }

        @Test
        @DisplayName("an open cost with an actual recorded is still compared against its ESTIMATE")
        void openCostComparesAgainstEstimate() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1100"),
                    List.of(coded(base, TRIP, "1100", ChargeCode.FREIGHT)),
                    Map.of(TRIP, broken(TRIP, "1100", false)), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.DISCREPANCY);
            assertThat(result.expectedAmount()).isEqualByComparingTo("1000");
            assertThat(result.expectedBasis()).isEqualTo(ExpectedBasis.ESTIMATED);
            assertThat(result.discrepancies()).extracting(FreightMatchResult.Discrepancy::lineId)
                    .contains(base);
        }

        @Test
        @DisplayName("one shipment closed and one open is MIXED")
        void mixedBasis() {
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("2100"),
                    List.of(coded(base, TRIP, "1100", ChargeCode.FREIGHT), coded(toll, OTHER_TRIP, "1000", ChargeCode.FREIGHT)),
                    Map.of(TRIP, broken(TRIP, "1100", true), OTHER_TRIP, broken(OTHER_TRIP, null, false)),
                    Tolerance.NONE);

            assertThat(result.expectedBasis()).isEqualTo(ExpectedBasis.MIXED);
            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
        }

        @Test
        @DisplayName("an all-in line beside a component is compared with what the component leaves")
        void freightLineGetsTheRemainder() {
            Map<UUID, FreightMatcher.LineExpectation> expectations = FreightMatcher.lineExpectations(
                    List.of(coded(base, TRIP, "900", ChargeCode.FREIGHT), coded(fuel, TRIP, "80", ChargeCode.FUEL_SURCHARGE)),
                    Map.of(TRIP, broken(TRIP, null, false)));

            assertThat(expectations.get(base).expectedAmount()).isEqualByComparingTo("920");
            assertThat(expectations.get(fuel).expectedAmount()).isEqualByComparingTo("80");
        }

        @Test
        @DisplayName("no breakdown: components are unknown, never zero, and the shipment is raised whole")
        void noBreakdownIsUnknownNotZero() {
            TripCostSnapshot flat = new TripCostSnapshot(TRIP, new BigDecimal("1000"), null, "PEN");
            List<InvoiceLine> lines = List.of(coded(base, TRIP, "1000", ChargeCode.BASE),
                    coded(toll, TRIP, "200", ChargeCode.TOLL));

            assertThat(FreightMatcher.lineExpectations(lines, Map.of(TRIP, flat)).get(toll).expectedAmount()).isNull();

            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1200"), lines,
                    Map.of(TRIP, flat), THREE_PERCENT);
            assertThat(result.discrepancies())
                    .filteredOn(d -> d.type() == DiscrepancyType.LINE_AMOUNT)
                    .singleElement()
                    .satisfies(d -> {
                        assertThat(d.lineId()).isNull();
                        assertThat(d.expectedAmount()).isEqualByComparingTo("1000");
                        assertThat(d.invoicedAmount()).isEqualByComparingTo("1200");
                    });
        }

        @Test
        @DisplayName("a component the estimate does not charge is expected at the tariff's zero")
        void unchargedComponentIsAKnownZero() {
            Map<UUID, FreightMatcher.LineExpectation> expectations = FreightMatcher.lineExpectations(
                    List.of(coded(fuel, TRIP, "50", ChargeCode.WAITING_TIME)), Map.of(TRIP, broken(TRIP, null, false)));

            assertThat(expectations.get(fuel).expectedAmount()).isEqualByComparingTo("0");
            assertThat(expectations.get(fuel).basis()).isEqualTo(ExpectedBasis.ESTIMATED);
        }

        @Test
        @DisplayName("a penalty is out of every comparison and out of the compared header")
        void penaltyIsExcluded() {
            UUID penalty = UUID.randomUUID();
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("900"),
                    List.of(coded(base, TRIP, "1000", ChargeCode.FREIGHT), coded(penalty, TRIP, "-100", ChargeCode.PENALTY)),
                    Map.of(TRIP, broken(TRIP, null, false)), Tolerance.NONE);

            assertThat(result.status()).isEqualTo(MatchStatus.MATCHED);
            assertThat(result.differenceAmount()).isEqualByComparingTo("0");
            assertThat(FreightMatcher.lineExpectations(
                    List.of(coded(penalty, TRIP, "-100", ChargeCode.PENALTY)), Map.of(TRIP, broken(TRIP, null, false))))
                    .doesNotContainKey(penalty);
        }

        @Test
        @DisplayName("a V46 line without a code reads as FREIGHT: two of them are still a duplicate")
        void uncodedLinesAreFreight() {
            assertThat(new InvoiceLine(base, TRIP, BigDecimal.ONE, "x", null).chargeCode())
                    .isEqualTo(ChargeCode.FREIGHT);
            assertThat(new FreightMatcher.TripCharge(TRIP, null))
                    .isEqualTo(new FreightMatcher.TripCharge(TRIP, ChargeCode.FREIGHT));
        }

        @Test
        @DisplayName("unmatched and missing-cost findings carry their line")
        void shipmentFindingsCarryTheirLine() {
            UUID orphan = UUID.randomUUID();
            FreightMatchResult result = FreightMatcher.match("PEN", new BigDecimal("1050"),
                    List.of(coded(base, TRIP, "1000", ChargeCode.FREIGHT), coded(orphan, null, "50", ChargeCode.TOLL)),
                    Map.of(TRIP, new TripCostSnapshot(TRIP, null, null, "PEN")), Tolerance.NONE);

            assertThat(result.discrepancies())
                    .extracting(FreightMatchResult.Discrepancy::type, FreightMatchResult.Discrepancy::lineId)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(DiscrepancyType.UNMATCHED_TRIP, orphan),
                            org.assertj.core.groups.Tuple.tuple(DiscrepancyType.MISSING_EXPECTED_COST, base));
        }
    }
}
