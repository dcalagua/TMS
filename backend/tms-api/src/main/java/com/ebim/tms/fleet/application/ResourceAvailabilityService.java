package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.fleet.domain.DriverShift;
import com.ebim.tms.fleet.domain.ResourceUnavailability;
import com.ebim.tms.fleet.domain.UnavailabilityReason;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.fleet.infrastructure.DriverShiftRepository;
import com.ebim.tms.fleet.infrastructure.ResourceUnavailabilityRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.reference.ResourceAvailabilityPort;
import com.ebim.tms.shared.reference.ResourceBlock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a vehicle or a driver cannot work, and when a driver normally does (migration V42).
 *
 * <p>Also fleet's implementation of {@link ResourceAvailabilityPort} - the only way
 * {@code planning} learns that the truck it is about to dispatch is in the workshop. Same shape as
 * {@link VehicleLookupService}: the rule lives here, and the other module sees an answer rather
 * than a table.
 *
 * <p><b>Overlapping blocks.</b> Two "in maintenance" rows on one truck for overlapping windows are
 * two statements of one fact, and the second is what makes a downtime report double-count. Refused
 * here with a sentence a planner can read, and refused again by
 * {@code ex_vehicle_unavailability_no_overlap} in the transaction that raced past this check - the
 * same two-layer arrangement {@code AppointmentService} has with the dock's EXCLUDE constraint,
 * and for the same reason: a check and a write are not one operation.
 */
@Service
public class ResourceAvailabilityService implements ResourceAvailabilityPort {

