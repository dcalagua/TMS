package com.ebim.tms.rates.domain;

/**
 * How narrowly a {@link RateCard} applies, and - through {@link #specificity()} - which card wins
 * when more than one covers the same shipment (migration V30).
 *
 * <p>The three values are the only ones a <em>trip</em> can be matched against unambiguously. Zone
 * is absent on purpose: a zone belongs to a destination, a trip serves many, so "the zone of this
 * trip" has no answer that is true rather than convenient. See the V30 migration header.
 */
public enum RateCardScope {

    /** Anything this carrier runs. The fallback every company can state on day one. */
    CARRIER(0),

    /** Anything leaving this depot, whatever it then serves. */
    ORIGIN(1),

    /**
     * This origin to this destination (migration V39) - the thing most freight agreements are
     * actually priced on.
     *
     * <p>Narrower than {@link #ORIGIN}, which is everything out of a depot, and wider than
     * {@link #ROUTE}, which is one named master corridor. A lane rate applies however the
     * corridor is driven and whichever vehicle runs it, which is how a contract states it.
     *
     * <p>Applies only to a shipment with exactly one destination: a multi-drop trip is not on a
     * lane. See {@code CostableTrip.soleDestinationId}.
     */
    LANE(2),
    /** Shipments built from this master corridor - the narrowest thing a card can name. */
    ROUTE(3);

    private final int specificity;

    RateCardScope(int specificity) {
        this.specificity = specificity;
    }

    /**
     * How specific this scope is, higher being narrower. The first key
     * {@link RateCardSelector} ranks by: a corridor's own price beats the depot's, which beats the
     * carrier's blanket rate - which is the order a person would read the three agreements in.
     */
    public int specificity() {
        return specificity;
    }
}
