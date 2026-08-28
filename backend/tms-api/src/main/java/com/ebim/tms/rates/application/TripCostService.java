package com.ebim.tms.rates.application;

import com.ebim.tms.rates.domain.CostEstimate;
import com.ebim.tms.rates.domain.CostInputs;
import com.ebim.tms.rates.domain.CostQuantitySource;
import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardSelector;
import com.ebim.tms.rates.domain.TripCost;
import com.ebim.tms.rates.domain.TripCostCalculator;
import com.ebim.tms.rates.infrastructure.RateCardRepository;
import com.ebim.tms.rates.infrastructure.TripCostRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.CostableTrip;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.TripCostingLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trip cost use cases: price a shipment against the tariff in force, record what the carrier
 * actually charged, and settle the two against each other.
 *
 * <p>Reads the trip through {@link TripCostingLookupPort} and never through {@code planning}'s own
 * types, which is what keeps the two modules separable ({@code ModuleBoundaryTest}).
 *
 * <p><b>Two doors onto the same estimate, and the difference between them is deliberate.</b>
 * {@link #estimate} is what a person asks for and refuses out loud - no carrier, no card, a closed
 * cost. {@link #estimateOnConfirmation} is what the confirmation of a plan triggers and stays
 * silent about exactly those cases, because an installation that has not entered its tariffs must
 * still be able to dispatch a truck. Both go through {@link #applyEstimate}, so the number they
 * produce cannot differ.
 */
@Service
public class TripCostService {

    private final TripCostRepository tripCostRepository;
    private final RateCardRepository rateCardRepository;
    private final TripCostingLookupPort tripCostingLookupPort;
    private final RouteTemplateLookupPort routeTemplateLookupPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public TripCostService(TripCostRepository tripCostRepository, RateCardRepository rateCardRepository,
            TripCostingLookupPort tripCostingLookupPort, RouteTemplateLookupPort routeTemplateLookupPort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.tripCostRepository = tripCostRepository;
        this.rateCardRepository = rateCardRepository;
        this.tripCostingLookupPort = tripCostingLookupPort;
        this.routeTemplateLookupPort = routeTemplateLookupPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    /**
     * The cost of one trip, or the "not priced yet" shape. 404 only when the trip itself does not
     * exist in this company - see {@link TripCostView#none}.
     */
    @Transactional(readOnly = true)
    public TripCostView get(CompanyScope scope, UUID tripId) {
        CostableTrip trip = requireTrip(scope, tripId);
        return tripCostRepository.findByTripIdAndCompanyId(trip.tripId(), scope.companyId())
                .map(TripCostView::from)
                .orElseGet(() -> TripCostView.none(trip.tripId()));
    }

    /**
     * Prices a trip on demand, refusing with a message when it cannot be priced.
     *
     * <p>Re-estimating an already-estimated trip is allowed and rewrites the figure in place: a
     * planner who corrects a route, swaps a vehicle or fixes a tariff should be able to ask again.
     * Each run is audited, so what a shipment was previously estimated at is not lost.
     */
    @Transactional
    public TripCostView estimate(CompanyScope scope, UUID tripId) {
        CostableTrip trip = requireTrip(scope, tripId);
        if (!trip.costable()) {
            throw new ConflictException("Shipment " + trip.shipmentNumber()
                    + " has not been confirmed yet and cannot be priced.");
        }
        if (trip.carrierId() == null) {
            throw new ConflictException("Shipment " + trip.shipmentNumber()
                    + " has no carrier, so there is nobody to price it for.");
        }
        RateCard card = selectCard(trip).orElseThrow(() -> new ConflictException(
                "No active rate card covers shipment " + trip.shipmentNumber() + " on " + trip.planningDate() + "."));
        UUID actorId = auditActorProvider.requireAppUserId();
        return TripCostView.from(applyEstimate(scope, trip, card, actorId));
    }

    /**
     * The confirmation-time estimate: same calculation, silent about the two cases a plan must be
     * confirmable in spite of - no carrier yet, and no tariff entered for this shipment. See
     * {@code TripCostEstimationPort}.
     */
    @Transactional
    public void estimateOnConfirmation(CompanyScope scope, UUID tripId, UUID actorId) {
        Optional<CostableTrip> found = tripCostingLookupPort.findCostableTrip(tripId, scope.companyId());
        if (found.isEmpty() || found.get().carrierId() == null) {
            return;
        }
        CostableTrip trip = found.get();
        Optional<RateCard> card = selectCard(trip);
        if (card.isEmpty()) {
            return;
        }
        // A cost that is already settled is left exactly as it is. Re-confirming a plan must not
        // restate a figure somebody has reconciled against an invoice - and unlike the on-demand
        // path there is nobody here to be told, so silence is the only correct behaviour.
        Optional<TripCost> existing = tripCostRepository.findByTripIdAndCompanyId(tripId, scope.companyId());
        if (existing.isPresent() && existing.get().isClosed()) {
            return;
        }
        applyEstimate(scope, trip, card.get(), actorId);
    }

    /**
     * Records - or corrects - what the carrier actually charged. Creates the cost row when the
     * trip has none, which is the case for a shipment no rate card covered: the invoice is still a
     * fact worth keeping even when TMS could not predict it.
     */
    @Transactional
    public TripCostView recordActual(CompanyScope scope, UUID tripId, ActualCostRequest request) {
        CostableTrip trip = requireTrip(scope, tripId);
        UUID actorId = auditActorProvider.requireAppUserId();
        BigDecimal amount = money(request.amount());
        String requestedCurrency = normalizeCurrency(request.currency());

        TripCost cost = tripCostRepository.findByTripIdAndCompanyId(trip.tripId(), scope.companyId())
                .orElseGet(() -> {
                    if (requestedCurrency == null) {
                        throw new InvalidRequestException(
                                "currency is required: shipment " + trip.shipmentNumber() + " has no estimate to "
                                        + "take one from.");
                    }
                    return new TripCost(scope.companyId(), trip.tripId(), trip.planningDate(),
                            requestedCurrency, actorId);
                });
        requireOpen(cost, trip);
        if (requestedCurrency != null && !requestedCurrency.equals(cost.currency())) {
            throw new ConflictException("Shipment " + trip.shipmentNumber() + " is costed in " + cost.currency()
                    + " and cannot record an amount in " + requestedCurrency + ".");
        }

        cost.recordActual(amount, blankToNull(request.reference()), blankToNull(request.notes()), actorId);
        TripCost saved = tripCostRepository.saveAndFlush(cost);
        auditRecorder.record(scope, AuditAggregateType.TRIP_COST, saved.id(), AuditAction.COST_ACTUAL_RECORDED,
                Map.of("shipmentNumber", trip.shipmentNumber(), "amount", saved.actualAmount(),
                        "currency", saved.currency()));
        return TripCostView.from(saved);
    }

    /** Settles the cost: after this every write is refused until it is reopened. */
    @Transactional
    public TripCostView close(CompanyScope scope, UUID tripId) {
        CostableTrip trip = requireTrip(scope, tripId);
        TripCost cost = requireCost(scope, trip);
        requireOpen(cost, trip);
        if (!cost.hasActual()) {
            throw new ConflictException("Shipment " + trip.shipmentNumber()
                    + " has no actual cost recorded and cannot be closed.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        cost.close(actorId);
        TripCost saved = tripCostRepository.saveAndFlush(cost);
        auditRecorder.record(scope, AuditAggregateType.TRIP_COST, saved.id(), AuditAction.COST_CLOSED,
                Map.of("shipmentNumber", trip.shipmentNumber(), "amount", saved.actualAmount(),
                        "currency", saved.currency()));
        return TripCostView.from(saved);
    }

    /**
     * Makes a settled cost writable again. See {@code TripCost.reopen} for why this exists at all:
     * the alternative to an audited undo is a hand-edited database.
     */
    @Transactional
    public TripCostView reopen(CompanyScope scope, UUID tripId) {
        CostableTrip trip = requireTrip(scope, tripId);
        TripCost cost = requireCost(scope, trip);
        if (!cost.isClosed()) {
            throw new ConflictException("The cost of shipment " + trip.shipmentNumber() + " is not closed.");
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        cost.reopen(actorId);
        TripCost saved = tripCostRepository.saveAndFlush(cost);
        auditRecorder.record(scope, AuditAggregateType.TRIP_COST, saved.id(), AuditAction.COST_REOPENED,
                Map.of("shipmentNumber", trip.shipmentNumber()));
        return TripCostView.from(saved);
    }

    /**
     * The one place an estimate is calculated and written, shared by both doors so the on-demand
     * figure and the confirmation-time figure cannot differ.
     */
    private TripCost applyEstimate(CompanyScope scope, CostableTrip trip, RateCard card, UUID actorId) {
        // The shipment's own measured run where there is one, the master corridor's reference
        // distance otherwise (V39). Which of the two was used travels onto the breakdown, because a
        // per-kilometre charge that cannot be traced to the kilometres it multiplied is a number
        // nobody can dispute.
        Distance distance = distanceFor(scope, trip);
        CostEstimate estimate = TripCostCalculator.calculate(card,
                CostInputs.of(trip, distance.km(), distance.source(), trip.stopCount(), null));
        TripCost cost = tripCostRepository.findByTripIdAndCompanyId(trip.tripId(), trip.companyId())
                .orElseGet(() -> new TripCost(trip.companyId(), trip.tripId(), trip.planningDate(),
                        estimate.currency(), actorId));
        requireOpen(cost, trip);
        if (!cost.currency().equals(estimate.currency())) {
            throw new ConflictException("Shipment " + trip.shipmentNumber() + " is costed in " + cost.currency()
                    + " and rate card " + card.code() + " is in " + estimate.currency() + ".");
        }

        // Cleared and flushed before the new lines are written, never in the same unit of work:
        // see TripCost.clearComponents for the Hibernate ordering this avoids. A no-op flush on a
        // trip being priced for the first time.
        if (cost.hasEstimate()) {
            cost.clearComponents();
            tripCostRepository.saveAndFlush(cost);
        }
        cost.recordEstimate(estimate, card, actorId);
        TripCost saved = tripCostRepository.saveAndFlush(cost);
        auditRecorder.record(scope, AuditAggregateType.TRIP_COST, saved.id(), AuditAction.COST_ESTIMATED,
                Map.of("shipmentNumber", trip.shipmentNumber(), "rateCard", card.code(),
                        "amount", saved.estimatedAmount(), "currency", saved.currency(),
                        "complete", estimate.isComplete()));
        return saved;
    }

    private Optional<RateCard> selectCard(CostableTrip trip) {
        return RateCardSelector.select(trip,
                rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(trip.companyId(), trip.carrierId()));
    }

    /**
     * The trip's reference distance, or null when it has no route or the route carries none.
     *
     * <p>Null is what makes the per-km component report itself non-calculable instead of being
     * multiplied by a zero nobody meant - the "no inventar la distancia" rule, in one place.
     */
    private BigDecimal referenceDistanceKm(CostableTrip trip) {
        if (trip.routeId() == null) {
            return null;
        }
        return routeTemplateLookupPort.findReferenceDistanceKm(trip.routeId(), trip.companyId()).orElse(null);
    }

    /** A distance and where it came from. */
    private record Distance(BigDecimal km, CostQuantitySource source) {
    }

    /**
     * The measured run first, the master corridor's reference distance second, nothing third.
     *
     * <p>Measured wins because it is about this shipment rather than about a corridor it resembles,
     * and because it exists for a trip with no master route at all - which before V39 could not be
     * priced per kilometre however long it actually was.
     *
     * <p>Nothing is still a legitimate answer, and it is what makes the per-km component report
     * itself non-calculable instead of being multiplied by a zero nobody meant: the "do not invent
     * the distance" rule, unchanged since V30 and now with one more place to look first.
     */
    private Distance distanceFor(CompanyScope scope, CostableTrip trip) {
        BigDecimal measured = tripCostingLookupPort
                .findMeasuredDistanceKm(trip.tripId(), scope.companyId())
                .orElse(null);
        if (measured != null && measured.signum() > 0) {
            return new Distance(measured, CostQuantitySource.MEASURED_ROUTE);
        }
        return new Distance(referenceDistanceKm(trip), CostQuantitySource.ROUTE_REFERENCE);
    }

    private CostableTrip requireTrip(CompanyScope scope, UUID tripId) {
        return tripCostingLookupPort.findCostableTrip(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found."));
    }

    private TripCost requireCost(CompanyScope scope, CostableTrip trip) {
        return tripCostRepository.findByTripIdAndCompanyId(trip.tripId(), scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment " + trip.shipmentNumber() + " has no cost recorded."));
    }

    private static void requireOpen(TripCost cost, CostableTrip trip) {
        if (cost.isClosed()) {
            throw new ConflictException("The cost of shipment " + trip.shipmentNumber()
                    + " is closed. Reopen it before changing it.");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(TripCostCalculator.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String value) {
        return (value == null || value.isBlank()) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