    private final ResourceUnavailabilityRepository unavailabilityRepository;
    private final DriverShiftRepository shiftRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public ResourceAvailabilityService(ResourceUnavailabilityRepository unavailabilityRepository,
            DriverShiftRepository shiftRepository, VehicleRepository vehicleRepository,
            DriverRepository driverRepository, AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.unavailabilityRepository = unavailabilityRepository;
        this.shiftRepository = shiftRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    // ---------------------------------------------------------------- the port

    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceBlock> findBlock(UUID companyId, UUID vehicleId, UUID driverId, OffsetDateTime at) {
        if (vehicleId == null && driverId == null) {
            return Optional.empty();
        }
        return unavailabilityRepository.findCovering(companyId, vehicleId, driverId, at).stream()
                .findFirst()
                .map(block -> new ResourceBlock(block.isVehicleBlock() ? "vehicle" : "driver",
                        block.reason().name(), block.endsAt()));
    }

    // ------------------------------------------------------------ blocks

    @Transactional(readOnly = true)
    public List<ResourceUnavailability> listForVehicle(CompanyScope scope, UUID vehicleId) {
        requireVehicle(scope, vehicleId);
        return unavailabilityRepository.findByCompanyIdAndVehicleIdOrderByStartsAtDesc(scope.companyId(), vehicleId);
    }

    @Transactional(readOnly = true)
    public List<ResourceUnavailability> listForDriver(CompanyScope scope, UUID driverId) {
        requireDriver(scope, driverId);
        return unavailabilityRepository.findByCompanyIdAndDriverIdOrderByStartsAtDesc(scope.companyId(), driverId);
    }

    @Transactional
    public ResourceUnavailability blockVehicle(CompanyScope scope, UUID vehicleId, UnavailabilityReason reason,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String notes) {
        Vehicle vehicle = requireVehicle(scope, vehicleId);
        UUID actorId = auditActorProvider.requireAppUserId();
        requireFree(scope, vehicleId, null, startsAt, endsAt, "Vehicle " + vehicle.code());

        ResourceUnavailability block = ResourceUnavailability.forVehicle(scope.companyId(), vehicleId, reason,
                startsAt, endsAt, blankToNull(notes), actorId);
        ResourceUnavailability saved = saveWithOverlapBackstop(block, "Vehicle " + vehicle.code());
        auditRecorder.record(scope, AuditAggregateType.VEHICLE, vehicleId, AuditAction.RESOURCE_BLOCKED,
                Map.of("reason", reason.name(), "startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return saved;
    }

    @Transactional
    public ResourceUnavailability blockDriver(CompanyScope scope, UUID driverId, UnavailabilityReason reason,
            OffsetDateTime startsAt, OffsetDateTime endsAt, String notes) {
        Driver driver = requireDriver(scope, driverId);
        UUID actorId = auditActorProvider.requireAppUserId();
        String who = "Driver " + driver.code();
        requireFree(scope, null, driverId, startsAt, endsAt, who);

        ResourceUnavailability block = ResourceUnavailability.forDriver(scope.companyId(), driverId, reason,
                startsAt, endsAt, blankToNull(notes), actorId);
        ResourceUnavailability saved = saveWithOverlapBackstop(block, who);
        auditRecorder.record(scope, AuditAggregateType.DRIVER, driverId, AuditAction.RESOURCE_BLOCKED,
                Map.of("reason", reason.name(), "startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return saved;
    }

    /**
     * Lifts a block. Deleted rather than end-dated: a maintenance window entered by mistake is not
     * a fact about the truck, and leaving it behind with a zero length would put a phantom in every
     * downtime figure. The decision survives in the audit trail, which is where a reversal belongs.
     */
    @Transactional
    public void releaseVehicle(CompanyScope scope, UUID vehicleId, UUID blockId) {
        release(scope, requireBlockOn(scope, blockId, vehicleId, true), AuditAggregateType.VEHICLE, vehicleId);
    }

    @Transactional
    public void releaseDriver(CompanyScope scope, UUID driverId, UUID blockId) {
        release(scope, requireBlockOn(scope, blockId, driverId, false), AuditAggregateType.DRIVER, driverId);
    }

    /**
     * The block must be the one the caller named, on the resource they named.
     *
     * <p>Not defensive tidiness - an authorization check. Vehicle downtime is guarded by
     * {@code fleet.vehicle:manage} and driver absence by {@code fleet.driver:manage}, precisely so
     * that whoever books trucks into the workshop cannot touch the personnel side (V26). A delete
     * that looked the block up by id alone would let the driver-facing endpoint remove a vehicle's
     * block and the vehicle-facing one remove a person's, and the permission split would mean
     * nothing at the only place it has to hold.
     *
     * <p>404 rather than 403 for a block on a different resource: the caller is not entitled to
     * learn that this id exists on something else.
     */
    private ResourceUnavailability requireBlockOn(CompanyScope scope, UUID blockId, UUID resourceId,
            boolean vehicle) {
        ResourceUnavailability block = unavailabilityRepository.findByIdAndCompanyId(blockId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Unavailability " + blockId + " was not found."));
        UUID actual = vehicle ? block.vehicleId() : block.driverId();
        if (!resourceId.equals(actual)) {
            throw new ResourceNotFoundException("Unavailability " + blockId + " was not found.");
        }
        return block;
    }

    private void release(CompanyScope scope, ResourceUnavailability block, AuditAggregateType type, UUID resourceId) {
        auditActorProvider.requireAppUserId();
        unavailabilityRepository.delete(block);
        auditRecorder.record(scope, type, resourceId, AuditAction.RESOURCE_RELEASED,
                Map.of("reason", block.reason().name(), "startsAt", block.startsAt().toString(),
                        "endsAt", block.endsAt().toString()));
    }

    // ------------------------------------------------------------ shifts

    @Transactional(readOnly = true)
    public List<DriverShift> listShifts(CompanyScope scope, UUID driverId) {
        requireDriver(scope, driverId);
        return shiftRepository.findByCompanyIdAndDriverIdOrderByDayOfWeekAsc(scope.companyId(), driverId);
    }

    /**
     * Sets a driver's hours on one day, replacing whatever was there.
     *
     * <p>Upsert rather than create-or-fail because {@code uq_driver_shift_day} allows one row per
     * driver per day: "Tuesday is now 07:00-16:00" is the sentence an operation says, and making
     * them delete Tuesday first would be ceremony over a rule the database already states.
     */
    @Transactional
    public DriverShift setShift(CompanyScope scope, UUID driverId, DayOfWeek day, LocalTime startsAt,
            LocalTime endsAt) {
        requireDriver(scope, driverId);
        auditActorProvider.requireAppUserId();
        return shiftRepository.findByCompanyIdAndDriverIdAndDayOfWeek(scope.companyId(), driverId, day.getValue())
                .map(existing -> {
                    existing.moveTo(startsAt, endsAt);
                    return shiftRepository.save(existing);
                })
                .orElseGet(() -> shiftRepository.save(
                        new DriverShift(scope.companyId(), driverId, day, startsAt, endsAt)));
    }

    @Transactional
    public void clearShift(CompanyScope scope, UUID driverId, UUID shiftId) {
        DriverShift shift = shiftRepository.findByIdAndCompanyId(shiftId, scope.companyId())
                .filter(found -> found.driverId().equals(driverId))
                .orElseThrow(() -> new ResourceNotFoundException("Shift " + shiftId + " was not found."));
        auditActorProvider.requireAppUserId();
        shiftRepository.delete(shift);
    }

    // ------------------------------------------------------------ internals

    private void requireFree(CompanyScope scope, UUID vehicleId, UUID driverId, OffsetDateTime startsAt,
            OffsetDateTime endsAt, String who) {
        if (!endsAt.isAfter(startsAt)) {
            throw new ConflictException("An unavailability window must end after it starts.");
        }
        unavailabilityRepository.findOverlapping(scope.companyId(), vehicleId, driverId, startsAt, endsAt).stream()
                .findFirst()
                .ifPresent(clash -> {
                    throw new ConflictException(who + " is already unavailable (" + clash.reason()
                            + ") from " + clash.startsAt() + " to " + clash.endsAt() + ".");
                });
    }

    /**
     * The database's answer to the race {@link #requireFree} cannot close. Two planners blocking one
     * truck for overlapping windows both pass the check and one loses here, as a 409 rather than a
     * 500.
     */
    private ResourceUnavailability saveWithOverlapBackstop(ResourceUnavailability block, String who) {
        try {
            return unavailabilityRepository.saveAndFlush(block);
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains("unavailability_no_overlap")) {
                throw new ConflictException(who + " was made unavailable for an overlapping window"
                        + " by someone else. Reload and try again.");
            }
            throw e;
        }
    }

    private Vehicle requireVehicle(CompanyScope scope, UUID vehicleId) {
        return vehicleRepository.findByIdAndCompanyId(vehicleId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle " + vehicleId + " was not found."));
    }

    private Driver requireDriver(CompanyScope scope, UUID driverId) {
        return driverRepository.findByIdAndCompanyId(driverId, scope.companyId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver " + driverId + " was not found."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
