package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
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
 * The control tower's summary (JOB 16, closing debt D7).
 *
 * <p><b>Why this exists.</b> JOB 12 found the control tower had <em>no backend tests at all</em> -
 * the summary counts, the panel capping and the permission rule below were entirely uncovered - and
 * recorded it as D7 rather than folding a backfill into that job. This is the backfill.
 *
 * <p>The case worth the most is {@link Disclosure#unplannedOrdersIsNullNotZeroWithoutOrderRead}. A
 * dispatcher holding {@code monitoring.transport:read} but not {@code orders.order:read} gets the
 * screen and does <b>not</b> get the backlog figure - and gets {@code null} rather than {@code 0},
 * because zero would be this response asserting an empty backlog it was never allowed to look at.
 * That is a one-line rule, it is invisible in every integration test that runs as an admin, and
 * nothing was checking it.
 */
class ControlTowerSummaryTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private TripRepository tripRepository;
    private TripStopRepository stopRepository;
    private TripExceptionRepository exceptionRepository;
    private OrderPlanningPort orderPlanningPort;
    private final com.ebim.tms.shared.reference.SettlementAdvisoryPort settlementAdvisoryPort =
            mock(com.ebim.tms.shared.reference.SettlementAdvisoryPort.class);

    private ControlTowerService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        stopRepository = mock(TripStopRepository.class);
        exceptionRepository = mock(TripExceptionRepository.class);
        orderPlanningPort = mock(OrderPlanningPort.class);
        ResourceAvailabilityPort availabilityPort = mock(ResourceAvailabilityPort.class);

        when(tripRepository.countByStatusForDay(any(), any())).thenReturn(List.of());
        when(tripRepository.countDepartedLateForDay(any(), any())).thenReturn(0L);
        when(tripRepository.countOverdueDepartureForDay(any(), any(), any(), anyCollection())).thenReturn(0L);
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(tripRepository.findAwaitingCarrierVehicleForDay(any(), any(), any())).thenReturn(List.of());
        when(tripRepository.findAwaitingDepartureWithResourcesForDay(any(), any(), any())).thenReturn(List.of());
        when(stopRepository.countOutstandingForDay(any(), any(), any(), any())).thenReturn(0L);
        when(stopRepository.countOutstandingPastWindowForDay(any(), any(), any(), any(), any())).thenReturn(0L);
        when(stopRepository.findOutstandingForDay(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(exceptionRepository.countByStatusForDay(any(), any(), any())).thenReturn(0L);
        when(exceptionRepository.findByStatusForDay(any(), any(), any(), any())).thenReturn(List.of());
        when(availabilityPort.findBlock(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(orderPlanningPort.searchAssignable(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 42));

        // JOB 23: the advisory panel reads two more sources. Stubbed empty here so these
        // tests stay about what they were about; the panel has its own test.
        when(stopRepository.findEtaMissingWindowForDay(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt()))
                .thenReturn(List.of());

        service = new ControlTowerService(mock(TripService.class), mock(TripViewAssembler.class),
                tripRepository, stopRepository, exceptionRepository, mock(DestinationLookupPort.class),
                mock(VehicleLookupPort.class), orderPlanningPort, availabilityPort,
                settlementAdvisoryPort);
    }

    private static CompanyScope scopeWith(Set<Permission> permissions) {
        return new CompanyScope(COMPANY, "CT", "Control Tower Co", "America/Lima", UUID.randomUUID(),
                "ORG", "Org", permissions);
    }

    private static TripRepository.TripStatusCount trips(TripStatus status, long count) {
        return new TripRepository.TripStatusCount() {
            @Override
            public TripStatus getStatus() {
                return status;
            }

            @Override
            public long getTripCount() {
                return count;
            }
        };
    }

    private ControlTowerView overview(LocalDate date) {
        return service.overview(scopeWith(EnumSet.allOf(Permission.class)),
                new ControlTowerFilter(date, null, null, null));
    }

    // --- the disclosure rule -------------------------------------------------------------

    @Nested
    @DisplayName("what a caller is allowed to be told")
    class Disclosure {

        /**
         * The rule D7 left uncovered. This endpoint is guarded by
         * {@code monitoring.transport:read}, which is about transport and not about orders - so a
         * dispatcher who holds it without {@code orders.order:read} still gets the screen and simply
         * does not get this figure.
         *
         * <p><b>Null and not zero.</b> Zero would be the response asserting an empty backlog it was
         * never permitted to look at, which is a claim rather than an omission.
         */
        @Test
        @DisplayName("the unplanned backlog is null, not zero, for a caller who may not read orders")
        void unplannedOrdersIsNullNotZeroWithoutOrderRead() {
            Set<Permission> withoutOrders = EnumSet.allOf(Permission.class);
            withoutOrders.remove(Permission.ORDERS_ORDER_READ);

            ControlTowerView view = service.overview(scopeWith(withoutOrders),
                    new ControlTowerFilter(TODAY, null, null, null));

            assertThat(view.summary().ordersUnplanned()).isNull();
        }

        /** And the query is not even asked - a permission check that still runs the query leaks timing and load. */
        @Test
        @DisplayName("and the orders query is not run at all")
        void doesNotEvenAsk() {
            Set<Permission> withoutOrders = EnumSet.allOf(Permission.class);
            withoutOrders.remove(Permission.ORDERS_ORDER_READ);

            service.overview(scopeWith(withoutOrders), new ControlTowerFilter(TODAY, null, null, null));

            verify(orderPlanningPort, never()).searchAssignable(any(), any());
        }

        @Test
        @DisplayName("a caller who may read orders gets the figure")
        void withOrderReadTheFigureIsReported() {
            assertThat(overview(TODAY).summary().ordersUnplanned()).isEqualTo(42);
        }
    }

    // --- the status roll-up --------------------------------------------------------------

    @Nested
    @DisplayName("the shipment counts")
    class Counts {

        /**
         * CONFIRMED and READY_FOR_DISPATCH are one number on this screen. Both mean "committed and
         * still here", which is the question a dispatcher is asking, and splitting them would put
         * two tiles where one decision is.
         */
        @Test
        @DisplayName("confirmed and ready are summed into one 'scheduled' figure")
        void confirmedAndReadyAreOneNumber() {
            when(tripRepository.countByStatusForDay(any(), any())).thenReturn(List.of(
                    trips(TripStatus.CONFIRMED, 4),
                    trips(TripStatus.READY_FOR_DISPATCH, 3),
                    trips(TripStatus.IN_TRANSIT, 5)));

            ControlTowerSummaryView summary = overview(TODAY).summary();

            assertThat(summary.tripsScheduled()).isEqualTo(7);
            assertThat(summary.tripsInTransit()).isEqualTo(5);
        }

        /** A status the query did not return is zero, not unknown - the reason it groups in SQL. */
        @Test
        @DisplayName("a status with no rows counts as zero")
        void absentStatusIsZero() {
            when(tripRepository.countByStatusForDay(any(), any()))
                    .thenReturn(List.of(trips(TripStatus.DRAFT, 2)));

            ControlTowerSummaryView summary = overview(TODAY).summary();

            assertThat(summary.tripsDraft()).isEqualTo(2);
            assertThat(summary.tripsCompleted()).isZero();
            assertThat(summary.tripsCancelled()).isZero();
        }

        /**
         * Departed-late and overdue are two different phone calls: one already left and was late,
         * the other has not left and still can be helped. They are never summed here.
         */
        @Test
        @DisplayName("departed late and overdue are kept apart")
        void lateAndOverdueAreDifferentFigures() {
            when(tripRepository.countDepartedLateForDay(any(), any())).thenReturn(3L);
            when(tripRepository.countOverdueDepartureForDay(any(), any(), any(), anyCollection())).thenReturn(2L);

            ControlTowerSummaryView summary = overview(TODAY).summary();

            assertThat(summary.tripsDepartedLate()).isEqualTo(3);
            assertThat(summary.tripsOverdue()).isEqualTo(2);
        }
    }

    // --- the window cutoff ---------------------------------------------------------------

    @Nested
    @DisplayName("which service windows can have closed")
    class WindowCutoff {

        /**
         * No window on a future date has closed, and asking the database to confirm that is a query
         * whose answer is already known.
         */
        @Test
        @DisplayName("a future day is not asked about at all")
        void futureDayIsNotQueried() {
            overview(LocalDate.now().plusDays(3));

            verify(stopRepository, never())
                    .countOutstandingPastWindowForDay(any(), any(), any(), any(), any());
        }

        /**
         * A day already past is asked with end-of-day: every window on it has closed, whatever the
         * clock says now.
         */
        @Test
        @DisplayName("a past day is asked with end of day")
        void pastDayUsesEndOfDay() {
            overview(LocalDate.now().minusDays(2));

            ArgumentCaptor<LocalTime> cutoff = ArgumentCaptor.forClass(LocalTime.class);
            verify(stopRepository).countOutstandingPastWindowForDay(
                    eq(COMPANY), any(), any(), any(), cutoff.capture());
            assertThat(cutoff.getValue()).isEqualTo(LocalTime.MAX);
        }

        /**
         * Today is asked with a wall-clock time before the end of the day - the company's own clock
         * and never the server's, which is what stops a deployment's region deciding whose windows
         * have closed.
         */
        @Test
        @DisplayName("today is asked with a real time of day, not end of day")
        void todayUsesTheCompanyClock() {
            overview(LocalDate.now(java.time.ZoneId.of("America/Lima")));

            ArgumentCaptor<LocalTime> cutoff = ArgumentCaptor.forClass(LocalTime.class);
            verify(stopRepository).countOutstandingPastWindowForDay(
                    eq(COMPANY), any(), any(), any(), cutoff.capture());
            assertThat(cutoff.getValue()).isNotEqualTo(LocalTime.MAX);
        }
    }

    // --- tenancy -------------------------------------------------------------------------

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("every count is asked for this company and this day")
        void everyQueryIsScoped() {
            overview(TODAY);

            verify(tripRepository).countByStatusForDay(COMPANY, TODAY);
            verify(tripRepository).countDepartedLateForDay(COMPANY, TODAY);
            verify(exceptionRepository).countByStatusForDay(eq(COMPANY), any(), eq(TODAY));
            verify(stopRepository).countOutstandingForDay(eq(COMPANY), eq(TODAY), any(), any());
        }
    }
}
