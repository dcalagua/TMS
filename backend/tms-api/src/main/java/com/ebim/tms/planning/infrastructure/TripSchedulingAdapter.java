package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripStop;
import com.ebim.tms.shared.reference.TripSchedulingPort;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a shipment runs (V47), answered by the module that owns {@code tms.trip}.
 *
 * <p>Read-only. A work assignment organises shipments and never moves them - letting the day's plan
 * write back into the shipment would be the wrong way round, and would give scheduling an authority
 * dispatch deliberately keeps.
 */
@Component
class TripSchedulingAdapter implements TripSchedulingPort {

    private final TripRepository tripRepository;
    private final PlanningRunRepository planningRunRepository;

    TripSchedulingAdapter(TripRepository tripRepository, PlanningRunRepository planningRunRepository) {
        this.tripRepository = tripRepository;
        this.planningRunRepository = planningRunRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TripSchedule> findSchedules(Collection<UUID> tripIds, UUID companyId) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TripSchedule> byTrip = new HashMap<>();
        for (Trip trip : tripRepository.findByIdInAndCompanyId(tripIds, companyId)) {
            List<TripStop> stops = trip.stops();
            TripStop last = stops.stream().max(Comparator.comparingInt(TripStop::sequence)).orElse(null);
            byTrip.put(trip.id(), new TripSchedule(
                    trip.id(),
                    trip.shipmentNumber(),
                    trip.plannedDepartureAt(),
                    // The V43 ETA of the last stop's departure. NULL when a leg could not be
                    // measured, and left NULL: an unknown end is not a guessable one, and the
                    // validator refuses the day rather than inventing when the truck is free.
                    last == null ? null : last.etaDepartureAt(),
                    originOf(trip, companyId),
                    last == null ? null : last.destinationId(),
                    trip.carrierId(),
                    trip.acceptedCarrierId()));
        }
        return Map.copyOf(byTrip);
    }

    /**
     * Where the vehicle leaves from: the planning run's <b>origin</b>, not the first stop.
     *
     * <p>The distinction is the whole point of the field. A reposition is measured from where the
     * previous shipment finished - its last stop - to where the next one <em>starts</em>, which is
     * the depot it leaves from. Using the first stop's destination would measure the drive to the
     * second place the truck visits and call it the drive to the first, which is wrong by exactly
     * one leg on every join in the day.
     *
     * <p>Resolved per trip rather than batched because a day holds a handful of shipments, not a
     * page of them: the N+1 is bounded by how many trips one driver can run.
     */
    private UUID originOf(Trip trip, UUID companyId) {
        return planningRunRepository.findByIdAndCompanyId(trip.planningRunId(), companyId)
                .map(run -> run.originId())
                .orElse(null);
    }
}
