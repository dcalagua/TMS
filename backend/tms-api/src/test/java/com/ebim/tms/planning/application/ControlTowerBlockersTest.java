package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.ControlTowerBlockerView.BlockerReason;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.ResourceBlock;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The control tower's blocker panel (JOB 12).
 *
 * <p>Every other panel on that screen reports what has already happened - a stop past its window, a
 * departure already late. This one reports what is <em>about to</em>: the states that make
 * {@code TripExecutionService.dispatch} refuse, surfaced at 06:00 rather than at the gate.
 *
 * <p>Nothing here invents a rule. Each reason is a refusal that already exists in the service, the
 * aggregate and the database - so the thing worth testing is that the panel agrees with them, and
 * that it does not cry wolf about shipments nothing is actually stopping.
 *
 * <p>Mocked collaborators rather than a database: the query predicates belong to the repositories
 * and are asserted where they live, and what this checks is the decision made on top of them.
 */
class ControlTowerBlockersTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID OWN_CARRIER = UUID.randomUUID();
    private static final UUID OTHER_CARRIER = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-09-07T13:00:00Z");

    private TripRepository tripRepository;
    private ResourceAvailabilityPort availabilityPort;
    private final com.ebim.tms.shared.reference.SettlementAdvisoryPort settlementAdvisoryPort =
            mock(com.ebim.tms.shared.reference.SettlementAdvisoryPort.class);

    private ControlTowerService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        availabilityPort = mock(ResourceAvailabilityPort.class);
        TripStopRepository stopRepository = mock(TripStopRepository.class);
        TripExceptionRepository exceptionRepository = mock(TripExceptionRepository.class);
        TripService tripService = mock(TripService.class);
        TripViewAssembler assembler = mock(TripViewAssembler.class);

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

        OrderPlanningPort orderPlanningPort = mock(OrderPlanningPort.class);
        when(orderPlanningPort.searchAssignable(any(), any()))
                .thenReturn(new com.ebim.tms.shared.api.PageResponse<>(List.of(), 0, 1, 0));

        // JOB 23: the advisory panel reads two more sources. Stubbed empty here so these
        // tests stay about what they were about; the panel has its own test.
        when(stopRepository.findEtaMissingWindowForDay(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt()))
                .thenReturn(List.of());

        service = new ControlTowerService(tripService, assembler, tripRepository, stopRepository,
                exceptionRepository, mock(DestinationLookupPort.class), mock(VehicleLookupPort.class),
                orderPlanningPort, availabilityPort, settlementAdvisoryPort);
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "CT", "Control Tower Co", "America/Lima", UUID.randomUUID(),
                "ORG", "Org", EnumSet.allOf(Permission.class));
    }

    private static Trip confirmedTrip(UUID carrierId, UUID acceptedCarrierId) {
        Trip trip = new Trip(COMPANY, RUN, TODAY, 7, "SH-00000007", VEHICLE, carrierId, DEPARTURE, ACTOR);
        trip.confirm(BigDecimal.valueOf(8000), BigDecimal.valueOf(32), 18, ACTOR);
        if (acceptedCarrierId != null) {
            trip.recordCarrierAcceptance(acceptedCarrierId, ACTOR);
        }
        trip.assignDriver(DRIVER, ACTOR);
        return trip;
    }

    private ControlTowerView overview() {
        return service.overview(scope(), new ControlTowerFilter(TODAY, null, null, null));
    }

    @Nested
    @DisplayName("when nothing is stuck")
    class Quiet {

        @Test
        @DisplayName("the panel is empty and the count is zero, which is a fact worth stating")
        void nothingBlocked() {
            ControlTowerView view = overview();

            assertThat(view.blockers()).isEmpty();
            // Zero, not absent. "Nothing is stuck" is what a dispatcher wants told, not inferred.
            assertThat(view.summary().blockedShipments()).isZero();
        }

        /**
         * A vehicle in the workshop next Tuesday does not stop a shipment leaving this morning. The
         * availability question is asked at the shipment's own departure, which is what makes the
         * panel worth reading rather than something everybody learns to ignore.
         */
        @Test
        @DisplayName("a resource blocked at some other time is not a blocker now")
        void blockAtAnotherTimeIsNotABlocker() {
            when(tripRepository.findAwaitingDepartureWithResourcesForDay(any(), any(), any()))
                    .thenReturn(List.of(confirmedTrip(OWN_CARRIER, null)));
            // The port is asked with the trip's planned departure, and answers empty for it.
            when(availabilityPort.findBlock(eq(COMPANY), any(), any(), eq(DEPARTURE)))
                    .thenReturn(Optional.empty());

            assertThat(overview().blockers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a shipment agreed with one carrier and carrying another's truck")
    class AwaitingCarrierVehicle {

        /** Debt D2's state, surfaced. It cannot depart, and until now nothing said so beforehand. */
        @Test
        @DisplayName("is listed, with the reason a planner can act on")
        void isListed() {
            when(tripRepository.findAwaitingCarrierVehicleForDay(any(), any(), any()))
                    .thenReturn(List.of(confirmedTrip(OWN_CARRIER, OTHER_CARRIER)));

            ControlTowerView view = overview();

            assertThat(view.blockers()).hasSize(1);
            assertThat(view.blockers().getFirst().reason()).isEqualTo(BlockerReason.AWAITING_CARRIER_VEHICLE);
            assertThat(view.blockers().getFirst().shipmentNumber()).isEqualTo("SH-00000007");
            assertThat(view.summary().blockedShipments()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a shipment whose resources cannot work")
    class UnavailableResources {

        @Test
        @DisplayName("a vehicle in the workshop at departure is a vehicle blocker, naming when it frees up")
        void vehicleBlocked() {
            when(tripRepository.findAwaitingDepartureWithResourcesForDay(any(), any(), any()))
                    .thenReturn(List.of(confirmedTrip(OWN_CARRIER, null)));
            when(availabilityPort.findBlock(any(), any(), any(), any())).thenReturn(Optional.of(
                    new ResourceBlock("vehicle", "MAINTENANCE", DEPARTURE.plusHours(6))));

            ControlTowerView view = overview();

            assertThat(view.blockers()).hasSize(1);
            assertThat(view.blockers().getFirst().reason()).isEqualTo(BlockerReason.VEHICLE_UNAVAILABLE);
            assertThat(view.blockers().getFirst().detail()).contains("MAINTENANCE");
        }

        @Test
        @DisplayName("a driver off sick is a driver blocker, not a vehicle one")
        void driverBlocked() {
            when(tripRepository.findAwaitingDepartureWithResourcesForDay(any(), any(), any()))
                    .thenReturn(List.of(confirmedTrip(OWN_CARRIER, null)));
            when(availabilityPort.findBlock(any(), any(), any(), any())).thenReturn(Optional.of(
                    new ResourceBlock("driver", "MEDICAL", DEPARTURE.plusDays(3))));

            assertThat(overview().blockers().getFirst().reason()).isEqualTo(BlockerReason.DRIVER_UNAVAILABLE);
        }

        /**
         * A shipment with no planned departure has no instant to ask about. Skipped rather than
         * asked about at {@code now()}, which would report a blocker that depends on when somebody
         * happened to open the screen.
         */
        @Test
        @DisplayName("a shipment with no planned departure is not asked about at all")
        void noDepartureIsNotAsked() {
            Trip noDeparture = new Trip(COMPANY, RUN, TODAY, 8, "SH-00000008", VEHICLE, OWN_CARRIER, null, ACTOR);
            when(tripRepository.findAwaitingDepartureWithResourcesForDay(any(), any(), any()))
                    .thenReturn(List.of(noDeparture));

            assertThat(overview().blockers()).isEmpty();
            org.mockito.Mockito.verify(availabilityPort, org.mockito.Mockito.never())
                    .findBlock(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("more than fits on a screen")
    class Capping {

        /**
         * Capped like every other panel here, with the true total in the summary - so a shortened
         * list reads as "the first twenty of twenty-five" rather than as "twenty-five".
         */
        @Test
        @DisplayName("the list is capped and the summary still counts what was shown")
        void capsTheList() {
            List<Trip> many = java.util.stream.IntStream.range(0, 25)
                    .mapToObj(index -> confirmedTrip(OWN_CARRIER, OTHER_CARRIER))
                    .toList();
            when(tripRepository.findAwaitingCarrierVehicleForDay(any(), any(), any())).thenReturn(many);

            ControlTowerView view = overview();

            assertThat(view.blockers()).hasSize(20);
            assertThat(view.summary().blockedShipments()).isEqualTo(20);
        }
    }
}
