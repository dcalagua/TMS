package com.ebim.tms.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.RoutingSource;
import com.ebim.tms.shared.reference.TravelEstimate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a shipment's run is measured (migration V38).
 *
 * <p>The routing port is mocked: what is under test is the walk - origin to first stop, then stop
 * to stop, and what happens when a link in that chain has no coordinates - not the distances
 * themselves, which {@code GeodesicDistanceTest} and {@code RoutingServiceTest} already own.
 */
class TripRoutingServiceTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private RoutingPort routingPort;
    private TripRoutingService service;

    @BeforeEach
    void setUp() {
        routingPort = mock(RoutingPort.class);
        service = new TripRoutingService(routingPort);
    }

    private static MasterReference place(String code, String latitude, String longitude) {
        return new MasterReference(UUID.randomUUID(), code, code + " name",
                latitude == null ? null : new BigDecimal(latitude),
                longitude == null ? null : new BigDecimal(longitude), null);
    }

    private static TravelEstimate estimate(String km, int minutes, RoutingSource source) {
        return TravelEstimate.computed(new BigDecimal(km), Duration.ofMinutes(minutes), "LOCAL_GEODESIC_V1",
                source, OffsetDateTime.now());
    }

    /** A trip whose stops visit {@code destinations} in order. */
    private static Trip tripWith(List<MasterReference> destinations) {
        Trip trip = mock(Trip.class);
        when(trip.companyId()).thenReturn(COMPANY);
        List<TripStop> stops = new java.util.ArrayList<>();
        int sequence = 1;
        for (MasterReference destination : destinations) {
            TripStop stop = mock(TripStop.class);
            when(stop.sequence()).thenReturn(sequence++);
            when(stop.destinationId()).thenReturn(destination.id());
            stops.add(stop);
        }
        when(trip.stops()).thenReturn(stops);
        return trip;
    }

    private static Map<UUID, MasterReference> byId(List<MasterReference> references) {
        return references.stream().collect(java.util.stream.Collectors.toMap(MasterReference::id, r -> r));
    }

    @Test
    @DisplayName("a trip with no stops measures nothing and asks nothing")
    void noStops() {
        Trip trip = tripWith(List.of());

        TripRouteMetrics metrics = service.measure(trip, place("ORIG", "-12.0", "-77.0"), Map.of());

        assertThat(metrics).isEqualTo(TripRouteMetrics.NONE);
        verifyNoInteractions(routingPort);
    }

    @Test
    @DisplayName("the run is origin to first stop, then stop to stop, and the legs sum")
    void walksTheRun() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference first = place("D1", "-12.10", "-77.10");
        MasterReference second = place("D2", "-12.20", "-77.20");
        Trip trip = tripWith(List.of(first, second));
        when(routingPort.estimate(eq(COMPANY), any(), any()))
                .thenReturn(Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)))
                .thenReturn(Optional.of(estimate("15.000", 25, RoutingSource.PROVIDER)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(first, second)));

        assertThat(metrics.totalDistanceKm()).isEqualByComparingTo("25.000");
        assertThat(metrics.totalMinutes()).isEqualTo(45);
        assertThat(metrics.legs()).hasSize(2);
        assertThat(metrics.isComplete()).isTrue();
        assertThat(metrics.estimated()).isFalse();
    }

    /**
     * The trip stops at the last stop. Whether a vehicle returns to base is a fleet policy this
     * product does not model, and a return leg nobody asked for would inflate every figure by
     * roughly half.
     */
    @Test
    @DisplayName("there is no return leg to the origin")
    void noReturnLeg() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference only = place("D1", "-12.10", "-77.10");
        Trip trip = tripWith(List.of(only));
        when(routingPort.estimate(any(), any(), any()))
                .thenReturn(Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(only)));

        assertThat(metrics.legs()).hasSize(1);
        verify(routingPort).estimate(eq(COMPANY), any(), any());
    }

    @Test
    @DisplayName("the first leg leaves the origin, which is not stop zero")
    void theFirstLegHasNoFromSequence() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference first = place("D1", "-12.10", "-77.10");
        Trip trip = tripWith(List.of(first));
        when(routingPort.estimate(any(), any(), any()))
                .thenReturn(Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(first)));

        assertThat(metrics.legs().get(0).fromStopSequence()).isNull();
        assertThat(metrics.legs().get(0).fromLabel()).isEqualTo("ORIG name");
        assertThat(metrics.legs().get(0).toStopSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("a leg estimated from a straight line marks the whole total as estimated")
    void estimatedIsSurfaced() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference first = place("D1", "-12.10", "-77.10");
        MasterReference second = place("D2", "-12.20", "-77.20");
        Trip trip = tripWith(List.of(first, second));
        when(routingPort.estimate(any(), any(), any()))
                .thenReturn(Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)))
                .thenReturn(Optional.of(estimate("15.000", 25, RoutingSource.FALLBACK)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(first, second)));

        assertThat(metrics.estimated()).isTrue();
        assertThat(metrics.legs().get(0).estimated()).isFalse();
        assertThat(metrics.legs().get(1).estimated()).isTrue();
    }

    /**
     * The case master data actually produces. A destination with no coordinates must not lose every
     * leg after it as well: the vehicle carries on from the last place that was locatable.
     */
    @Test
    @DisplayName("a stop with no coordinates costs one leg, not the rest of the trip")
    void oneUnlocatableStopDoesNotLoseTheRest() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference first = place("D1", "-12.10", "-77.10");
        MasterReference blind = place("D2", null, null);
        MasterReference third = place("D3", "-12.30", "-77.30");
        Trip trip = tripWith(List.of(first, blind, third));
        when(routingPort.estimate(eq(COMPANY), any(), any()))
                .thenAnswer(call -> call.getArgument(2) == null
                        ? Optional.empty()
                        : Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(first, blind, third)));

        // Origin->D1 and D1->D3 measured; the leg into D2 could not be.
        assertThat(metrics.legs()).hasSize(2);
        assertThat(metrics.unmeasurableLegs()).isEqualTo(1);
        assertThat(metrics.isComplete()).isFalse();
        assertThat(metrics.totalDistanceKm()).isEqualByComparingTo("20.000");
        assertThat(metrics.legs().get(1).fromStopSequence()).isEqualTo(1);
        assertThat(metrics.legs().get(1).toStopSequence()).isEqualTo(3);
    }

    @Test
    @DisplayName("an origin with no coordinates loses only the first leg")
    void unlocatableOrigin() {
        MasterReference origin = place("ORIG", null, null);
        MasterReference first = place("D1", "-12.10", "-77.10");
        MasterReference second = place("D2", "-12.20", "-77.20");
        Trip trip = tripWith(List.of(first, second));
        when(routingPort.estimate(eq(COMPANY), any(), any()))
                .thenAnswer(call -> call.getArgument(1) == null
                        ? Optional.empty()
                        : Optional.of(estimate("15.000", 25, RoutingSource.PROVIDER)));

        TripRouteMetrics metrics = service.measure(trip, origin, byId(List.of(first, second)));

        assertThat(metrics.unmeasurableLegs()).isEqualTo(1);
        assertThat(metrics.legs()).hasSize(1);
        assertThat(metrics.totalDistanceKm()).isEqualByComparingTo("15.000");
    }

    @Test
    @DisplayName("routing is asked with the trip's own company, never another")
    void companyScoped() {
        MasterReference origin = place("ORIG", "-12.00", "-77.00");
        MasterReference first = place("D1", "-12.10", "-77.10");
        Trip trip = tripWith(List.of(first));
        when(routingPort.estimate(any(), any(), any()))
                .thenReturn(Optional.of(estimate("10.000", 20, RoutingSource.PROVIDER)));

        service.measure(trip, origin, byId(List.of(first)));

        verify(routingPort).estimate(eq(COMPANY), any(GeoPoint.class), any(GeoPoint.class));
    }
}
