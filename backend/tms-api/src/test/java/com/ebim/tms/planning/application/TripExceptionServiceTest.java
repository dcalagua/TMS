package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.TransportEventType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripException;
import com.ebim.tms.planning.domain.TripExceptionStatus;
import com.ebim.tms.planning.domain.TripExceptionType;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.infrastructure.TransportEventRepository;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
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

/**
 * The duplicate rule {@link TripExceptionService} applies to an incoming report, and the rule
 * itself in {@link TripException#restates}.
 *
 * <p><b>Why this needs a test at all.</b> An exception is the row the control tower counts and the
 * bell is keyed on. A problem written twice does not merely read untidily - it says two things went
 * wrong where one did, and the real one sinks in a list ordered by nothing but time. The failure is
 * silent by construction: every duplicate is a successful 200 with a valid row behind it.
 *
 * <p><b>The trip and its stop are mocked, not built</b>, for the reason
 * {@code TripStopExecutionServiceTest} gives: a {@code TripStop} that has never been flushed has no
 * id, and the service finds its stop by id. The exceptions, by contrast, are <em>real</em>
 * {@code TripException} instances wherever the rule under test reads them - a test of a matching
 * rule that stubs the matching would assert nothing.
 */
class TripExceptionServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID STOP_ID = UUID.randomUUID();
    private static final UUID OTHER_STOP_ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    /** Relative to the wall clock, never a literal instant - the trap the sibling tests document. */
    private static final OffsetDateTime REPORTED_AT = OffsetDateTime.now().minusHours(1);

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private TripRepository tripRepository;
    private TripExceptionRepository exceptionRepository;
    private TransportEventRecorder transportEvents;
    private TripAlertPublisher alerts;
    private TripExceptionService service;
    private Trip trip;
    private TripStop stop;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        exceptionRepository = mock(TripExceptionRepository.class);
        transportEvents = mock(TransportEventRecorder.class);
        alerts = mock(TripAlertPublisher.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);
        AuditActorProvider actors = mock(AuditActorProvider.class);
        when(actors.requireAppUserId()).thenReturn(ACTOR);

        service = new TripExceptionService(tripRepository, exceptionRepository,
                mock(TransportEventRepository.class), transportEvents, alerts, assembler, actors);

        stop = mock(TripStop.class);
        when(stop.id()).thenReturn(STOP_ID);
        when(stop.sequence()).thenReturn(1);

        trip = mock(Trip.class);
        when(trip.id()).thenReturn(TRIP_ID);
        when(trip.tripNumber()).thenReturn(1);
        when(trip.isDraft()).thenReturn(false);
        when(trip.stops()).thenReturn(List.of(stop));

        when(tripRepository.findByIdAndCompanyIdForUpdate(TRIP_ID, COMPANY)).thenReturn(Optional.of(trip));
        when(assembler.toDetail(any(Trip.class), eq(COMPANY))).thenAnswer(call -> new TripDetailView(
                null, List.of(), List.of(), List.of(), List.of(), TripRouteMetrics.NONE));
        // The service reads back the id it was given, so a row that was never flushed would fail on
        // a NullPointerException rather than on the rule under test. A spy over the real entity
        // keeps restates() real while lending the row the id JPA would have assigned.
        when(exceptionRepository.saveAndFlush(any(TripException.class))).thenAnswer(call -> {
            TripException saved = spy((TripException) call.getArgument(0));
            doReturn(UUID.randomUUID()).when(saved).id();
            return saved;
        });
    }

    private static TripExceptionRequest report(UUID stopId, TripExceptionType type, String notes) {
        return new TripExceptionRequest(stopId, type, null, notes);
    }

    /** What the repository would hand back for an open problem already on this trip. */
    private void alreadyOpen(TripExceptionType type, UUID stopId, String notes) {
        when(exceptionRepository.findByCompanyIdAndTripIdAndExceptionTypeAndStatus(
                COMPANY, TRIP_ID, type, TripExceptionStatus.OPEN))
                .thenReturn(List.of(new TripException(COMPANY, TRIP_ID, stopId, type, REPORTED_AT, ACTOR, notes)));
    }

    @Nested
    @DisplayName("a repeat of an open problem")
    class Repeats {

        @Test
        @DisplayName("writes no second row, no second timeline entry and no second alert")
        void suppressesTheDuplicate() {
            alreadyOpen(TripExceptionType.CUSTOMER_CLOSED, STOP_ID, "Gate locked.");

            service.report(SCOPE, TRIP_ID, report(STOP_ID, TripExceptionType.CUSTOMER_CLOSED, "Gate locked."));

            verify(exceptionRepository, never()).saveAndFlush(any(TripException.class));
            verifyNoInteractions(transportEvents);
            verifyNoInteractions(alerts);
        }

        /**
         * The case a derived query would have got wrong. {@code trip_stop_id} is null for a
         * trip-level problem, {@code trip_stop_id = null} matches nothing in SQL, and the check
         * would have been silently off for the reports most likely to be sent twice.
         */
        @Test
        @DisplayName("is caught for a trip-level problem too, where the stop is null on both sides")
        void suppressesTheDuplicateWithNoStop() {
            alreadyOpen(TripExceptionType.TRAFFIC_DELAY, null, null);

            service.report(SCOPE, TRIP_ID, report(null, TripExceptionType.TRAFFIC_DELAY, null));

            verify(exceptionRepository, never()).saveAndFlush(any(TripException.class));
            verifyNoInteractions(alerts);
        }

        @Test
        @DisplayName("does not swallow a report that says something different")
        void differentNotesAreADifferentProblem() {
            alreadyOpen(TripExceptionType.TRAFFIC_DELAY, null, "Blocked on the ring road.");

            service.report(SCOPE, TRIP_ID, report(null, TripExceptionType.TRAFFIC_DELAY, "Accident at the bridge."));

            verify(exceptionRepository).saveAndFlush(any(TripException.class));
            verify(transportEvents).record(eq(SCOPE), eq(TRIP_ID), isNull(),
                    eq(TransportEventType.EXCEPTION_REPORTED), any(), eq("Accident at the bridge."), anyMap());
            verify(alerts).exceptionOpened(eq(SCOPE), eq(trip), any(TripException.class), isNull());
        }

        @Test
        @DisplayName("does not swallow the same problem at a different stop")
        void anotherStopIsADifferentProblem() {
            alreadyOpen(TripExceptionType.CUSTOMER_CLOSED, OTHER_STOP_ID, "Gate locked.");

            service.report(SCOPE, TRIP_ID, report(STOP_ID, TripExceptionType.CUSTOMER_CLOSED, "Gate locked."));

            verify(exceptionRepository, times(1)).saveAndFlush(any(TripException.class));
            verify(alerts).exceptionOpened(eq(SCOPE), eq(trip), any(TripException.class), eq(stop));
        }

        @Test
        @DisplayName("is a first report when the trip has nothing open of that type")
        void nothingOpenIsNotADuplicate() {
            when(exceptionRepository.findByCompanyIdAndTripIdAndExceptionTypeAndStatus(
                    COMPANY, TRIP_ID, TripExceptionType.VEHICLE_BREAKDOWN, TripExceptionStatus.OPEN))
                    .thenReturn(List.of());

            service.report(SCOPE, TRIP_ID, report(null, TripExceptionType.VEHICLE_BREAKDOWN, "Clutch gone."));

            verify(exceptionRepository).saveAndFlush(any(TripException.class));
        }
    }

    @Nested
    @DisplayName("the rule itself")
    class TheRule {

        private TripException open(UUID stopId, TripExceptionType type, String notes) {
            return new TripException(COMPANY, TRIP_ID, stopId, type, REPORTED_AT, ACTOR, notes);
        }

        @Test
        @DisplayName("matches the same stop, type and sentence")
        void matchesAnIdenticalStatement() {
            assertThat(open(STOP_ID, TripExceptionType.DELIVERY_REJECTED, "Pallet damaged.")
                    .restates(STOP_ID, TripExceptionType.DELIVERY_REJECTED, "Pallet damaged.")).isTrue();
        }

        @Test
        @DisplayName("matches two trip-level problems that both name no stop")
        void treatsTwoNullStopsAsTheSameScope() {
            assertThat(open(null, TripExceptionType.TRAFFIC_DELAY, null)
                    .restates(null, TripExceptionType.TRAFFIC_DELAY, null)).isTrue();
        }

        @Test
        @DisplayName("does not match a different type")
        void doesNotMatchAnotherType() {
            assertThat(open(STOP_ID, TripExceptionType.CUSTOMER_CLOSED, "Nobody there.")
                    .restates(STOP_ID, TripExceptionType.ADDRESS_NOT_FOUND, "Nobody there.")).isFalse();
        }

        /**
         * A problem that was dealt with and happens again is a new problem - which is what keeps
         * "closed out at 11:00, back at 15:00" two facts instead of one row with a rewritten time.
         */
        @Test
        @DisplayName("never matches once the problem has been resolved")
        void neverMatchesAResolvedProblem() {
            TripException resolved = open(STOP_ID, TripExceptionType.CUSTOMER_CLOSED, "Nobody there.");
            resolved.resolve(REPORTED_AT.plusMinutes(30), ACTOR, "Rescheduled for tomorrow.");

            assertThat(resolved.restates(STOP_ID, TripExceptionType.CUSTOMER_CLOSED, "Nobody there.")).isFalse();
        }
    }
}
