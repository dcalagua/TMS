package com.ebim.tms.rates.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.rates.domain.CostComponentReason;
import com.ebim.tms.rates.domain.CostComponentStatus;
import com.ebim.tms.rates.domain.CostInputs;
import com.ebim.tms.rates.domain.RateCard;
import com.ebim.tms.rates.domain.RateCardScope;
import com.ebim.tms.rates.domain.RateComponent;
import com.ebim.tms.rates.domain.RateComponents;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link TripCostService} adds on top of the calculation: what may be priced, what a
 * confirmation-time estimate stays quiet about, and what a closed cost refuses.
 *
 * <p>Mocked rather than database-backed on purpose - none of these rules is about persistence, and
 * the persistence half (the V30 CHECK constraints, {@code uq_trip_cost_trip}, the tenant policy)
 * belongs to an integration test, which needs Docker. This file runs everywhere.
 */
class TripCostServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID ORIGIN = UUID.randomUUID();
    private static final UUID ROUTE = UUID.randomUUID();
    private static final UUID VEHICLE_TYPE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripCostRepository tripCostRepository;
    private RateCardRepository rateCardRepository;
    private TripCostingLookupPort trips;
    private RouteTemplateLookupPort routes;
    private AuditRecorder auditRecorder;
    private TripCostService service;

    @BeforeEach
    void setUp() {
        tripCostRepository = mock(TripCostRepository.class);
        rateCardRepository = mock(RateCardRepository.class);
        trips = mock(TripCostingLookupPort.class);
        routes = mock(RouteTemplateLookupPort.class);
        auditRecorder = mock(AuditRecorder.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new TripCostService(tripCostRepository, rateCardRepository, trips, routes, actors, auditRecorder);
        when(tripCostRepository.saveAndFlush(any(TripCost.class))).thenAnswer(call -> call.getArgument(0));
        when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.empty());
        when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.of(trip(true, CARRIER, ROUTE)));
        when(routes.findReferenceDistanceKm(ROUTE, COMPANY)).thenReturn(Optional.of(new BigDecimal("40.5")));
        when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                .thenReturn(List.of(card()));
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a trip nobody has priced answers with the empty shape, not a 404")
        void notPricedYet() {
            TripCostView view = service.get(SCOPE, TRIP);

            assertThat(view.priced()).isFalse();
            assertThat(view.tripId()).isEqualTo(TRIP);
            assertThat(view.components()).isEmpty();
        }

        @Test
        @DisplayName("a trip of another company is not found")
        void foreignTrip() {
            when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.get(SCOPE, TRIP));
        }
    }

    @Nested
    @DisplayName("estimating on demand")
    class Estimating {

        @Test
        @DisplayName("prices the shipment and snapshots the card that did it")
        void snapshotsTheCard() {
            TripCostView view = service.estimate(SCOPE, TRIP);

            assertThat(view.priced()).isTrue();
            assertThat(view.currency()).isEqualTo("PEN");
            // 120.00 base + 40.5 km x 0.85 = 34.43
            assertThat(view.estimatedAmount()).isEqualByComparingTo("154.43");
            assertThat(view.rateCardCode()).isEqualTo("CARD-1");
            assertThat(view.rateCardName()).isEqualTo("Card one");
            assertThat(view.rateCardScope()).isEqualTo(RateCardScope.CARRIER);
            assertThat(view.estimateComplete()).isTrue();
            assertThat(view.components()).extracting(TripCostComponentView::component)
                    .containsExactly(RateComponent.BASE, RateComponent.DISTANCE);
            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.TRIP_COST), any(),
                    eq(AuditAction.COST_ESTIMATED), anyMap());
        }

        @Test
        @DisplayName("a shipment with no route cannot be charged by distance, and says so")
        void noRouteMeansNoDistance() {
            when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.of(trip(true, CARRIER, null)));

            TripCostView view = service.estimate(SCOPE, TRIP);

            assertThat(view.estimatedAmount()).isEqualByComparingTo("120.00");
            assertThat(view.estimateComplete()).isFalse();
            assertThat(view.components())
                    .filteredOn(component -> component.status() == CostComponentStatus.NOT_CALCULABLE)
                    .singleElement()
                    .satisfies(component -> assertThat(component.reason())
                            .isEqualTo(CostComponentReason.DISTANCE_UNKNOWN));
        }

        @Test
        @DisplayName("a route with no reference distance is the same case, and never a distance of zero")
        void routeWithoutDistance() {
            when(routes.findReferenceDistanceKm(ROUTE, COMPANY)).thenReturn(Optional.empty());

            TripCostView view = service.estimate(SCOPE, TRIP);

            assertThat(view.estimatedAmount()).isEqualByComparingTo("120.00");
            assertThat(view.estimateComplete()).isFalse();
        }

        @Test
        @DisplayName("a draft shipment is not priced: everything a price depends on is still moving")
        void draftIsRefused() {
            when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.of(trip(false, CARRIER, ROUTE)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.estimate(SCOPE, TRIP))
                    .withMessageContaining("has not been confirmed");
        }

        @Test
        @DisplayName("a shipment with no carrier has nobody to be priced for")
        void noCarrier() {
            when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.of(trip(true, null, ROUTE)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.estimate(SCOPE, TRIP))
                    .withMessageContaining("no carrier");
        }

        @Test
        @DisplayName("no card covering the shipment is refused out loud on the on-demand path")
        void noCardOnDemand() {
            when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                    .thenReturn(List.of());

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.estimate(SCOPE, TRIP))
                    .withMessageContaining("No active rate card");
        }

        @Test
        @DisplayName("re-estimating rewrites the same row rather than adding a second one")
        void reEstimateInPlace() {
            TripCost existing = new TripCost(COMPANY, TRIP, PLANNING_DATE, "PEN", ACTOR);
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(existing));

            service.estimate(SCOPE, TRIP);
            service.estimate(SCOPE, TRIP);

            assertThat(existing.components()).hasSize(2);
            assertThat(existing.estimatedAmount()).isEqualByComparingTo("154.43");
        }
    }

    @Nested
    @DisplayName("estimating at confirmation")
    class OnConfirmation {

        @Test
        @DisplayName("stays silent when no card covers the shipment, so the plan still confirms")
        void silentWithoutACard() {
            when(rateCardRepository.findByCompanyIdAndCarrierIdAndActiveTrue(COMPANY, CARRIER))
                    .thenReturn(List.of());

            service.estimateOnConfirmation(SCOPE, TRIP, ACTOR);

            verify(tripCostRepository, never()).saveAndFlush(any(TripCost.class));
        }

        @Test
        @DisplayName("stays silent when the shipment has no carrier yet")
        void silentWithoutACarrier() {
            when(trips.findCostableTrip(TRIP, COMPANY)).thenReturn(Optional.of(trip(true, null, ROUTE)));

            service.estimateOnConfirmation(SCOPE, TRIP, ACTOR);

            verify(tripCostRepository, never()).saveAndFlush(any(TripCost.class));
        }

        @Test
        @DisplayName("never restates a settled figure")
        void leavesAClosedCostAlone() {
            TripCost closed = new TripCost(COMPANY, TRIP, PLANNING_DATE, "PEN", ACTOR);
            closed.recordActual(new BigDecimal("300.00"), null, null, ACTOR);
            closed.close(ACTOR);
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(closed));

            service.estimateOnConfirmation(SCOPE, TRIP, ACTOR);

            assertThat(closed.hasEstimate()).isFalse();
            verify(tripCostRepository, never()).saveAndFlush(any(TripCost.class));
        }

        @Test
        @DisplayName("writes the same figure the on-demand path would")
        void sameFigureAsOnDemand() {
            service.estimateOnConfirmation(SCOPE, TRIP, ACTOR);

            verify(tripCostRepository).saveAndFlush(any(TripCost.class));
            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.TRIP_COST), any(),
                    eq(AuditAction.COST_ESTIMATED), anyMap());
        }
    }

    @Nested
    @DisplayName("the actual, and closing it")
    class Actual {

        @Test
        @DisplayName("a shipment with no estimate needs a currency, because there is none to inherit")
        void currencyRequiredWithoutAnEstimate() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.recordActual(SCOPE, TRIP,
                            new ActualCostRequest(new BigDecimal("300.00"), null, null, null)))
                    .withMessageContaining("currency is required");
        }

        @Test
        @DisplayName("an amount in another currency is refused, never converted")
        void currencyMismatch() {
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY))
                    .thenReturn(Optional.of(new TripCost(COMPANY, TRIP, PLANNING_DATE, "PEN", ACTOR)));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.recordActual(SCOPE, TRIP,
                            new ActualCostRequest(new BigDecimal("300.00"), "USD", null, null)))
                    .withMessageContaining("costed in PEN");
        }

        @Test
        @DisplayName("recording it leaves the estimate untouched and reports the variance")
        void variance() {
            TripCost cost = estimated();
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(cost));

            TripCostView view = service.recordActual(SCOPE, TRIP,
                    new ActualCostRequest(new BigDecimal("180.00"), "pen", "F001-4471", "Two hours waiting"));

            assertThat(view.estimatedAmount()).isEqualByComparingTo("154.43");
            assertThat(view.actualAmount()).isEqualByComparingTo("180.00");
            assertThat(view.variance()).isEqualByComparingTo("25.57");
            assertThat(view.actualReference()).isEqualTo("F001-4471");
            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.TRIP_COST), any(),
                    eq(AuditAction.COST_ACTUAL_RECORDED), anyMap());
        }

        @Test
        @DisplayName("a cost with no actual cannot be closed")
        void closeNeedsAnActual() {
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(estimated()));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.close(SCOPE, TRIP))
                    .withMessageContaining("no actual cost");
        }

        @Test
        @DisplayName("a closed cost refuses every write until it is reopened")
        void closedIsFrozen() {
            TripCost cost = estimated();
            cost.recordActual(new BigDecimal("180.00"), null, null, ACTOR);
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(cost));

            assertThat(service.close(SCOPE, TRIP).closed()).isTrue();
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.recordActual(SCOPE, TRIP,
                            new ActualCostRequest(new BigDecimal("999.00"), null, null, null)))
                    .withMessageContaining("is closed");
            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.estimate(SCOPE, TRIP))
                    .withMessageContaining("is closed");

            assertThat(service.reopen(SCOPE, TRIP).closed()).isFalse();
            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.TRIP_COST), any(),
                    eq(AuditAction.COST_REOPENED), anyMap());
        }

        @Test
        @DisplayName("reopening something that is not closed is refused")
        void reopenOpen() {
            when(tripCostRepository.findByTripIdAndCompanyId(TRIP, COMPANY)).thenReturn(Optional.of(estimated()));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.reopen(SCOPE, TRIP))
                    .withMessageContaining("not closed");
        }
    }

    /** A cost carrying the estimate this fixture's card produces, without going through the service. */
    private TripCost estimated() {
        TripCost cost = new TripCost(COMPANY, TRIP, PLANNING_DATE, "PEN", ACTOR);
        cost.recordEstimate(
                TripCostCalculator.calculate(card(), new CostInputs(new BigDecimal("40.5"), null, null, null)),
                card(), ACTOR);
        return cost;
    }

    private static CostableTrip trip(boolean costable, UUID carrierId, UUID routeId) {
        return new CostableTrip(TRIP, COMPANY, "SH-00000042", PLANNING_DATE, carrierId, VEHICLE_TYPE, ORIGIN,
                routeId, BigDecimal.valueOf(1000), BigDecimal.valueOf(12), BigDecimal.valueOf(10), costable);
    }

    /** Base plus per-km, so every test can tell the two lines apart in one figure. */
    private static RateCard card() {
        return new RateCard(COMPANY, "CARD-1", "Card one", CARRIER, RateCardScope.CARRIER, null, null, null, "PEN",
                PLANNING_DATE.minusDays(30), null,
                new RateComponents(new BigDecimal("120.00"), new BigDecimal("0.8500"), null, null, null, null),
                ACTOR);
    }
}
