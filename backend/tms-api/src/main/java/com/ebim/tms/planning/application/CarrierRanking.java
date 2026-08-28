package com.ebim.tms.planning.application;

import com.ebim.tms.shared.reference.CarrierQuote;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The order carriers are offered a shipment in (JOB 07).
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li><b>A price beats no price.</b> A carrier with an applicable agreement always ranks above
 *       one without, however cheap the second might turn out to be. "No tariff entered" is not
 *       "free", and a ranking that treated it as zero would put the carrier nobody has a contract
 *       with at the top of every list.</li>
 *   <li><b>Cheapest first</b>, among carriers quoting in the same currency.</li>
 *   <li><b>Then by code</b>, which is not a business rule - it is what makes the ranking
 *       reproducible. Two carriers quoting the same figure would otherwise swap places between two
 *       runs and "why did this go to the third carrier" would have no stable answer.</li>
 * </ol>
 *
 * <h2>Currencies are not converted</h2>
 *
 * <p>Two carriers quoting in different currencies are not comparable, and this product invents no
 * FX rate (V30). Quotes in a currency other than the shipment's reference are ranked <em>after</em>
 * every comparable one and marked, rather than converted at a rate nobody agreed to or dropped as
 * though the carrier had no price at all.
 *
 * <p>Pure: a list in, a list out, no repository and no clock - so a ranking is reproducible and
 * provable without a database, exactly as the planning engines are.
 */
public final class CarrierRanking {

    private CarrierRanking() {}

    /**
     * One carrier's place, with what it was ranked on.
     *
     * @param quote null when the carrier has no applicable agreement - still offerable, ranked last
     * @param comparable whether this quote could be compared with the rest on price alone
     */
    public record Candidate(UUID carrierId, String carrierCode, CarrierQuote quote, boolean comparable) {

        public boolean hasPrice() {
            return quote != null;
        }
    }

    /**
     * Ranks {@code carriers} for a shipment, using the quotes that could be produced.
     *
     * @param referenceCurrency the currency prices are compared in - the majority currency among the
     *     quotes. Null when there are none, in which case every carrier ranks by code alone
     */
    public static List<Candidate> rank(List<CarrierReference> carriers, Map<UUID, CarrierQuote> quotes) {
        String referenceCurrency = majorityCurrency(quotes);

        return carriers.stream()
                .map(carrier -> {
                    CarrierQuote quote = quotes.get(carrier.id());
                    boolean comparable = quote != null
                            && (referenceCurrency == null || referenceCurrency.equals(quote.currency()));
                    return new Candidate(carrier.id(), carrier.code(), quote, comparable);
                })
                .sorted(Comparator
                        // A price beats no price; a comparable price beats one in another currency.
                        .comparingInt((Candidate candidate) -> candidate.comparable() ? 0
                                : candidate.hasPrice() ? 1 : 2)
                        .thenComparing(candidate -> candidate.hasPrice()
                                ? candidate.quote().amount()
                                : BigDecimal.ZERO)
                        // Determinism, not business: without it two equal quotes swap places
                        // between runs and the stored ranking stops explaining itself.
                        .thenComparing(Candidate::carrierCode,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * The currency most quotes are in.
     *
     * <p>Majority rather than "the first one": a single card mis-keyed in another currency would
     * otherwise make every correctly-priced carrier the incomparable one.
     */
    private static String majorityCurrency(Map<UUID, CarrierQuote> quotes) {
        return quotes.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(CarrierQuote::currency,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                // Count first, then the code, so a tie between two currencies resolves the same way
                // every time rather than on map iteration order.
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** The little a ranking needs to know about a carrier. */
    public record CarrierReference(UUID id, String code, String name) {
    }
}
