package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.DriverShift;
import com.ebim.tms.fleet.domain.WorkAssignment;
import com.ebim.tms.fleet.domain.WorkAssignmentTrip;
import com.ebim.tms.fleet.domain.WorkSequenceValidator;
import com.ebim.tms.fleet.domain.WorkSequenceValidator.ResourceWindow;
import com.ebim.tms.fleet.domain.WorkSequenceValidator.ScheduledTrip;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.fleet.infrastructure.DriverShiftRepository;
import com.ebim.tms.fleet.infrastructure.ResourceUnavailabilityRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.fleet.infrastructure.WorkAssignmentRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.GeoPoint;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.reference.ResourceRejectionReason;
import com.ebim.tms.shared.reference.RoutingPort;
import com.ebim.tms.shared.reference.TravelEstimate;
import com.ebim.tms.shared.reference.TripSchedulingPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds one driver-and-vehicle pairing's day (migration V47, closing debt D5).
 *
 * <p>The arithmetic is {@link WorkSequenceValidator}, a pure function that knows nothing about this
 * service. What happens here is the gathering - the shipments' windows, the drives between them, the
 * driver's shift, whatever blocks either resource - and the writing of a sequence that was checked
 * as a whole.
 *
 * <h2>The rule this service must never break</h2>
 *
 * <p><b>A work assignment is not an alternative route past a dispatch guard.</b> Putting a shipment
 * into somebody's day does not make it dispatchable: {@code TripExecutionService} is still the only
 * authority on whether a vehicle may leave. A shipment whose accepted carrier does not own the
 * vehicle (V42, debt D2) is <em>reported</em> here and repaired nowhere.
 *
 * <h2>Why every operation revalidates everything</h2>
 *
 * <p>Adding, removing, reordering, swapping the driver, swapping the vehicle - all of them rebuild
 * the sequence and re-run the whole validation. Moving one shipment breaks the leg into it
 * <em>and</em> the leg out of it, and a service that revalidated only what changed would report a
 * day as feasible with a broken join in the middle.
 */
@Service
public class WorkAssignmentService {

    private final WorkAssignmentRepository assignmentRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final DriverShiftRepository shiftRepository;
    private final ResourceUnavailabilityRepository unavailabilityRepository;
    private final TripSchedulingPort tripSchedulingPort;
    private final RoutingPort routingPort;
    private final OriginLookupPort originLookupPort;
    private final AuditActorProvider auditActorProvider;

