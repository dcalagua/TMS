package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.DriverReference;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettings;
import com.ebim.tms.shared.settings.CompanySettingsPort;
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

/**
 * The four rules {@code TripService.updateDriver} adds (migration V26): the driver must be one
 * this company may assign, their licence must still be valid on the day they are meant to drive,
 * they must not already be out on another trip that day, and their carrier must not contradict the
 * vehicle's.
 *
 * <p>Mocked rather than database-backed, for the same reason {@link TripExecutionServiceTest} is:
 * none of these rules is about persistence, and the persistence half - the row lock and
 * {@code uq_trip_driver_active_planning_date} - belongs to {@code PlanningApiIntegrationTest},
 * which needs Docker. This file runs everywhere.
 */
class TripDriverAssignmentTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIER = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final LocalDate PLANNING_DATE = LocalDate.of(2026, 8, 20);
    private static final OffsetDateTime PLANNED_DEPARTURE = OffsetDateTime.parse("2026-08-20T08:00:00Z");

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripRepository tripRepository;
    private DriverLookupPort driverLookupPort;
    private AuditRecorder auditRecorder;
    private TripService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        driverLookupPort = mock(DriverLookupPort.class);
        auditRecorder = mock(AuditRecorder.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);
        CompanySettingsPort settings = mock(CompanySettingsPort.class);
        when(settings.settingsOf(any())).thenReturn(CompanySettings.defaults());

        service = new TripService(tripRepository, mock(PlanningRunRepository.class),
                mock(TripOrderAssignmentRepository.class), mock(TripAssignmentService.class),
                mock(OrderPlanningPort.class), mock(VehicleLookupPort.class), driverLookupPort,
                mock(RouteTemplateLookupPort.class), mock(PlanningCapacityService.class), assembler,
                mock(ShipmentEventPublisher.class), mock(TripTenderService.class),
                // The settings port only decides the shipment-number prefix (migration V34); it is
                // stubbed with the product defaults rather than left unstubbed so that a later test
                // in this file which creates a trip fails on its own rule, not on a null prefix.
                mock(TripAlertPublisher.class), settings, actors, auditRecorder);

        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(call -> call.getArgument(0));
        when(assembler.toDetail(any(Trip.class), eq(COMPANY)))
                .thenAnswer(call -> new TripDetailView(null, List.of(), List.of(), List.of(), List.of()));
        when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.of(driver(null, CARRIER)));
    }

    /** A trip walked to {@code target}, locked and ready for the service to find. */
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

    private static DriverReference driver(LocalDate licenseExpiresOn, UUID carrierId) {
        return new DriverReference(DRIVER, "DR-ANA", "Quispe, Ana", "DNI", "12345678", "+51 999 111 222",
                "Q-987654", "A-IIB", licenseExpiresOn, carrierId, true);
    }

    private static TripDriverRequest request(Trip trip, UUID driverId) {
        return new TripDriverRequest(driverId, trip.version());
    }

    @Nested
    @DisplayName("naming a driver")
    class Assigning {

        @Test
        @DisplayName("sets the driver on a draft trip and records a DRIVER_CHANGE, not a generic UPDATE")
        void assignsAndAudits() {
            Trip trip = lockedTrip(TripStatus.DRAFT);

            service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER));

            assertThat(trip.driverId()).isEqualTo(DRIVER);
            verify(auditRecorder).record(eq(SCOPE), eq(AuditAggregateType.TRIP), any(),
                    eq(AuditAction.DRIVER_CHANGE), anyMap());
        }

        @Test
        @DisplayName("still allows a swap after confirmation - a driver calling in sick is not a re-plan")
        void allowedAfterConfirmation() {
            Trip trip = lockedTrip(TripStatus.READY_FOR_DISPATCH);

            service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER));

            assertThat(trip.driverId()).isEqualTo(DRIVER);
        }

        @Test
        @DisplayName("refuses once the vehicle has left - who is driving has stopped being a plan")
        void refusedOnceInTransit() {
            Trip trip = lockedTrip(TripStatus.IN_TRANSIT);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER)))
                    .withMessageContaining("can no longer be changed");
            assertThat(trip.driverId()).isNull();
        }

        @Test
        @DisplayName("clears the driver when none is sent, releasing them for another trip that day")
        void clearsWhenNull() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);
            trip.assignDriver(DRIVER, ACTOR);

            service.updateDriver(SCOPE, TRIP_ID, new TripDriverRequest(null, trip.version()));

            assertThat(trip.driverId()).isNull();
            verify(driverLookupPort, never()).findAssignable(any(), any());
        }

        @Test
        @DisplayName("refuses a stale caller, so two dispatchers cannot both silently win")
        void staleVersionIsRefused() {
            Trip trip = lockedTrip(TripStatus.CONFIRMED);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID,
                            new TripDriverRequest(DRIVER, trip.version() + 7)))
                    .withMessageContaining("changed by someone else");
            assertThat(trip.driverId()).isNull();
        }

        @Test
        @DisplayName("404s a trip of another company before it can learn anything about it")
        void crossTenantTripIsNotFound() {
            UUID unknown = UUID.randomUUID();
            when(tripRepository.findByIdAndCompanyIdForUpdate(unknown, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, unknown, new TripDriverRequest(DRIVER, 0L)));
        }
    }

    @Nested
    @DisplayName("the four assignment rules")
    class Rules {

        @Test
        @DisplayName("refuses a driver of another company or an inactive one - the port answers empty for both")
        void refusesADriverThePortWillNotResolve() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.empty());

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER)))
                    .withMessageContaining("does not reference an active driver");
            assertThat(trip.driverId()).isNull();
        }

        @Test
        @DisplayName("refuses a licence that lapses before the day the trip runs, not merely before today")
        void licenceMustBeValidOnThePlanningDate() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY))
                    .thenReturn(Optional.of(driver(PLANNING_DATE.minusDays(1), CARRIER)));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER)))
                    .withMessageContaining("licence that expired");
            assertThat(trip.driverId()).isNull();
        }

        @Test
        @DisplayName("accepts a licence that expires on the planning date itself")
        void expiryDayIsInclusive() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY))
                    .thenReturn(Optional.of(driver(PLANNING_DATE, CARRIER)));

            service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER));

            assertThat(trip.driverId()).isEqualTo(DRIVER);
        }

        @Test
        @DisplayName("accepts a driver with no expiry on file")
        void unrecordedExpiryNeverBlocks() {
            Trip trip = lockedTrip(TripStatus.DRAFT);

            service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER));

            assertThat(trip.driverId()).isEqualTo(DRIVER);
        }

        @Test
        @DisplayName("refuses a driver whose carrier is not the one running the trip")
        void carrierMustMatch() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY))
                    .thenReturn(Optional.of(driver(null, OTHER_CARRIER)));

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER)))
                    .withMessageContaining("different carrier");
            assertThat(trip.driverId()).isNull();
        }

        @Test
        @DisplayName("accepts a company's own staff driver on a subcontracted vehicle - only both-known contradicts")
        void ownStaffMayDriveASubcontractedTruck() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(driverLookupPort.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.of(driver(null, null)));

            service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER));

            assertThat(trip.driverId()).isEqualTo(DRIVER);
        }

        @Test
        @DisplayName("refuses a driver already out on another active trip the same planning date")
        void refusesADoubleBooking() {
            Trip trip = lockedTrip(TripStatus.DRAFT);
            when(tripRepository.existsByCompanyIdAndDriverIdAndPlanningDateAndStatusNotAndIdNot(
                    COMPANY, DRIVER, PLANNING_DATE, TripStatus.CANCELLED, trip.id())).thenReturn(true);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.updateDriver(SCOPE, TRIP_ID, request(trip, DRIVER)))
                    .withMessageContaining("already assigned to another active trip");
            assertThat(trip.driverId()).isNull();
        }
    }
}
