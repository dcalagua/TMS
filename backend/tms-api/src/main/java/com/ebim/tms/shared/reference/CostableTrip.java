package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything needed to put a price on one trip, carrying no {@code planning} type so that
 * {@code com.ebim.tms.rates} can cost a shipment without depending on the module that owns it
 * (migration V30). Assembled by {@code planning.infrastructure.TripCostingLookupAdapter}, the only
 * producer, and read by {@code rates.application.TripCostService}.
 *
 * <p>Everything here is either an identity a rate card can be matched on or a quantity a component
 * can be multiplied by. Nothing else about a trip is relevant to what it costs, and passing more
 * would invite the costing module to start making operational judgements.
 *
 * @param planningDate  the day the shipment runs, and therefore the day a card's validity is
 *                      judged against - never "today". Re-estimating a shipment from last month
 *                      must price it with the tariff that was in force then, and re-running an
 *                      estimate must not produce a different number just because time has passed.
 * @param carrierId     who is being paid, or null while no vehicle has been assigned. A trip with
 *                      no carrier cannot be priced at all - there is no counterparty - which is
 *                      the one case {@code TripCostService} refuses outright rather than
 *                      reporting as "no rate found".
 * @param vehicleTypeId the type of the assigned vehicle, or null when there is none. Used only to
 *                      pick between cards; a trip whose vehicle type no card names is priced by
 *                      the card that names no type.
 * @param routeId       the master corridor the shipment was built from, or null. Both a matching
 *                      key (a {@code ROUTE}-scoped card) and the only source of a distance this
 *                      product has - see {@link RouteTemplateLookupPort#findReferenceDistanceKm}.
 * @param weightKg      the summed declared weight of the orders on the trip; never null, and zero
 *                      when nothing on board declared one. Zero is <em>not</em> treated as a free
 *                      shipment: a per-kg component with a zero total is reported as
 *                      non-calculable rather than charged at nothing (migration V30).
 * @param volumeM3      as {@code weightKg}
 * @param pallets       as {@code weightKg}
 * @param costable      whether this trip has reached a state worth pricing - confirmed or beyond.
 *                      A draft is still being rearranged and its load, vehicle and route all still
 *                      change; pricing one would produce a figure that is out of date before the
 *                      planner has finished looking at it.
 */
public record CostableTrip(
        UUID tripId,
        UUID companyId,
        String shipmentNumber,
        LocalDate planningDate,
        UUID carrierId,
        UUID vehicleTypeId,
        UUID originId,
        UUID routeId,
        BigDecimal weightKg,
        BigDecimal volumeM3,
        BigDecimal pallets,
        boolean costable,
        /**
         * The shipment's only destination, or null when it has none or has several (V39).
         *
         * <p>Null for a multi-drop trip <b>on purpose</b>, and that is what makes lane pricing
         * unambiguous: a lane is one origin to one destination, and a shipment that visits four
         * places is not on a lane - it is on a route, which is what {@code RateCardScope.ROUTE}
         * exists for. Picking "the first stop" or "the farthest stop" as the lane would price a
         * multi-drop shipment against an agreement that was never about it.
         */
        UUID soleDestinationId,
        /**
         * How many stops the shipment has, or null when that is not known (V39).
         *
         * <p>Feeds the per-stop charge, which is levied on drops <em>after the first</em> - see
         * {@code RateComponent.STOP_OFF}. Null and zero are different: no stops recorded yet is not
         * the same as a shipment with one drop.
         */
        Integer stopCount) {

    /** The pre-V39 shape: no lane and no stop count. */
    public CostableTrip(UUID tripId, UUID companyId, String shipmentNumber, LocalDate planningDate,
            UUID carrierId, UUID vehicleTypeId, UUID originId, UUID routeId, BigDecimal weightKg,
            BigDecimal volumeM3, BigDecimal pallets, boolean costable) {
        this(tripId, companyId, shipmentNumber, planningDate, carrierId, vehicleTypeId, originId, routeId,
                weightKg, volumeM3, pallets, costable, null, null);
    }
}
