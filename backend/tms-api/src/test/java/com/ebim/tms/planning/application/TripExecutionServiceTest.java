package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.ShipmentEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The rules {@link TripExecutionService} adds on top of the transition table: the version check,
 * the idempotent retry, the vehicle re-check, and what an operator-supplied time may be.
 *
 * <p>Mocked rather than database-backed on purpose - none of these rules is about persistence, and
 * the persistence half (row locks, the V25 CHECK constraints, the outbox row actually landing)
 * belongs to {@code PlanningApiIntegrationTest}, which needs Docker. This file runs everywhere.
 *
 * <p><b>No literal instants.</b> {@code Trip.confirm} stamps {@code confirmedAt} from the wall
 * clock, and the service refuses a time before it, so a test that hard-coded "07:35Z" would pass
 * or fail depending on what time of day it ran. Every time below is expressed relative to the
 * timestamp the trip itself recorded.
 */
class TripExecutionServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID VEHICLE_TYPE = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime PLANNED_DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");

    /** Any instant that is unambiguously before anything this test can record. */
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    /**
     * The id the service is called with. {@link Trip}'s own id is assigned by JPA and stays null
     * without a database, so the trip is looked up by this one instead - which is also closer to
     * what a controller does: it holds a path variable, not an entity.
     */
    private static final UUID TRIP_ID = UUID.randomUUID();

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private final CompanyScope scope = SCOPE;

    private TripRepository tripRepository;
    private VehicleLookupPort vehicleLookupPort;
    private DriverLookupPort driverLookupPort;
    private ShipmentEventPublisher events;
    private TripAlertPublisher alerts;
    private OrderExecutionPropagator orderExecution;
    private TripExecutionService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        vehicleLookupPort = mock(VehicleLookupPort.class);
        driverLookupPort = mock(DriverLookupPort.class);
        events = mock(ShipmentEventPublisher.class);
        alerts = mock(TripAlertPublisher.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        orderExecution = mock(OrderExecutionPropagator.class);
        service = new TripExecutionService(tripRepository, vehicleLookupPort, driverLookupPort, events,
                mock(TripTenderService.class), alerts, assembler, orderExecution, actors);
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(call -> call.getArgument(0));
        when(assembler.toDetail(any(Trip.class), eq(COMPANY)))
                .thenAnswer(call -> new TripDetailView(null, List.of(), List.of(), List.of(), List.of(), TripRouteMetrics.NONE));
        when(vehicleLookupPort.findAssignable(VEHICLE, COMPANY)).thenReturn(Optional.of(availableVehicle()));
        when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.of(driverWithLicenceExpiring(null)));
    }

    private static VehicleCapacityReference availableVehicle() {
        return new VehicleCapacityReference(VEHICLE, "VH-1", "ABC-123", CARRIER, "Carrier One", VEHICLE_TYPE,
                "TRUCK-8T", BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, true, "AVAILABLE");
    }

    /** @param expiresOn null for a driver whose licence expiry is simply not on file */
    private static DriverReference driverWithLicenceExpiring(LocalDate expiresOn) {
        return new DriverReference(DRIVER, "DR-1", "Quispe, Ana", "DNI", "12345678", "+51 999 111 222",
                "Q-987654", "A-IIB", expiresOn, CARRIER, true);
    }

    /**
     * A trip walked to {@code target} through the entity's own transitions, with each execution
     * time equal to the previous one - the tightest legal ordering, so any refusal a test sees
     * comes from the rule under test and not from the fixture drifting.
     */
    private Trip lockedTrip(TripStatus target) {
        Trip trip = new Trip(COMPANY, RUN, PLANNING_DATE, 1, "SH-00000001", VEHICLE, CARRIER, PLANNED_DEPARTURE, ACTOR);
        if (target != TripStatus.DRAFT) {
            trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
        }
        if (target == TripStatus.READY_FOR_DISPATCH || target == TripStatus.IN_TRANSIT) {
            trip.markReadyForDispatch(trip.confirmedAt(), ACTOR);
        }
        if (target == TripStatus.IN_TRANSIT) {
            trip.dispatch(trip.readyAt(), ACTOR);
        }
        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        return trip;
    }

    /** {@link #lockedTrip} with a driver named on it - the shape every driver rule below needs. */
    private Trip lockedTripWithDriver(TripStatus target) {
        Trip trip = lockedTrip(target);
        trip.assignDriver(DRIVER, ACTOR);
        return trip;
    }

    /** The current version, so a request is "not stale" - the case every rule test wants. */
    private static TripExecutionRequest current(Trip trip, OffsetDateTime occurredAt) {
        return new TripExecutionRequest(trip.version(), occurredAt);
    }

    @Nested
    @DisplayName("markReadyForDispatch")
    class MarkReady {

        @Test
        @DisplayName("moves a confirmed trip and publishes SHIPMENT_READY with the operator's own time")
        void publishesTheOperatorsTime() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);
            OffsetDateTime reportedAt = trip.confirmedAt().plusSeconds(1);

            service.markReadyForDispatch(scope, TRIP_ID, current(trip, reportedAt));

            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
            assertThat(trip.readyAt()).isEqualTo(reportedAt);
            assertThat(trip.readyBy()).isEqualTo(ACTOR);
            verify(events).publish(eq(scope), eq(trip), eq(ShipmentEventType.SHIPMENT_READY), eq(reportedAt), anyMap());
        }

        @Test
        @DisplayName("refuses a trip whose vehicle has since gone out of service")
        void revalidatesTheVehicle() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);
            when(vehicleLookupPort.findAssignable(VEHICLE, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID, current(trip, null)))
                    .withMessageContaining("no longer active and available");
            assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("refuses a ready time earlier than the confirmation it follows")
        void timeMustNotPrecedeConfirmation() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID, current(trip, LONG_AGO)))
                    .withMessageContaining("before the trip was confirmed");
            assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
        }

        @Test
        @DisplayName("refuses a draft trip with a message naming both states")
        void refusesAnIllegalMove() {
            Trip trip = lockedTrip(TripStatus.DRAFT);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID, current(trip, null)))
                    .withMessageContaining("DRAFT")
                    .withMessageContaining("READY_FOR_DISPATCH");
        }
    }

    @Nested
    @DisplayName("dispatch")
    class Dispatch {

        @Test
        @DisplayName("records the real departure and leaves the planned one alone")
        void recordsBesideThePlan() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);
            OffsetDateTime left = trip.readyAt().plusSeconds(1);

            service.dispatch(scope, TRIP_ID, current(trip, left));

            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
            assertThat(trip.actualDepartureAt()).isEqualTo(left);
            assertThat(trip.plannedDepartureAt()).isEqualTo(PLANNED_DEPARTURE);
        }

        @Test
        @DisplayName("defaults an omitted time to now rather than leaving it null")
        void omittedTimeMeansNow() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);
            OffsetDateTime before = OffsetDateTime.now();

            service.dispatch(scope, TRIP_ID, current(trip, null));

            assertThat(trip.actualDepartureAt()).isBetween(before, OffsetDateTime.now());
        }

        @Test
        @DisplayName("refuses a departure in the future")
        void noFutureDepartures() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);
            OffsetDateTime tomorrow = OffsetDateTime.now().plusDays(1);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.dispatch(scope, TRIP_ID, current(trip, tomorrow)))
                    .withMessageContaining("cannot be in the future");
            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
        }

        @Test
        @DisplayName("refuses a departure earlier than the moment the trip was made ready")
        void timeMustNotPrecedeReady() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.dispatch(scope, TRIP_ID, current(trip, LONG_AGO)))
                    .withMessageContaining("before the trip was made ready");
        }
    }

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("closes a trip that is on the road even if its vehicle has since been grounded")
        void doesNotRevalidateTheVehicle() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);
            when(vehicleLookupPort.findAssignable(VEHICLE, COMPANY)).thenReturn(Optional.empty());
            OffsetDateTime back = trip.actualDepartureAt().plusSeconds(1);

            service.complete(scope, TRIP_ID, current(trip, back));

            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
            assertThat(trip.actualCompletionAt()).isEqualTo(back);
        }

        @Test
        @DisplayName("refuses a completion earlier than the departure it follows")
        void timeMustNotPrecedeDeparture() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.complete(scope, TRIP_ID, current(trip, LONG_AGO)))
                    .withMessageContaining("before the vehicle departed");
        }

        @Test
        @DisplayName("publishes SHIPMENT_COMPLETED")
        void publishesTheEvent() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);

            service.complete(scope, TRIP_ID, current(trip, trip.actualDepartureAt().plusSeconds(1)));

            ArgumentCaptor<ShipmentEventType> type = ArgumentCaptor.forClass(ShipmentEventType.class);
            verify(events).publish(eq(scope), eq(trip), type.capture(), any(OffsetDateTime.class), anyMap());
            assertThat(type.getValue()).isEqualTo(ShipmentEventType.SHIPMENT_COMPLETED);
        }
    }

    /**
     * Which transitions reach the alert bell (migration V32).
     *
     * <p>The routing only, not the wording: whether a departure is late enough to say anything about
     * is {@code TripAlertPublisher}'s decision and is tested there, against the real
     * {@code DepartureDelay} rule. What matters here is that the service hands every departure and
     * every completion over, and hands nothing else - the failure mode being a bell that either
     * never rings or rings on every routine step until nobody reads it.
     */
    @Nested
    @DisplayName("alerts")
    class Alerts {

        @Test
        @DisplayName("hands every departure to the alert publisher, with the time it actually left")
        void departureIsAnnounced() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);
            OffsetDateTime left = trip.readyAt().plusSeconds(1);

            service.dispatch(scope, TRIP_ID, current(trip, left));

            verify(alerts).departed(scope, trip, left);
        }

        @Test
        @DisplayName("hands a completion over too")
        void completionIsAnnounced() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);
            OffsetDateTime back = trip.actualDepartureAt().plusSeconds(1);

            service.complete(scope, TRIP_ID, current(trip, back));

            verify(alerts).completed(scope, trip, back);
        }

        @Test
        @DisplayName("says nothing when a shipment is merely declared ready")
        void readyIsNotWorthAnAlert() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);

            service.markReadyForDispatch(scope, TRIP_ID, current(trip, null));

            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
            verifyNoInteractions(alerts);
        }

        @Test
        @DisplayName("says nothing when the transition was refused")
        void refusedTransitionsAnnounceNothing() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.dispatch(scope, TRIP_ID, current(trip, LONG_AGO)));

            verifyNoInteractions(alerts);
        }
    }

    /**
     * The driver half of the "is this shipment still fit to leave" check (migration V26). Every
     * case here mirrors one the vehicle already has, plus the two the vehicle has no analogue for:
     * a licence expiring, and a trip with no driver at all - which is legal in every state and
     * must therefore pass silently.
     */
    @Nested
    @DisplayName("the driver re-check")
    class DriverRules {

        @Test
        @DisplayName("refuses to make ready a trip whose driver has since been deactivated")
        void refusesAnInactiveDriver() {
            Trip trip = lockedTripWithDriver(TripStatus.CONFIRMED);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID, current(trip, null)))
                    .withMessageContaining("no longer active");
            assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("refuses to dispatch a driver whose licence ran out since the trip was planned")
        void refusesAnExpiredLicence() {
            Trip trip = lockedTripWithDriver(TripStatus.READY_FOR_DISPATCH);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY))
                    .thenReturn(Optional.of(driverWithLicenceExpiring(companyToday().minusDays(1))));

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.dispatch(scope, TRIP_ID, current(trip, null)))
                    .withMessageContaining("licence that expired");
            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("lets through a licence expiring today - the last day is still a valid day")
        void expiryDayIsInclusive() {
            Trip trip = lockedTripWithDriver(TripStatus.READY_FOR_DISPATCH);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY))
                    .thenReturn(Optional.of(driverWithLicenceExpiring(companyToday())));

            service.dispatch(scope, TRIP_ID, current(trip, null));

            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
        }

        @Test
        @DisplayName("lets through a driver with no expiry on file - not knowing is not evidence of expiry")
        void unrecordedExpiryNeverBlocks() {
            Trip trip = lockedTripWithDriver(TripStatus.CONFIRMED);

            service.markReadyForDispatch(scope, TRIP_ID, current(trip, null));

            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
        }

        @Test
        @DisplayName("asks nothing of a trip that names no driver - naming one is never required")
        void noDriverIsLegal() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);

            service.markReadyForDispatch(scope, TRIP_ID, current(trip, null));

            assertThat(trip.status()).isEqualTo(TripStatus.READY_FOR_DISPATCH);
            verify(driverLookupPort, never()).findAssignable(any(), any());
        }

        @Test
        @DisplayName("closes a trip that is on the road even if its driver has since been deactivated")
        void completionDoesNotRevalidateTheDriver() {
            Trip trip = lockedTripWithDriver(TripStatus.IN_TRANSIT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.empty());

            service.complete(scope, TRIP_ID, current(trip, trip.actualDepartureAt().plusSeconds(1)));

            assertThat(trip.status()).isEqualTo(TripStatus.COMPLETED);
        }

        /** The day the service judges a licence by: today in the company's zone, not the server's. */
        private LocalDate companyToday() {
            return SCOPE.today();
        }
    }

    @Nested
    @DisplayName("concurrency and retries")
    class Concurrency {

        @Test
        @DisplayName("answers a retry of an action that already succeeded, stale version and all")
        void isIdempotent() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);
            OffsetDateTime departedAt = trip.actualDepartureAt();

            // Version 999 is nowhere near the persisted one - a retry always holds a stale view.
            service.dispatch(scope, TRIP_ID, new TripExecutionRequest(999L, null));

            assertThat(trip.status()).isEqualTo(TripStatus.IN_TRANSIT);
            assertThat(trip.actualDepartureAt()).isEqualTo(departedAt);
            // The point of the idempotent branch: no second event and no second audit row.
            verifyNoInteractions(events);
            verify(tripRepository, never()).saveAndFlush(any(Trip.class));
        }

        @Test
        @DisplayName("refuses a genuinely stale caller on a state that has not been reached yet")
        void staleVersionIsRefused() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID,
                            new TripExecutionRequest(trip.version() + 7, null)))
                    .withMessageContaining("changed by someone else");
            assertThat(trip.status()).isEqualTo(TripStatus.CONFIRMED);
        }

        @Test
        @DisplayName("requires a version at all")
        void versionIsMandatory() {
            lockedTrip(TripStatus.CONFIRMED);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.markReadyForDispatch(scope, TRIP_ID,
                            new TripExecutionRequest(null, null)))
                    .withMessageContaining("version is required");
        }

        @Test
        @DisplayName("takes the row lock before doing anything, and 404s a trip of another company")
        void locksAndScopes() {
            UUID unknown = UUID.randomUUID();
            when(tripRepository.findByIdAndCompanyIdForUpdate(unknown, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.dispatch(scope, unknown, new TripExecutionRequest(0L, null)));
            verify(tripRepository, times(1)).findByIdAndCompanyIdForUpdate(unknown, COMPANY);
        }
    }
}
