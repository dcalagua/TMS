package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.EtaSource;
import com.ebim.tms.planning.domain.StopSchedule;
import com.ebim.tms.planning.domain.StopScheduleEngine;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.planning.domain.PlanningRun;
import com.ebim.tms.planning.infrastructure.PlanningRunRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.LocationTimeZonePort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.StopServicePort;
import com.ebim.tms.shared.reference.TravelEstimate;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and stamps when a shipment is expected at each of its stops (migration V43, ADR-011).
 *
 * <p>The arithmetic lives in {@link StopScheduleEngine}, which is a pure function and has no idea
 * this class exists. What happens here is the gathering: the legs from {@link RoutingPort}, the
 * service times from {@link StopServicePort}, the windows from the stops themselves, and the zone
 * those windows are written in.
 *
 * <p><b>On request, never on a schedule.</b> A trip whose stops or vehicle change has a stale ETA
 * until somebody asks for a new one, and {@code eta_calculated_at} is how a reader tells. A
 * background loop recomputing on its own would need an actor to attribute the write to, and that is
 * open debt D4 - inventing a principal to satisfy an audit column is exactly what JOB 07 refused.
 *
 * <p><b>No audit action.</b> Recomputing an estimate is not a decision anybody is accountable for;
 * it produces a number that the next recomputation replaces. The trip's {@code updated_by} moves,
 * which is the honest record of "somebody asked for this", and a new audit verb for it would bury
 * the ones that are compliance facts.
 */
@Service
public class StopEtaService {

    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;
    private final RoutingPort routingPort;
    private final StopServicePort stopServicePort;
    private final LocationTimeZonePort timeZonePort;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final AuditActorProvider auditActorProvider;

    public StopEtaService(TripRepository tripRepository, PlanningRunRepository planningRunRepository,
            RoutingPort routingPort,
            StopServicePort stopServicePort, LocationTimeZonePort timeZonePort,
            OriginLookupPort originLookupPort, DestinationLookupPort destinationLookupPort,
            AuditActorProvider auditActorProvider) {
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
        this.routingPort = routingPort;
        this.stopServicePort = stopServicePort;
        this.timeZonePort = timeZonePort;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.auditActorProvider = auditActorProvider;
    }

    /**
     * Recomputes the whole run and writes it onto the stops.
     *
     * @throws ConflictException when the shipment has no planned departure. A schedule needs a
     *                           starting instant, and the honest response to not having one is to
     *                           say so rather than to invent {@code now()} - which would produce a
     *                           board that changes every time somebody refreshes it
     */
    @Transactional
    public List<StopSchedule> recompute(CompanyScope scope, UUID tripId) {
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip " + tripId + " was not found."));
        UUID actorId = auditActorProvider.requireAppUserId();

        if (trip.plannedDepartureAt() == null) {
            throw new ConflictException("Shipment " + trip.shipmentNumber() + " has no planned departure,"
                    + " so there is nothing to schedule from. Assign a vehicle and a departure time first.");
        }

        List<TripStop> stops = trip.stops();
        if (stops.isEmpty()) {
            return List.of();
        }

        List<StopScheduleEngine.Leg> legs = buildLegs(scope, trip, stops, originIdOf(scope, trip));
        ZoneId zone = zoneOf(scope, stops);
        List<StopSchedule> schedule = StopScheduleEngine.schedule(trip.plannedDepartureAt(), zone, legs);

        OffsetDateTime calculatedAt = OffsetDateTime.now();
        for (int index = 0; index < stops.size(); index++) {
            stops.get(index).applySchedule(schedule.get(index), calculatedAt);
        }
        trip.touch(actorId);
        tripRepository.save(trip);
        return schedule;
    }

