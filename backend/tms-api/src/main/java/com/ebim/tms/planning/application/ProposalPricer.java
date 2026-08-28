package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.PlanningProposal.ProposedTrip;
import com.ebim.tms.shared.reference.CarrierQuotationPort;
import com.ebim.tms.shared.reference.CarrierQuote;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.PlannableOrder;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Prices a plan nobody has committed to yet (JOB 11, closing open debt D1).
 *
 * <p>{@code PlanningKpis.totalCost} was null by design and said so: "pricing a hypothetical trip
 * needs a rating port that takes a proposal rather than a persisted shipment". V39 built the rating
 * and JOB 07 built {@link CarrierQuotationPort}, which already prices a shipment against a carrier
 * it does not have - a tender. A proposal is the same question one step earlier, and this asks it
 * through the same port, the same selector and the same calculator.
 *
 * <p>That sameness is the point. A plan compared on price and the invoice that eventually follows
 * it must come from one set of rules; two code paths computing "the price" is precisely how those
 * two numbers come to differ.
 *
 * <h2>What it refuses to do</h2>
 *
 * <p><b>No partial totals.</b> If any proposed trip cannot be priced, there is no total - only a
 * count and a reason. A sum that silently omits the three trips nobody has an agreement for makes
 * the worse plan look cheaper, and comparing engines on cost is the entire purpose of the figure.
 *
 * <p><b>No currency conversion.</b> Agreements in different currencies do not add up. This product
 * invents no FX rate (V30), and {@code CarrierQuote} already refuses the same thing when ranking
 * carriers.
 *
 * <p><b>No invented distance.</b> A trip whose legs the travel matrix does not all know is priced
 * with a null distance, so a per-kilometre component reports itself non-calculable rather than
 * being multiplied by a pile of zeros. {@link TravelMatrix#knows} is what makes that visible -
 * {@code distanceKm} answers zero for an unknown leg, which is right for planning and wrong here.
 */
@Service
public class ProposalPricer {

    private final CarrierQuotationPort quotationPort;

    public ProposalPricer(CarrierQuotationPort quotationPort) {
        this.quotationPort = quotationPort;
    }

    /**
     * What this plan would cost, or why that cannot be said.
     *
     * <p>Never throws. A plan that cannot be priced is an ordinary outcome - most installations
     * have agreements for some carriers and not others - and a planning run must not fail because
     * of it.
     */
    public ProposalPricing price(UUID companyId, PlanningInput input, List<ProposedTrip> trips) {
        if (trips.isEmpty()) {
            return ProposalPricing.none(ProposalPricing.UnpricedReason.NO_TRIPS, 0, 0);
        }

        Map<UUID, VehicleCapacityReference> vehicles = input.vehicles().stream()
                .collect(Collectors.toMap(VehicleCapacityReference::id, Function.identity(),
                        (first, second) -> first));
        Map<UUID, PlannableOrder> orders = input.orders().stream()
                .collect(Collectors.toMap(PlannableOrder::id, Function.identity(),
                        (first, second) -> first));

        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        int priced = 0;

        for (ProposedTrip proposed : trips) {
            Optional<CarrierQuote> quote = quote(companyId, input, proposed, vehicles, orders);
            if (quote.isEmpty()) {
                // One unpriceable trip is enough. Carrying on to produce a sum over the rest would
                // be the partial total this refuses to report.
                return ProposalPricing.none(
                        ProposalPricing.UnpricedReason.NO_AGREEMENT_FOR_SOME_TRIP, priced, trips.size());
            }
            CarrierQuote found = quote.get();
            if (currency == null) {
                currency = found.currency();
            } else if (!currency.equals(found.currency())) {
                return ProposalPricing.none(
                        ProposalPricing.UnpricedReason.MIXED_CURRENCIES, priced, trips.size());
            }
            total = total.add(found.amount());
            priced++;
        }

        return ProposalPricing.of(total, currency, trips.size());
    }

    private Optional<CarrierQuote> quote(UUID companyId, PlanningInput input, ProposedTrip proposed,
            Map<UUID, VehicleCapacityReference> vehicles, Map<UUID, PlannableOrder> orders) {
        VehicleCapacityReference vehicle = vehicles.get(proposed.vehicleId());
        if (vehicle == null || vehicle.carrierId() == null) {
            // Own fleet, or a vehicle the snapshot no longer holds. A rate card is an agreement
            // with a carrier, and there is no agreement with yourself: own-fleet cost is an
            // internal-rate model this product does not have, and pricing it at zero would make a
            // plan that used it unbeatable.
            return Optional.empty();
        }

        List<PlannableOrder> carried = proposed.orderIds().stream()
                .map(orders::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (carried.size() != proposed.orderIds().size()) {
            return Optional.empty();
        }

        CostableTrip costable = new CostableTrip(
                // No trip id: this shipment does not exist yet, which is the whole point. Nothing
                // downstream looks one up - quoteWithKnownDistance is the overload that does not.
                null, companyId, null, input.planningDate(),
                vehicle.carrierId(), vehicle.vehicleTypeId(), input.originId(), proposed.routeId(),
                sum(carried, PlannableOrder::totalWeightKg),
                sum(carried, PlannableOrder::totalVolumeM3),
                sum(carried, PlannableOrder::totalPallets),
                true,
                soleDestinationOf(proposed),
                proposed.stopLocationIds().size());

        return quotationPort.quoteWithKnownDistance(companyId, costable, vehicle.carrierId(),
                distanceOf(input, proposed));
    }

    /**
     * The run's distance, or null when any leg of it is unknown.
     *
     * <p>Null and not a short sum. A per-kilometre charge over legs the matrix could not measure
     * would be a price that looks calculated and is not - the "do not invent the distance" rule
     * V30 states and V39 kept.
     */
    private static BigDecimal distanceOf(PlanningInput input, ProposedTrip proposed) {
        TravelMatrix travel = input.travel();
        BigDecimal total = BigDecimal.ZERO;
        UUID from = input.originId();
        for (UUID stop : proposed.stopLocationIds()) {
            if (!travel.knows(from, stop)) {
                return null;
            }
            total = total.add(travel.distanceKm(from, stop));
            from = stop;
        }
        // The return leg, for the same reason TripRoutingService does not measure one: what a
        // carrier charges is stated by the rate card's own components, and inventing a leg back to
        // the depot here would price something the plan never claimed.
        return total;
    }

    /**
     * The one destination, or null when the shipment visits several.
     *
     * <p>Null for a multi-drop trip on purpose, exactly as {@link CostableTrip} requires: a lane is
     * one origin to one destination, and picking "the first stop" would price a four-drop shipment
     * against an agreement that was never about it.
     */
    private static UUID soleDestinationOf(ProposedTrip proposed) {
        Set<UUID> distinct = new HashSet<>(proposed.stopLocationIds());
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private static BigDecimal sum(List<PlannableOrder> orders, Function<PlannableOrder, BigDecimal> field) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (PlannableOrder order : orders) {
            BigDecimal value = field.apply(order);
            if (value != null) {
                total = total.add(value);
                any = true;
            }
        }
        // Null when not one order declared it: a shipment nobody weighed is not a shipment weighing
        // nothing, and the per-kilo component must say so rather than charge for zero kilos.
        return any ? total : null;
    }
}
