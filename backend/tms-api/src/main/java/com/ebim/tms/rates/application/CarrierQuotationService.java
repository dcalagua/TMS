package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.CostComponentStatus;
import com.ebim.tms.rates.domain.CostEstimate;
import com.ebim.tms.rates.domain.CostInputs;
import com.ebim.tms.rates.domain.CostQuantitySource;
import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardSelector;
import com.ebim.tms.rates.domain.TripCostCalculator;
import com.ebim.tms.rates.infrastructure.RateCardRepository;
import com.ebim.tms.shared.reference.CarrierQuote;
import com.ebim.tms.shared.reference.CarrierQuotationPort;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices one shipment against several carriers, so that a waterfall can rank them (JOB 07).
 *
 * <p><b>The same calculator, the same selector, the same inputs</b> as
 * {@code TripCostService.estimateOnConfirmation}. That is the point: a carrier offered a shipment at
 * 840 must be invoiced against the agreement that produced 840, and two code paths computing "the
 * price" separately is precisely how those two numbers come to differ.
 *
 * <p>The one difference is <em>whose</em> price is being asked for. A confirmed trip is priced
 * against the carrier it already has; this prices a trip against a carrier it does not have yet,
 * which is what a tender is.
 */
@Service
public class CarrierQuotationService implements CarrierQuotationPort {

    private final RateCardRepository rateCardRepository;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final TripCostingLookupPort tripCostingLookupPort;

    public CarrierQuotationService(RateCardRepository rateCardRepository,
            RouteTemplateLookupPort routeTemplateLookupPort, TripCostingLookupPort tripCostingLookupPort) {
        this.rateCardRepository = rateCardRepository;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.tripCostingLookupPort = tripCostingLookupPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CarrierQuote> quote(UUID companyId, CostableTrip trip, Collection<UUID> carrierIds) {
        if (carrierIds.isEmpty()) {
            return Map.of();
        }
        // The quantities are the shipment's and do not change per carrier, so they are resolved
        // once for the whole ranking rather than per candidate.
        CostInputs inputs = inputsFor(companyId, trip);

        Map<UUID, CarrierQuote> quotes = new LinkedHashMap<>();
        for (UUID carrierId : carrierIds) {
            quoteAgainst(companyId, trip, carrierId, inputs).ifPresent(quote -> quotes.put(carrierId, quote));
        }
        return quotes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CarrierQuote> quote(UUID companyId, CostableTrip trip, UUID carrierId) {
        return quoteAgainst(companyId, trip, carrierId, inputsFor(companyId, trip));
    }

    /**
     * Prices the shipment as if {@code carrierId} were running it.
     *
     * <p>The selector needs the trip's carrier to match a card's, and the trip's carrier is the one
     * it has today rather than the one being quoted - so the trip is re-stated with the candidate's
     * id. Every other selection rule (dates, scope, vehicle type) then applies unchanged, which is
     * what keeps one set of rules governing both the offer and the invoice.
     */
    private Optional<CarrierQuote> quoteAgainst(UUID companyId, CostableTrip trip, UUID carrierId,
            CostInputs inputs) {
        CostableTrip asCandidate = new CostableTrip(trip.tripId(), companyId, trip.shipmentNumber(),
                trip.planningDate(), carrierId, trip.vehicleTypeId(), trip.originId(), trip.routeId(),
                trip.weightKg(), trip.volumeM3(), trip.pallets(), trip.costable(), trip.soleDestinationId(),
                trip.stopCount());

        List<RateCard> candidates =
                rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(companyId, carrierId);
        Optional<RateCard> card = RateCardSelector.select(asCandidate, candidates);
        if (card.isEmpty() || !card.get().hasAnyComponent()) {
            // No applicable agreement. Absent from the ranking rather than present at zero, which
            // would put the carrier nobody has a contract with at the top of the list.
            return Optional.empty();
        }

        CostEstimate estimate = TripCostCalculator.calculate(card.get(), inputs);
        boolean partial = estimate.lines().stream()
                .anyMatch(line -> line.status() == CostComponentStatus.NOT_CALCULABLE);
        return Optional.of(new CarrierQuote(carrierId, estimate.amount(), estimate.currency(),
                card.get().id(), card.get().code(), partial));
    }

    /** The shipment's quantities, resolved exactly as {@code TripCostService} resolves them. */
    private CostInputs inputsFor(UUID companyId, CostableTrip trip) {
        BigDecimal measured = tripCostingLookupPort.findMeasuredDistanceKm(trip.tripId(), companyId)
                .orElse(null);
        if (measured != null && measured.signum() > 0) {
            return CostInputs.of(trip, measured, CostQuantitySource.MEASURED_ROUTE, trip.stopCount(), null);
        }
        BigDecimal reference = trip.routeId() == null
                ? null
                : routeTemplateLookupPort.findReferenceDistanceKm(trip.routeId(), companyId).orElse(null);
        return CostInputs.of(trip, reference, CostQuantitySource.ROUTE_REFERENCE, trip.stopCount(), null);
    }
}