    /**
     * Origin to first stop, then stop to stop, in the planner's own sequence.
     *
     * <p>A leg with no estimate carries a null travel time, which the engine turns into "no ETA
     * from here on". Deliberately not a zero and not the previous leg's figure.
     */
    private List<StopScheduleEngine.Leg> buildLegs(CompanyScope scope, Trip trip, List<TripStop> stops,
            UUID originId) {
        Map<UUID, Integer> serviceMinutes = stopServicePort.findServiceMinutes(
                stops.stream().map(TripStop::destinationId).collect(java.util.stream.Collectors.toSet()),
                scope.companyId());
        Map<UUID, MasterReference> places = placesOf(scope, originId, stops);

        List<StopScheduleEngine.Leg> legs = new ArrayList<>(stops.size());
        GeoPoint from = originId == null ? null : pointOf(places.get(originId));

        for (TripStop stop : stops) {
            MasterReference destination = places.get(stop.destinationId());
            GeoPoint to = pointOf(destination);
            Optional<TravelEstimate> estimate = from == null || to == null
                    ? Optional.empty()
                    : routingPort.estimate(scope.companyId(), from, to);

            legs.add(new StopScheduleEngine.Leg(
                    stop.sequence(),
                    estimate.map(TravelEstimate::travelMinutes).orElse(null),
                    // isEstimated() reads source and not servedFromCache - the distinction V38 had
                    // to be corrected into. A cached measured road is still a measured road.
                    estimate.map(e -> e.isEstimated() ? EtaSource.FALLBACK : EtaSource.MEASURED_ROUTE)
                            .orElse(null),
                    serviceMinutes.getOrDefault(stop.destinationId(), 0),
                    stop.serviceWindowStart(),
                    stop.serviceWindowEnd()));

            // The vehicle carries on from where it is even when a leg could not be measured, so a
            // destination missing its coordinates does not also lose the geometry of the next leg.
            // The schedule is still broken from here - that is the engine's rule, not this one's.
            if (to != null) {
                from = to;
            }
        }
        return legs;
    }

    /** Where the run starts. The origin lives on the planning run, which is where the assembler reads it too. */
    private UUID originIdOf(CompanyScope scope, Trip trip) {
        return planningRunRepository.findByIdAndCompanyId(trip.planningRunId(), scope.companyId())
                .map(PlanningRun::originId)
                .orElse(null);
    }

    /**
     * Origins and destinations resolve through <b>different</b> ports, and asking one for both
     * silently returns nothing for the other half - which produces a shipment where every leg looks
     * unmeasurable and the whole run loses its ETA. The two maps are merged here rather than in the
     * caller so that mistake has one place to be made and it has been made already.
     */
    private Map<UUID, MasterReference> placesOf(CompanyScope scope, UUID originId, List<TripStop> stops) {
        Set<UUID> destinationIds = new LinkedHashSet<>();
        stops.forEach(stop -> destinationIds.add(stop.destinationId()));

        Map<UUID, MasterReference> places = new java.util.HashMap<>(
                destinationLookupPort.findAllInCompany(destinationIds, scope.companyId()));
        if (originId != null) {
            places.putAll(originLookupPort.findAllInCompany(Set.of(originId), scope.companyId()));
        }
        return places;
    }

    /**
     * The zone the service windows are written in.
     *
     * <p>The first stop's own, falling back to the company's. A window is a wall-clock time at the
     * site, and reading it in the server's zone would move every window by the deployment's offset
     * - the failure V41 paid for with dock opening hours.
     *
     * <p>One zone for the whole run rather than one per stop, and that is a real simplification
     * worth naming: a shipment crossing a zone boundary has its later windows read in the first
     * stop's zone. Peru has one zone and this is exact there; it is recorded here rather than
     * hidden, and per-stop zones are a change to this method and nothing else.
     */
    private ZoneId zoneOf(CompanyScope scope, List<TripStop> stops) {
        return timeZonePort.findTimeZone(stops.getFirst().destinationId(), scope.companyId())
                .or(() -> Optional.ofNullable(scope.timeZone()))
                .map(ZoneId::of)
                .orElse(ZoneId.of("UTC"));
    }

    private static GeoPoint pointOf(MasterReference reference) {
        return reference == null ? null : GeoPoint.of(reference.latitude(), reference.longitude());
    }
}
