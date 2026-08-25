package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.StopExecutionStatus;
import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
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
 * The rules {@link TripStopExecutionService} adds on top of the stop transition table: the trip
 * must be on the road, a retry is not an error, a time cannot be in the future or before the
 * vehicle left, and a stop that was not served opens an exception with a typed reason.
 *
 * <p><b>The trip and its stop are mocked, not built.</b> The service finds its stop by id, and a
 * {@code TripStop} that has never been flushed has none - JPA assigns it. Mocking is what lets the
 * service's own rules be tested without a database; the state machine those rules protect is
 * asserted against real entities in {@code planning.domain.TripStopExecutionTest}, and the two
 * meet in {@code PlanningApiIntegrationTest}, which needs Docker.
 */
class TripStopExecutionServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID STOP_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    /**
     * Relative to the wall clock, never a literal instant. The service compares operator-supplied
     * times against {@code now()}, so a hard-coded departure would make these tests pass or fail
     * depending on the time of day they ran - the same trap {@code TripExecutionServiceTest}
     * documents.
     */
    private static final OffsetDateTime DEPARTED_AT = OffsetDateTime.now().minusHours(3);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripRepository tripRepository;
    private TripExceptionRepository exceptionRepository;
    private TransportEventRecorder transportEvents;
    private TripStopExecutionService service;
    private Trip trip;
    private TripStop stop;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        exceptionRepository = mock(TripExceptionRepository.class);
        transportEvents = mock(TransportEventRecorder.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new TripStopExecutionService(
                tripRepository, exceptionRepository, transportEvents, mock(TripAlertPublisher.class),
                assembler, actors);

        stop = mock(TripStop.class);
        when(stop.id()).thenReturn(STOP_ID);
        when(stop.sequence()).thenReturn(1);
        when(stop.executionStatus()).thenReturn(StopExecutionStatus.PENDING);

        trip = mock(Trip.class);
        when(trip.id()).thenReturn(TRIP_ID);
        when(trip.tripNumber()).thenReturn(1);
        when(trip.status()).thenReturn(TripStatus.IN_TRANSIT);
        when(trip.stops()).thenReturn(List.of(stop));
        when(trip.actualDepartureAt()).thenReturn(DEPARTED_AT);

        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        when(tripRepository.saveAndFlush(any(Trip.class))).thenAnswer(call -> call.getArgument(0));
        when(assembler.toDetail(any(Trip.class), eq(COMPANY)))
                .thenAnswer(call -> new TripDetailView(null, List.of(), List.of(), List.of(), List.of()));
        when(exceptionRepository.saveAndFlush(any(TripException.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    private static TripStopExecutionRequest at(OffsetDateTime occurredAt) {
        return new TripStopExecutionRequest(occurredAt, null);
    }

    @Nested
    @DisplayName("before it records anything")
    class Preconditions {

        @Test
        @DisplayName("refuses a stop on a trip that has not been dispatched")
        void refusesWhenTheTripIsNotOnTheRoad() {
            when(trip.status()).thenReturn(TripStatus.READY_FOR_DISPATCH);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.arrive(SCOPE, TRIP_ID, STOP_ID, at(null)))
                    .withMessageContaining("READY_FOR_DISPATCH");
            verifyNoInteractions(transportEvents);
        }

        @Test
        @DisplayName("refuses a stop id that belongs to another trip")
        void refusesAStopItDoesNotHave() {
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> service.arrive(SCOPE, TRIP_ID, UUID.randomUUID(), at(null)));
        }

        @Test
        @DisplayName("refuses a move the stop's transition table does not allow")
        void refusesAnIllegalMove() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.PENDING);

            assertThatExceptionOfType(ConflictException.class)
                    .isThrownBy(() -> service.startService(SCOPE, TRIP_ID, STOP_ID, at(null)))
                    .withMessageContaining("IN_SERVICE");
            verifyNoInteractions(transportEvents);
        }

        @Test
        @DisplayName("answers a retry of an action that already succeeded with the trip, not an error")
        void repeatingAnOutcomeIsIdempotent() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.ARRIVED);

            assertThat(service.arrive(SCOPE, TRIP_ID, STOP_ID, at(null))).isNotNull();

            verify(trip, never()).recordStopOutcome(any(), any(), any(), any(), any());
            verifyNoInteractions(transportEvents);
        }
    }

    @Nested
    @DisplayName("the time it accepts")
    class Times {

        @Test
        @DisplayName("refuses one in the future beyond the clock-skew tolerance")
        void refusesAFutureTime() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.arrive(SCOPE, TRIP_ID, STOP_ID,
                            at(OffsetDateTime.now().plusHours(1))))
                    .withMessageContaining("future");
        }

        @Test
        @DisplayName("refuses an arrival earlier than the departure it followed")
        void refusesAnArrivalBeforeTheVehicleLeft() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.arrive(SCOPE, TRIP_ID, STOP_ID, at(DEPARTED_AT.minusMinutes(1))))
                    .withMessageContaining("cannot be before");
        }

        @Test
        @DisplayName("accepts one that is merely earlier than now - a backdated arrival")
        void acceptsABackdatedTime() {
            service.arrive(SCOPE, TRIP_ID, STOP_ID, at(DEPARTED_AT.plusMinutes(30)));

            verify(trip).recordStopOutcome(eq(STOP_ID), eq(StopExecutionStatus.ARRIVED),
                    eq(DEPARTED_AT.plusMinutes(30)), isNull(), eq(ACTOR));
        }

        @Test
        @DisplayName("does not constrain a skip, which records the moment a decision was taken")
        void aSkipHasNoOrderingToRespect() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.PENDING);

            service.skip(SCOPE, TRIP_ID, STOP_ID, new TripStopFailureRequest(DEPARTED_AT.minusHours(2),
                    TripExceptionType.CUSTOMER_CLOSED, null));

            verify(trip).recordStopOutcome(eq(STOP_ID), eq(StopExecutionStatus.SKIPPED), any(), isNull(),
                    eq(ACTOR));
        }
    }

    @Nested
    @DisplayName("what it writes")
    class Writes {

        @Test
        @DisplayName("appends one timeline entry naming the stop it happened at")
        void logsTheArrival() {
            service.arrive(SCOPE, TRIP_ID, STOP_ID, at(null));

            verify(transportEvents).record(eq(SCOPE), eq(TRIP_ID), eq(STOP_ID),
                    eq(TransportEventType.ARRIVED_AT_STOP), any(), isNull(), anyMap());
        }

        @Test
        @DisplayName("opens an exception carrying the typed reason when a delivery does not happen")
        void failingAStopOpensAnException() {
            service.fail(SCOPE, TRIP_ID, STOP_ID, new TripStopFailureRequest(null,
                    TripExceptionType.DELIVERY_REJECTED, "refused at the dock"));

            ArgumentCaptor<TripException> captor = ArgumentCaptor.forClass(TripException.class);
            verify(exceptionRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().exceptionType()).isEqualTo(TripExceptionType.DELIVERY_REJECTED);
            assertThat(captor.getValue().tripStopId()).isEqualTo(STOP_ID);
            assertThat(captor.getValue().isOpen()).isTrue();
            assertThat(captor.getValue().notes()).isEqualTo("refused at the dock");

            verify(transportEvents).record(eq(SCOPE), eq(TRIP_ID), eq(STOP_ID),
                    eq(TransportEventType.STOP_FAILED), any(), eq("refused at the dock"), anyMap());
        }

        @Test
        @DisplayName("opens no exception for an ordinary completion")
        void completingAStopOpensNothing() {
            when(stop.executionStatus()).thenReturn(StopExecutionStatus.ARRIVED);

            service.complete(SCOPE, TRIP_ID, STOP_ID, at(null));

            verify(exceptionRepository, never()).saveAndFlush(any(TripException.class));
        }

        @Test
        @DisplayName("demands a sentence when the reason chosen is OTHER, which says nothing on its own")
        void otherRequiresNotes() {
            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.skip(SCOPE, TRIP_ID, STOP_ID,
                            new TripStopFailureRequest(null, TripExceptionType.OTHER, "   ")))
                    .withMessageContaining("notes");
            verifyNoInteractions(transportEvents);
        }
    }
}
