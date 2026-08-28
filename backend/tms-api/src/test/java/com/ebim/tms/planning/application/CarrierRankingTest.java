package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.shared.reference.CarrierQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The order carriers are offered a shipment in (JOB 07).
 *
 * <p>Pure: a list in, a list out. The ranking is stored on the waterfall and walked for the life of
 * the shipment, so getting it wrong is not a display bug - it is the shipment going to the wrong
 * carrier, at the wrong price, with a stored record that says it was deliberate.
 */
class CarrierRankingTest {

    private static final UUID A = UUID.nameUUIDFromBytes("carrier-a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("carrier-b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("carrier-c".getBytes());

    private static CarrierRanking.CarrierReference carrier(UUID id, String code) {
        return new CarrierRanking.CarrierReference(id, code, code + " SAC");
    }

    private static CarrierQuote quote(UUID carrierId, String amount, String currency) {
        return new CarrierQuote(carrierId, new BigDecimal(amount), currency, UUID.randomUUID(), "RC-1", false);
    }

    @Test
    @DisplayName("cheapest first")
    void cheapestFirst() {
        List<CarrierRanking.Candidate> ranked = CarrierRanking.rank(
                List.of(carrier(A, "AAA"), carrier(B, "BBB"), carrier(C, "CCC")),
                Map.of(A, quote(A, "900.00", "PEN"), B, quote(B, "700.00", "PEN"), C, quote(C, "800.00", "PEN")));

        assertThat(ranked).extracting(CarrierRanking.Candidate::carrierId).containsExactly(B, C, A);
    }

    /**
     * The rule that matters most. A carrier with no tariff entered would rank first if its absent
     * price were read as zero, and the shipment would be offered to the one carrier nobody has an
     * agreement with.
     */
    @Test
    @DisplayName("a carrier with no applicable tariff ranks last, not first")
    void noPriceIsNotFree() {
        List<CarrierRanking.Candidate> ranked = CarrierRanking.rank(
                List.of(carrier(A, "AAA"), carrier(B, "BBB")),
                Map.of(B, quote(B, "900.00", "PEN")));

        assertThat(ranked).extracting(CarrierRanking.Candidate::carrierId).containsExactly(B, A);
        assertThat(ranked.get(1).hasPrice()).isFalse();
    }

    @Test
    @DisplayName("an unpriced carrier is still a candidate: a dispatcher may want to offer to them")
    void unpricedCarriersAreStillOffered() {
        List<CarrierRanking.Candidate> ranked = CarrierRanking.rank(
                List.of(carrier(A, "AAA"), carrier(B, "BBB")), Map.of());

        assertThat(ranked).hasSize(2);
        assertThat(ranked).allMatch(candidate -> !candidate.hasPrice());
    }

    /**
     * Two carriers quoting in different currencies are not comparable, and this product invents no
     * FX rate. The odd one out ranks after every comparable quote rather than being converted at a
     * rate nobody agreed to, or dropped as though it had no price at all.
     */
    @Test
    @DisplayName("a quote in another currency ranks after the comparable ones, and is not converted")
    void currenciesAreNotConverted() {
        List<CarrierRanking.Candidate> ranked = CarrierRanking.rank(
                List.of(carrier(A, "AAA"), carrier(B, "BBB"), carrier(C, "CCC")),
                Map.of(A, quote(A, "900.00", "PEN"),
                        B, quote(B, "800.00", "PEN"),
                        // Cheaper as a number, and in dollars - so not cheaper at all.
                        C, quote(C, "300.00", "USD")));

        assertThat(ranked).extracting(CarrierRanking.Candidate::carrierId).containsExactly(B, A, C);
        assertThat(ranked.get(2).comparable()).isFalse();
    }

    @Test
    @DisplayName("the majority currency is the reference, so one mis-keyed card does not invert the list")
    void majorityCurrencyWins() {
        List<CarrierRanking.Candidate> ranked = CarrierRanking.rank(
                List.of(carrier(A, "AAA"), carrier(B, "BBB"), carrier(C, "CCC")),
                Map.of(A, quote(A, "900.00", "PEN"),
                        B, quote(B, "800.00", "PEN"),
                        C, quote(C, "1.00", "USD")));

        // The single USD card does not become the reference and push both PEN carriers to the back.
        assertThat(ranked).extracting(CarrierRanking.Candidate::carrierId).containsExactly(B, A, C);
    }

    /**
     * Not a business rule - the property that makes a stored ranking able to explain itself. Without
     * it two equal quotes swap places between runs and "why did this go to the third carrier" has no
     * stable answer.
     */
    @Test
    @DisplayName("equal quotes break the tie on code, so the same input ranks the same way twice")
    void deterministic() {
        List<CarrierRanking.CarrierReference> carriers =
                List.of(carrier(C, "CCC"), carrier(A, "AAA"), carrier(B, "BBB"));
        Map<UUID, CarrierQuote> quotes = Map.of(
                A, quote(A, "800.00", "PEN"), B, quote(B, "800.00", "PEN"), C, quote(C, "800.00", "PEN"));

        assertThat(CarrierRanking.rank(carriers, quotes))
                .extracting(CarrierRanking.Candidate::carrierCode)
                .containsExactly("AAA", "BBB", "CCC");
        assertThat(CarrierRanking.rank(carriers, quotes))
                .isEqualTo(CarrierRanking.rank(carriers, quotes));
    }

    @Test
    @DisplayName("no carriers means no ranking, not an error")
    void empty() {
        assertThat(CarrierRanking.rank(List.of(), Map.of())).isEmpty();
    }
}