    public WorkAssignmentService(WorkAssignmentRepository assignmentRepository,
            VehicleRepository vehicleRepository, DriverRepository driverRepository,
            DriverShiftRepository shiftRepository, ResourceUnavailabilityRepository unavailabilityRepository,
            TripSchedulingPort tripSchedulingPort, RoutingPort routingPort,
            OriginLookupPort originLookupPort, AuditActorProvider auditActorProvider) {
        this.assignmentRepository = assignmentRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.shiftRepository = shiftRepository;
        this.unavailabilityRepository = unavailabilityRepository;
        this.tripSchedulingPort = tripSchedulingPort;
        this.routingPort = routingPort;
        this.originLookupPort = originLookupPort;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional
    public WorkAssignmentView create(CompanyScope scope, WorkAssignmentRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        requireVehicle(scope, request.vehicleId());
        if (request.driverId() != null) {
            requireDriver(scope, request.driverId());
        }

        WorkAssignment assignment = new WorkAssignment(scope.companyId(), request.operationalDate(),
                request.vehicleId(), request.driverId(), blankToNull(request.notes()), actorId);
        return saveWithSequence(scope, assignment, request.tripIds(), actorId);
    }

    /**
     * Replaces the day: its resources and its whole sequence.
     *
     * <p>One operation for add, remove and reorder, deliberately. They differ only in what the
     * caller sends, and all three have identical consequences for the rest of the day - so giving
     * them separate endpoints would be three ways to reach one revalidation, and three places for
     * it to be forgotten.
     */
    @Transactional
    public WorkAssignmentView update(CompanyScope scope, UUID assignmentId, WorkAssignmentRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        WorkAssignment assignment = lockedAssignment(scope, assignmentId);
        requireVehicle(scope, request.vehicleId());
        if (request.driverId() != null) {
            requireDriver(scope, request.driverId());
        }

        assignment.assignVehicle(request.vehicleId(), actorId);
        assignment.assignDriver(request.driverId(), actorId);
        return saveWithSequence(scope, assignment, request.tripIds(), actorId);
    }

    /**
     * Confirms the day, and refuses to confirm one that does not work.
     *
     * <p>The only place feasibility is enforced rather than merely reported. A planner may build an
     * impossible day and look at it - that is how a problem gets diagnosed - but committing to one
     * is a different act, and the conflicts are named in the refusal so it says what to fix.
     */
    @Transactional
    public WorkAssignmentView confirm(CompanyScope scope, UUID assignmentId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        WorkAssignment assignment = lockedAssignment(scope, assignmentId);

        List<WorkSequenceValidator.Rejection> conflicts = validate(scope, assignment);
        if (!conflicts.isEmpty()) {
            throw new ConflictException("This day cannot be confirmed: "
                    + conflicts.stream().map(WorkSequenceValidator.Rejection::detail)
                            .collect(Collectors.joining(" ")));
        }
        assignment.confirm(actorId);
        return toView(scope, assignmentRepository.save(assignment), conflicts);
    }

    @Transactional
    public WorkAssignmentView cancel(CompanyScope scope, UUID assignmentId) {
        UUID actorId = auditActorProvider.requireAppUserId();
        WorkAssignment assignment = lockedAssignment(scope, assignmentId);
        assignment.cancel(actorId);
        return toView(scope, assignmentRepository.save(assignment), List.of());
    }

    @Transactional(readOnly = true)
    public List<WorkAssignmentView> listForDay(CompanyScope scope, LocalDate date) {
        return assignmentRepository
                .findByCompanyIdAndOperationalDateOrderByCreatedAtAsc(scope.companyId(), date).stream()
                .map(assignment -> toView(scope, assignment, validate(scope, assignment)))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkAssignmentView get(CompanyScope scope, UUID assignmentId) {
        WorkAssignment assignment = assignmentRepository
                .findByIdAndCompanyId(assignmentId, scope.companyId())
                .orElseThrow(WorkAssignmentService::notFound);
        return toView(scope, assignment, validate(scope, assignment));
    }

    // ------------------------------------------------------------------ the sequence

    /**
     * Rebuilds the sequence, measures every reposition, validates the whole day and saves.
     *
     * <p>The repositions are computed here and <b>frozen on the rows</b>, so the day a planner
     * committed to stays reproducible after a destination is re-geocoded - the same argument V30
     * makes for cost lines and V43 for stop ETAs.
     */
    private WorkAssignmentView saveWithSequence(CompanyScope scope, WorkAssignment assignment,
            List<UUID> tripIds, UUID actorId) {
        Map<UUID, TripSchedulingPort.TripSchedule> schedules =
                tripSchedulingPort.findSchedules(new LinkedHashSet<>(tripIds), scope.companyId());

        List<UUID> unknown = tripIds.stream().filter(id -> !schedules.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("This day names " + unknown.size() + " shipment"
                    + (unknown.size() == 1 ? "" : "s") + " that do not belong to this company.");
        }

        List<Long> repositions = repositionsFor(scope, tripIds, schedules);
        List<WorkAssignmentTrip> rows = new ArrayList<>();
        for (int index = 0; index < tripIds.size(); index++) {
            TripSchedulingPort.TripSchedule schedule = schedules.get(tripIds.get(index));
            Long reposition = repositions.get(index);
            rows.add(new WorkAssignmentTrip(scope.companyId(), schedule.tripId(), schedule.startsAt(),
                    schedule.endsAt(), reposition == null ? null : Math.toIntExact(reposition)));
        }
        assignment.replaceTrips(rows, actorId);

        WorkAssignment saved = saveWithResourceBackstop(assignment);
        return toView(scope, saved, validate(scope, saved));
    }

    /**
     * The drive into each shipment from the one before it.
     *
     * <p>Null for the first (nothing to reposition from) and <b>null when routing cannot measure the
     * leg</b> - never zero. That distinction is the whole reason
     * {@link ResourceRejectionReason#ROUTING_UNKNOWN} exists: a day built on an unmeasured drive is
     * a day nobody has checked.
     */
    private List<Long> repositionsFor(CompanyScope scope, List<UUID> tripIds,
            Map<UUID, TripSchedulingPort.TripSchedule> schedules) {
        Set<UUID> locationIds = schedules.values().stream()
                .flatMap(schedule -> java.util.stream.Stream.of(schedule.startLocationId(),
                        schedule.endLocationId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, MasterReference> places = locationIds.isEmpty()
                ? Map.of()
                : originLookupPort.findAllInCompany(locationIds, scope.companyId());

        List<Long> repositions = new ArrayList<>();
        for (int index = 0; index < tripIds.size(); index++) {
            if (index == 0) {
                repositions.add(null);
                continue;
            }
            TripSchedulingPort.TripSchedule previous = schedules.get(tripIds.get(index - 1));
            TripSchedulingPort.TripSchedule current = schedules.get(tripIds.get(index));
            GeoPoint from = pointOf(places.get(previous.endLocationId()));
            GeoPoint to = pointOf(places.get(current.startLocationId()));
            repositions.add(from == null || to == null
                    ? null
                    : routingPort.estimate(scope.companyId(), from, to)
                            .map(TravelEstimate::travelMinutes)
                            .orElse(null));
        }
        return repositions;
    }

    /** Runs the pure validator over the whole day, gathering everything it needs first. */
    private List<WorkSequenceValidator.Rejection> validate(CompanyScope scope, WorkAssignment assignment) {
        List<WorkAssignmentTrip> rows = assignment.trips();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> tripIds = rows.stream().map(WorkAssignmentTrip::tripId).toList();
        Map<UUID, TripSchedulingPort.TripSchedule> schedules =
                tripSchedulingPort.findSchedules(tripIds, scope.companyId());

        UUID vehicleCarrierId = vehicleRepository
                .findByIdAndCompanyId(assignment.vehicleId(), scope.companyId())
                .map(vehicle -> vehicle.carrierId())
                .orElse(null);

        List<ScheduledTrip> trips = rows.stream()
                .map(row -> {
                    TripSchedulingPort.TripSchedule schedule = schedules.get(row.tripId());
                    if (schedule == null) {
                        return new ScheduledTrip(row.tripId(), "?", null, null, null, null, true);
                    }
                    return new ScheduledTrip(schedule.tripId(), schedule.shipmentNumber(),
                            schedule.startsAt(), schedule.endsAt(), schedule.startLocationId(),
                            schedule.endLocationId(), schedule.carrierMatches(vehicleCarrierId));
                })
                .toList();
        List<Long> repositions = rows.stream()
                .map(row -> row.repositionMinutes() == null ? null : row.repositionMinutes().longValue())
                .toList();

        return WorkSequenceValidator.validate(trips, repositions, windowFor(scope, assignment),
                ZoneId.of(scope.timeZone() == null ? "UTC" : scope.timeZone()));
    }

    /**
     * What the driver and vehicle can do that day: the shift, the licence, and every block.
     *
     * <p>A block on the vehicle for a maintenance reason is reported as
     * {@link ResourceRejectionReason#MAINTENANCE_BLOCK} rather than a generic unavailability,
     * because the person who resolves it is different - a workshop books a truck out, and a planner
     * cannot argue with it.
     */
    private ResourceWindow windowFor(CompanyScope scope, WorkAssignment assignment) {
        LocalDate date = assignment.operationalDate();
        OffsetDateTime dayStart = date.atStartOfDay(ZoneId.of(
                scope.timeZone() == null ? "UTC" : scope.timeZone())).toOffsetDateTime();
        OffsetDateTime dayEnd = dayStart.plusDays(1);

        DriverShift shift = assignment.driverId() == null ? null : shiftRepository
                .findByCompanyIdAndDriverIdAndDayOfWeek(scope.companyId(), assignment.driverId(),
                        date.getDayOfWeek().getValue())
                .orElse(null);

        boolean licenseValid = assignment.driverId() == null || driverRepository
                .findByIdAndCompanyId(assignment.driverId(), scope.companyId())
                .map(driver -> driver.licenseExpiresOn() == null || !driver.licenseExpiresOn().isBefore(date))
                .orElse(false);

        List<ResourceWindow.Block> blocks = new ArrayList<>();
        unavailabilityRepository.findOverlapping(scope.companyId(), assignment.vehicleId(), null,
                        dayStart, dayEnd)
                .forEach(block -> blocks.add(new ResourceWindow.Block(block.startsAt(), block.endsAt(),
                        isMaintenance(block.reason())
                                ? ResourceRejectionReason.MAINTENANCE_BLOCK
                                : ResourceRejectionReason.VEHICLE_UNAVAILABLE)));
        if (assignment.driverId() != null) {
            unavailabilityRepository.findOverlapping(scope.companyId(), null, assignment.driverId(),
                            dayStart, dayEnd)
                    .forEach(block -> blocks.add(new ResourceWindow.Block(block.startsAt(), block.endsAt(),
                            ResourceRejectionReason.DRIVER_UNAVAILABLE)));
        }

        return new ResourceWindow(shift == null ? null : shift.startsAt(),
                shift == null ? null : shift.endsAt(), licenseValid, blocks);
    }

    private static boolean isMaintenance(com.ebim.tms.fleet.domain.UnavailabilityReason reason) {
        return reason == com.ebim.tms.fleet.domain.UnavailabilityReason.MAINTENANCE
                || reason == com.ebim.tms.fleet.domain.UnavailabilityReason.REPAIR
                || reason == com.ebim.tms.fleet.domain.UnavailabilityReason.INSPECTION;
    }

    // ------------------------------------------------------------------ internals

    /**
     * The database's answer to the race no service check can close.
     *
     * <p>Two dispatchers giving one vehicle two days' work at the same second both pass any check
     * here, and {@code uq_work_assignment_vehicle_day} refuses the second - as a 409 naming the
     * resource rather than a 500.
     */
    private WorkAssignment saveWithResourceBackstop(WorkAssignment assignment) {
        try {
            return assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("uq_work_assignment_vehicle_day")) {
                throw new ConflictException("That vehicle already has a day's work planned for this date.");
            }
            if (message.contains("uq_work_assignment_driver_day")) {
                throw new ConflictException("That driver already has a day's work planned for this date.");
            }
            if (message.contains("uq_work_assignment_trip_once")) {
                throw new ConflictException("One of those shipments is already in somebody else's day.");
            }
            throw e;
        }
    }

    private WorkAssignmentView toView(CompanyScope scope, WorkAssignment assignment,
            List<WorkSequenceValidator.Rejection> conflicts) {
        Map<UUID, TripSchedulingPort.TripSchedule> schedules = tripSchedulingPort.findSchedules(
                assignment.trips().stream().map(WorkAssignmentTrip::tripId).toList(), scope.companyId());

        List<WorkAssignmentView.TripView> trips = assignment.trips().stream()
                .map(row -> new WorkAssignmentView.TripView(row.tripId(),
                        Optional.ofNullable(schedules.get(row.tripId()))
                                .map(TripSchedulingPort.TripSchedule::shipmentNumber).orElse(null),
                        row.sequence(), row.plannedStart(), row.plannedEnd(), row.repositionMinutes()))
                .toList();

        String vehicleCode = vehicleRepository.findByIdAndCompanyId(assignment.vehicleId(), scope.companyId())
                .map(vehicle -> vehicle.code()).orElse(null);
        String driverName = assignment.driverId() == null ? null
                : driverRepository.findByIdAndCompanyId(assignment.driverId(), scope.companyId())
                        .map(driver -> driver.code()).orElse(null);

        return new WorkAssignmentView(assignment.id(), assignment.operationalDate(), assignment.vehicleId(),
                vehicleCode, assignment.driverId(), driverName, assignment.status(), assignment.notes(),
                assignment.version(), conflicts.isEmpty(), trips,
                conflicts.stream().map(WorkAssignmentView.ConflictView::of).toList());
    }

    private WorkAssignment lockedAssignment(CompanyScope scope, UUID assignmentId) {
        return assignmentRepository.findByIdAndCompanyIdForUpdate(assignmentId, scope.companyId())
                .orElseThrow(WorkAssignmentService::notFound);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Work assignment not found.");
    }

    private void requireVehicle(CompanyScope scope, UUID vehicleId) {
        vehicleRepository.findByIdAndCompanyId(vehicleId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("That vehicle does not belong to this company."));
    }

    private void requireDriver(CompanyScope scope, UUID driverId) {
        driverRepository.findByIdAndCompanyId(driverId, scope.companyId())
                .orElseThrow(() -> new InvalidRequestException("That driver does not belong to this company."));
    }

    private static GeoPoint pointOf(MasterReference reference) {
        return reference == null ? null : GeoPoint.of(reference.latitude(), reference.longitude());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
