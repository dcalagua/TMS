package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.ControlTowerAdvisoryView.AdvisoryType;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripExceptionRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.planning.infrastructure.TripStopRepository;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OrderPlanningPort;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.SettlementAdvisoryPort;
import com.ebim.tms.shared.reference.SettlementAdvisoryPort.SettlementAdvisory;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The advisory panel, and the line it must never cross (JOB 23, Control Tower V3).
 *
 * <p><b>The rule these tests exist for:</b> an advisory is never a blocker. A blocker is a state
 * that makes {@code dispatch} refuse; an advisory is something a supervisor should know and may
 * reasonably do nothing about today. JOB 12 kept the blockers panel to hard stops and named mixing
 * them as the thing not to do — V3 adds a second stream beside it, and
 * {@link #anAdvisoryIsNeverABlocker} is the assertion that keeps it there.
 *
 * <p>The second rule: <b>the tower owns none of this state.</b> It reads a discrepancy through a
 * port and links to it. Two records of one dispute would drift apart the first time somebody
 * resolved the wrong one.
 */
class ControlTowerAdvisoriesTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    private TripRepository tripRepository;
    private TripStopRepository stopRepository;
    private SettlementAdvisoryPort settlementAdvisoryPort;
    private ControlTowerService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        stopRepository = mock(TripStopRepository.class);
        settlementAdvisoryPort = mock(SettlementAdvisoryPort.class);
        TripExceptionRepository exceptionRepository = mock(TripExceptionRepository.class);
        OrderPlanningPort orderPlanningPort = mock(OrderPlanningPort.class);
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
        when(stopRepository.findEtaMissingWindowForDay(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(exceptionRepository.countByStatusForDay(any(), any(), any())).thenReturn(0L);
        when(exceptionRepository.findByStatusForDay(any(), any(), any(), any())).thenReturn(List.of());
        when(availabilityPort.findBlock(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(orderPlanningPort.searchAssignable(any(), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 1, 0));
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt())).thenReturn(List.of());

        service = new ControlTowerService(mock(TripService.class), mock(TripViewAssembler.class),
                tripRepository, stopRepository, exceptionRepository, mock(DestinationLookupPort.class),
                mock(VehicleLookupPort.class), orderPlanningPort, availabilityPort, settlementAdvisoryPort);
    }

    private static CompanyScope scope() {
        return new CompanyScope(COMPANY, "CT", "Control Tower Co", "America/Lima", UUID.randomUUID(),
                "ORG", "Org", EnumSet.allOf(Permission.class));
    }

    private ControlTowerView overview() {
        return service.overview(scope(), new ControlTowerFilter(TODAY, null, null, null));
    }

    private Trip tripOfDay(String shipmentNumber) {
        Trip trip = new Trip(COMPANY, UUID.randomUUID(), TODAY, 1, shipmentNumber,
                null, null, null, UUID.randomUUID());
        return trip;
    }

    @Test
    @DisplayName("an open settlement discrepancy appears as an advisory, with its money and its link")
    void settlementDiscrepancyBecomesAnAdvisory() {
        Trip trip = tripOfDay("SHP-0001");
        UUID discrepancyId = UUID.randomUUID();
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of(trip));
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt())).thenReturn(List.of(
                new SettlementAdvisory(discrepancyId, UUID.randomUUID(), "F001-123", trip.id(),
                        "AMOUNT_ABOVE_TOLERANCE", new BigDecimal("140.00"), "PEN",
                        "The carrier invoiced 140.00 more than we expected.")));

        ControlTowerView view = overview();

        assertThat(view.advisories()).singleElement().satisfies(advisory -> {
            assertThat(advisory.type()).isEqualTo(AdvisoryType.SETTLEMENT_DISCREPANCY_OPEN);
            assertThat(advisory.shipmentNumber()).isEqualTo("SHP-0001");
            assertThat(advisory.amount()).isEqualByComparingTo("140.00");
            // The id of the record in ITS module, so the UI links to settlement rather than
            // offering to resolve it here.
            assertThat(advisory.sourceId()).isEqualTo(discrepancyId);
        });
    }

    @Test
    @DisplayName("an advisory is never a blocker, and never counted as one")
    void anAdvisoryIsNeverABlocker() {
        Trip trip = tripOfDay("SHP-0002");
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of(trip));
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt())).thenReturn(List.of(
                new SettlementAdvisory(UUID.randomUUID(), UUID.randomUUID(), "F001-9", trip.id(),
                        "AMOUNT_ABOVE_TOLERANCE", new BigDecimal("0.40"), "PEN", "Forty cents.")));

        ControlTowerView view = overview();

        // The whole point of JOB 23. Forty cents of rounding must not make a shipment look like it
        // cannot depart - once a panel has cried wolf, the truck that genuinely cannot leave is one
        // row among forty.
        assertThat(view.advisories()).hasSize(1);
        assertThat(view.blockers()).isEmpty();
        assertThat(view.summary().blockedShipments()).isZero();
        assertThat(view.summary().openAdvisories()).isEqualTo(1);
    }

    @Test
    @DisplayName("an advisory is not an operational exception either")
    void anAdvisoryIsNotAReportedException() {
        Trip trip = tripOfDay("SHP-0003");
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of(trip));
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt())).thenReturn(List.of(
                new SettlementAdvisory(UUID.randomUUID(), UUID.randomUUID(), "F001-3", trip.id(),
                        "AMOUNT_ABOVE_TOLERANCE", null, "PEN", "Could not be compared.")));

        ControlTowerView view = overview();

        // Three streams, three counts. A reported exception (V27) is somebody saying the truck broke
        // down; an advisory is the system noticing something. Merging them would lose who said it.
        assertThat(view.advisories()).hasSize(1);
        assertThat(view.openExceptions()).isEmpty();
        assertThat(view.summary().openExceptions()).isZero();
    }

    @Test
    @DisplayName("a difference the two sides could not be compared on stays null, never zero")
    void anUncomparableDifferenceIsNotZero() {
        Trip trip = tripOfDay("SHP-0004");
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of(trip));
        when(settlementAdvisoryPort.findOpenDiscrepancies(any(), any(), anyInt())).thenReturn(List.of(
                new SettlementAdvisory(UUID.randomUUID(), UUID.randomUUID(), "F001-4", trip.id(),
                        "NO_EXPECTED_COST", null, "PEN", "We never costed this shipment.")));

        ControlTowerView view = overview();

        // Zero would read as "the invoice agrees", which is the opposite of what a null means here.
        // V46's rule, carried through the port rather than flattened on the way out.
        assertThat(view.advisories()).singleElement()
                .satisfies(advisory -> assertThat(advisory.amount()).isNull());
    }

    @Test
    @DisplayName("a quiet day has an empty advisory panel and a zero count, not a missing one")
    void quietDay() {
        ControlTowerView view = overview();

        assertThat(view.advisories()).isEmpty();
        // Zero stated rather than inferred from an empty list - "nothing to worry about" is a fact
        // a supervisor wants told, the same reason blockedShipments shows zero.
        assertThat(view.summary().openAdvisories()).isZero();
    }

    @Test
    @DisplayName("settlement is not asked anything on a day with no shipments")
    void noTripsAsksSettlementNothing() {
        when(tripRepository.findByCompanyIdAndPlanningDateAndStatusIn(any(), any(), any(), any()))
                .thenReturn(List.of());

        ControlTowerView view = overview();

        assertThat(view.advisories()).isEmpty();
        org.mockito.Mockito.verify(settlementAdvisoryPort, org.mockito.Mockito.never())
                .findOpenDiscrepancies(any(), any(), anyInt());
    }

    @Test
    @DisplayName("cancelled shipments raise no advisories")
    void cancelledRaisesNothing() {
        ControlTowerView view = overview();

        // ADVISORY_TRIP_STATES excludes CANCELLED: there is nothing worth telling anybody about a
        // shipment that is not happening.
        assertThat(TripStatus.values()).contains(TripStatus.CANCELLED);
        assertThat(view.advisories()).isEmpty();
    }
}
