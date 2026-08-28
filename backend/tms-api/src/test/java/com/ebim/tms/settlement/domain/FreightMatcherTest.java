package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.FreightMatcher.InvoiceLine;
import com.ebim.tms.settlement.domain.FreightMatcher.TripCostSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
}
