package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.application.DispatchReadiness.BlockerCode;
import com.ebim.tms.planning.application.DispatchReadiness.Check;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.shared.reference.DriverLookupPort;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.ResourceBlock;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evaluator every reader of "can this trip leave?" shares (ADR-013 section 12). The gate's
 * exact sentences are pinned in {@code TripExecutionServiceTest.DispatchChecks}; this pins what the
 * other readers rely on: every blocker is reported, not only the first, in the gate's order, and a
 * reader asking for some checks never pays for the others.
 */
class DispatchReadinessTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID VEHICLE = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-28T08:00:00Z");
    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO", "Company", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private VehicleLookupPort vehicles;
    private DriverLookupPort drivers;
    private ResourceAvailabilityPort availability;
    private DispatchReadiness readiness;
    private Trip trip;

    @BeforeEach
    void setUp() {
        vehicles = mock(VehicleLookupPort.class);
        drivers = mock(DriverLookupPort.class);
        availability = mock(ResourceAvailabilityPort.class);
        readiness = new DispatchReadiness(vehicles, drivers, availability, mock(CommittedOrderHolds.class));
        trip = mock(Trip.class);
        when(trip.tripNumber()).thenReturn(7);
        when(trip.vehicleId()).thenReturn(VEHICLE);
        when(trip.driverId()).thenReturn(DRIVER);
        when(availability.findBlock(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a trip with nothing wrong has no blockers")
    void ready() {
        when(vehicles.findAssignable(VEHICLE, COMPANY)).thenReturn(Optional.of(mock(
                com.ebim.tms.shared.reference.VehicleCapacityReference.class)));
        when(drivers.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.of(mock(
                com.ebim.tms.shared.reference.DriverReference.class)));

        assertThat(readiness.evaluate(SCOPE, trip, AT)).isEmpty();
    }

    @Test
    @DisplayName("every failing check is reported, in the order the gate tries them")
    void everyBlockerInOrder() {
        when(vehicles.findAssignable(VEHICLE, COMPANY)).thenReturn(Optional.empty());
        when(drivers.findAssignable(DRIVER, COMPANY)).thenReturn(Optional.empty());
        when(trip.awaitsCarrierVehicle()).thenReturn(true);
        when(availability.findBlock(COMPANY, VEHICLE, DRIVER, AT)).thenReturn(
                Optional.of(new ResourceBlock("driver", "LEAVE", AT.plusDays(1))));

        assertThat(readiness.evaluate(SCOPE, trip, AT)).extracting(DispatchReadiness.Blocker::code)
                .containsExactly(BlockerCode.VEHICLE_NOT_OPERABLE, BlockerCode.DRIVER_NOT_ACTIVE,
                        BlockerCode.AWAITING_CARRIER_VEHICLE, BlockerCode.DRIVER_UNAVAILABLE);
    }

    @Test
    @DisplayName("a reader asking only about the carrier never looks up the fleet or the calendar")
    void onlyTheChecksAskedFor() {
        when(trip.awaitsCarrierVehicle()).thenReturn(true);

        assertThat(readiness.evaluate(SCOPE, trip, AT, EnumSet.of(Check.CARRIER_OWNS_VEHICLE)))
                .extracting(DispatchReadiness.Blocker::code)
                .containsExactly(BlockerCode.AWAITING_CARRIER_VEHICLE);
        verifyNoInteractions(vehicles, drivers, availability);
    }

    @Test
    @DisplayName("ADR-014: an order on hold blocks the departure, and the sentence names it and how to get out")
    void heldOrderBlocksDispatch() {
        UUID tripId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        CommittedOrderHolds holds = mock(CommittedOrderHolds.class);
        when(trip.id()).thenReturn(tripId);
        when(holds.onTrips(COMPANY, java.util.List.of(tripId))).thenReturn(java.util.Map.of(tripId,
                java.util.List.of(new com.ebim.tms.shared.reference.OrderHoldPort.HeldOrder(
                        orderId, "ORD-000042", java.util.List.of("COMMERCIAL")))));
        DispatchReadiness withHolds = new DispatchReadiness(vehicles, drivers, availability, holds);

        assertThat(withHolds.evaluate(SCOPE, trip, AT, EnumSet.of(Check.ORDERS_NOT_HELD)))
                .singleElement()
                .satisfies(blocker -> {
                    assertThat(blocker.code()).isEqualTo(BlockerCode.ORDER_HOLD_ON_COMMITTED_TRIP);
                    assertThat(blocker.message()).contains("ORD-000042", "COMMERCIAL", "Release the hold");
                });
        assertThat(DispatchReadiness.ALL).contains(Check.ORDERS_NOT_HELD);
    }

    @Test
    @DisplayName("with no departure time there is nothing to check the calendar against")
    void noTimeNoCalendar() {
        assertThat(readiness.evaluate(SCOPE, trip, null, EnumSet.of(Check.RESOURCES_AVAILABLE))).isEmpty();
        verifyNoInteractions(availability);
    }
}
