package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.AssignmentStatus;
import com.ebim.tms.planning.domain.Trip;
import com.ebim.tms.planning.domain.TripOrderAssignment;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.planning.infrastructure.TripOrderAssignmentRepository;
import com.ebim.tms.planning.infrastructure.TripRepository;
import com.ebim.tms.shared.reference.TripEmissionSourcePort;
import com.ebim.tms.shared.reference.TripSettlementLookupPort.TripLoad;
import com.ebim.tms.shared.reference.VehicleCapacityReference;
import com.ebim.tms.shared.reference.VehicleLookupPort;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a trip drove and carried, for its carbon footprint (migration V73) - the planning side of
 * {@link TripEmissionSourcePort}.
 *
 * <p>The distance is measured by {@link TripViewAssembler#routingOf}, the very code that measures the
 * trip workspace's «Recorrido», so the footprint and the distance printed beside it cannot disagree.
 * The vehicle type comes from fleet through {@link VehicleLookupPort}; the loads are the ACTIVE rows
 * of the assignment ledger, the same rows cost to serve splits on (V68).
 *
 * <p>Read-only. Measuring writes nothing but the routing port's own cache.
 */
@Service
public class TripEmissionSourceService implements TripEmissionSourcePort {

    private final TripRepository tripRepository;
    private final TripOrderAssignmentRepository assignmentRepository;
    private final TripViewAssembler assembler;
    private final VehicleLookupPort vehicleLookupPort;

    public TripEmissionSourceService(TripRepository tripRepository,
            TripOrderAssignmentRepository assignmentRepository, TripViewAssembler assembler,
            VehicleLookupPort vehicleLookupPort) {
        this.tripRepository = tripRepository;
        this.assignmentRepository = assignmentRepository;
        this.assembler = assembler;
        this.vehicleLookupPort = vehicleLookupPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TripEmissionSource> findSource(UUID tripId, UUID companyId) {
        return tripRepository.findByIdAndCompanyId(tripId, companyId)
                .map(trip -> sourcesOf(List.of(trip), companyId).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripEmissionSource> findInPeriod(UUID companyId, LocalDate from, LocalDate to, int limit) {
        List<Trip> trips = tripRepository.findByCompanyIdAndPlanningDateBetweenAndStatusNotOrderByPlanningDateAscIdAsc(
                companyId, from, to, TripStatus.CANCELLED, PageRequest.of(0, limit));
        return trips.isEmpty() ? List.of() : sourcesOf(trips, companyId);
    }

    /** A fixed number of batched lookups for the whole set, plus the routing port's per-leg reads. */
    private List<TripEmissionSource> sourcesOf(List<Trip> trips, UUID companyId) {
        Map<UUID, TripRouteMetrics> routing = assembler.routingOf(trips, companyId);
        Set<UUID> vehicleIds = trips.stream().map(Trip::vehicleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, VehicleCapacityReference> vehicles = vehicleIds.isEmpty() ? Map.of()
                : vehicleLookupPort.findAllInCompany(vehicleIds, companyId);
        Map<UUID, List<TripOrderAssignment>> assignments = assignmentRepository
                .findByCompanyIdAndTripIdInAndStatus(companyId,
                        trips.stream().map(Trip::id).toList(), AssignmentStatus.ACTIVE)
                .stream()
                .collect(Collectors.groupingBy(TripOrderAssignment::tripId));
        return trips.stream().map(trip -> {
            TripRouteMetrics metrics = routing.getOrDefault(trip.id(), TripRouteMetrics.NONE);
            VehicleCapacityReference vehicle = trip.vehicleId() == null ? null : vehicles.get(trip.vehicleId());
            List<TripLoad> loads = assignments.getOrDefault(trip.id(), List.of()).stream()
                    .sorted(Comparator.comparing(TripOrderAssignment::assignedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(TripOrderAssignment::id))
                    .map(row -> new TripLoad(row.id(), row.orderId(), row.assignedWeightKg(), row.assignedVolumeM3(),
                            row.assignedPallets(), row.assignedFloorAreaM2()))
                    .toList();
            return new TripEmissionSource(trip.id(), trip.shipmentNumber(), trip.status() == TripStatus.CANCELLED,
                    trip.planningDate(), trip.actualDepartureAt(), trip.routeId(), trip.vehicleId(),
                    vehicle == null ? null : vehicle.vehicleTypeId(), vehicle == null ? null : vehicle.licensePlate(),
                    metrics.totalDistanceKm(), metrics.estimated(), metrics.unmeasurableLegs(), metrics.provider(),
                    loads);
        }).toList();
    }
}
