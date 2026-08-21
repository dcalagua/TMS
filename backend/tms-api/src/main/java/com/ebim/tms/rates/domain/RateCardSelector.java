package com.ebim.tms.rates.domain;

import com.ebim.tms.shared.reference.CostableTrip;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Picks the one card that prices a shipment, out of every active card its carrier has.
 *
 * <p>Pure, like {@link TripCostCalculator} and for the same reason: which agreement applied to a
 * shipment must be reproducible and explainable, so the rule lives in one testable function
 * instead of in an ORDER BY somebody can quietly change.
 *
 * <h2>The rule</h2>
 *
 * <p>A card is a <em>candidate</em> when all four hold: it is this carrier's, it is in force on the
 * trip's planning date, its scope covers the shipment, and its vehicle type is either unset or the
 * one the shipment runs on. (Company and {@code active} are the repository's job - the candidates
 * never leave the tenant.)
 *
 * <p>Candidates are then ranked by, in order:
 *
 * <ol>
 *   <li><b>Scope</b>, narrowest first: {@code ROUTE} beats {@code ORIGIN} beats {@code CARRIER}.</li>
 *   <li><b>Vehicle type</b>: a card naming one beats a card that does not. Below scope and not
 *       above it, because a price agreed for a corridor is a more deliberate statement than a
 *       price agreed for a class of truck across the whole network - "the Norte run costs this"
 *       is the sentence a commercial manager actually says.</li>
 *   <li><b>Latest {@code validFrom}</b>: when two agreements genuinely overlap, the newer one is
 *       the renegotiation.</li>
 *   <li><b>Code</b>, ascending. Never reached by well-formed data
 *       ({@code uq_rate_card_active_agreement} refuses two identical active agreements starting on
 *       the same day) and present so that the answer is total anyway: an installation that
 *       somehow holds two overlapping cards gets one deterministic price rather than whichever
 *       row the database returned first.</li>
 * </ol>
 */
public final class RateCardSelector {

    /**
     * Highest-ranked first. Written as a comparator over candidates rather than as a fold, so the
     * tie-break order above <em>is</em> the code.
     */
    private static final Comparator<RateCard> BEST_FIRST =
            Comparator.comparingInt((RateCard card) -> card.scope().specificity()).reversed()
                    .thenComparing(Comparator.comparing((RateCard card) -> card.vehicleTypeId() != null).reversed())
                    .thenComparing(Comparator.comparing(RateCard::validFrom).reversed())
                    .thenComparing(RateCard::code);

    private RateCardSelector() {
    }

    /**
     * The card that prices {@code trip}, or empty when this carrier has none that covers it.
     *
     * <p>Empty is an ordinary answer and not an error: a company that has entered no tariff for a
     * corridor yet has shipments with no estimate, which is exactly what the screen should say.
     *
     * @param candidates every active card of the trip's company and carrier; may include cards
     *     that do not cover the trip, which are filtered here
     */
    public static Optional<RateCard> select(CostableTrip trip, Collection<RateCard> candidates) {
        return candidates.stream()
                .filter(card -> card.carrierId().equals(trip.carrierId()))
                .filter(card -> card.coversDate(trip.planningDate()))
                .filter(card -> card.appliesToScopeOf(trip.originId(), trip.routeId()))
                .filter(card -> card.appliesToVehicleType(trip.vehicleTypeId()))
                .min(BEST_FIRST);
    }
}
