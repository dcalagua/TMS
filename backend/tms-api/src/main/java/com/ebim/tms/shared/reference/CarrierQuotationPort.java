package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What each carrier would charge to run a shipment (JOB 07).
 *
 * <p><b>Why planning asks rather than reads.</b> Ranking carriers is a planning decision and
 * pricing is a rates rule; a carrier waterfall that reached into {@code tms.rate_card} would put
 * the selection of an agreement - effective dates, scope specificity, vehicle-type matching - in
 * two places, and the day they disagreed the offer sent would not be the price invoiced.
 *
 * <p><b>A carrier with no applicable agreement is simply absent</b> from the answer rather than
 * present at zero. It is a real and common state - a carrier onboarded before its tariff was
 * entered - and zero would rank it first.
 */
public interface CarrierQuotationPort {

    /**
     * One quote per carrier that has an applicable agreement, keyed by carrier.
     *
     * <p>Batched because a waterfall prices every candidate at once: a per-carrier call would be an
     * N+1 over a list whose whole purpose is to be compared side by side.
     */
    Map<UUID, CarrierQuote> quote(UUID companyId, CostableTrip trip, Collection<UUID> carrierIds);

    /** One carrier, for the paths that already know which one they mean. */
    Optional<CarrierQuote> quote(UUID companyId, CostableTrip trip, UUID carrierId);

    /**
     * Prices a shipment whose distance the caller already knows, and that may not exist yet
     * (JOB 11).
     *
     * <p>The other two overloads resolve the distance by looking the persisted trip up. A
     * <em>proposal</em> has no row to look up and does not need one: the planning run measured
     * every leg it is made of before the engine ran, so the distance is already in hand and a
     * lookup would be a second, worse answer to a question already settled.
     *
     * <p>{@code distanceKm} may be null, and null is not zero. A proposal whose legs could not all
     * be measured has no distance, and a per-kilometre component must report itself non-calculable
     * rather than be multiplied by a zero nobody meant - the same rule {@code TripCostService}
     * follows for a shipment with no route.
     *
     * <p>Everything else - card selection, dates, scope, vehicle type, the calculator - is the
     * path a confirmed shipment takes. That is the point: a plan compared on price and the invoice
     * that follows it must come from one set of rules.
     */
    Optional<CarrierQuote> quoteWithKnownDistance(UUID companyId, CostableTrip trip, UUID carrierId,
            java.math.BigDecimal distanceKm);
}
