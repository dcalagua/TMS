package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.TravelEstimate;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Measures a shipment's run through {@link RoutingPort} (migration V38).
 *
 * <p>Planning asks the port and never the routing module's tables - the boundary
 * {@code ModuleBoundaryTest} enforces, and the reason {@code RoutingPort} lives in
 * {@code shared.reference} beside {@code OrderPlanningPort}.
 *
 * <p><b>A trip whose route cannot be measured is still a trip.</b> A destination with no
 * coordinates produces an unmeasurable leg, which is counted and reported rather than thrown: master
 * data being incomplete must not stop a board from rendering, and a total that is quietly short
 * would be worse than one that says how short it is.
 */
@Service
public class TripRoutingService {

    private final RoutingPort routingPort;

    public TripRoutingService(RoutingPort routingPort) {
        this.routingPort = routingPort;
    }

    /**
     * The run: origin to first stop, then stop to stop, in the planner's own sequence.
     *
     * @param destinations already-resolved references, so this adds no query to a view that has
     *                     just loaded them - the same batching discipline the assembler follows
     */
    public TripRouteMetrics measure(Trip trip, MasterReference origin, Map<UUID, MasterReference> destinations) {
        List<TripStop> stops = trip.stops();
        if (stops.isEmpty()) {
            return TripRouteMetrics.NONE;
        }

        GeoPoint from = pointOf(origin);
        String fromLabel = labelOf(origin, "Origin");
        Integer fromSequence = null;

        BigDecimal totalKm = BigDecimal.ZERO;
        Duration totalDuration = Duration.ZERO;
        List<TripRouteMetrics.TripRouteLegView> legs = new ArrayList<>();
        String provider = null;
        boolean anyEstimated = false;
        int unmeasurable = 0;

        for (TripStop stop : stops) {
            MasterReference destination = destinations.get(stop.destinationId());
            GeoPoint to = pointOf(destination);
            String toLabel = labelOf(destination, "Stop " + stop.sequence());

            Optional<TravelEstimate> estimate = routingPort.estimate(trip.companyId(), from, to);
            if (estimate.isEmpty()) {
                unmeasurable++;
            } else {
                TravelEstimate leg = estimate.get();
                totalKm = totalKm.add(leg.distanceKm());
                totalDuration = totalDuration.plus(leg.travelDuration());
                anyEstimated |= leg.isEstimated();
                provider = provider == null ? leg.provider() : provider;
                legs.add(new TripRouteMetrics.TripRouteLegView(fromSequence, fromLabel, stop.sequence(), toLabel,
                        leg.distanceKm(), leg.travelMinutes(), leg.isEstimated()));
            }

            // The vehicle carries on from where it is even when the last leg could not be measured:
            // one destination missing its coordinates must not lose every leg after it as well.
            if (to != null) {
                from = to;
                fromLabel = toLabel;
                fromSequence = stop.sequence();
            }
        }

        return new TripRouteMetrics(totalKm, totalDuration, List.copyOf(legs), provider, anyEstimated, unmeasurable);
    }

    private static GeoPoint pointOf(MasterReference reference) {
        return reference == null ? null : GeoPoint.of(reference.latitude(), reference.longitude());
    }

    private static String labelOf(MasterReference reference, String fallback) {
        if (reference == null) {
            return fallback;
        }
        return reference.name() != null ? reference.name() : reference.code();
    }
}
