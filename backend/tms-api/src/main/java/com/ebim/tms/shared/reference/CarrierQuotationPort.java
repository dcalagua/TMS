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
}
