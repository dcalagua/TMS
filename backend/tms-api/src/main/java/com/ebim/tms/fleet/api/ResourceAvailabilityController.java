package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.DriverShiftRequest;
import com.ebim.tms.fleet.application.DriverShiftView;
import com.ebim.tms.fleet.application.ResourceAvailabilityService;
import com.ebim.tms.fleet.application.UnavailabilityRequest;
import com.ebim.tms.fleet.application.UnavailabilityView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * When a vehicle or a driver cannot work, and when a driver normally does (migration V42).
 *
 * <p><b>Guarded by the existing vehicle and driver permissions, deliberately, rather than by a new
 * {@code fleet.availability:*} pair.</b> V26 split {@code fleet.driver:*} from
 * {@code fleet.vehicle:*} because a driver record holds personal data a truck record does not - and
 * a driver's absence reason (MEDICAL, ABSENCE) is the most personal thing in the fleet module. A
 * single availability permission would hand whoever books trucks into the workshop a view of who is
 * off sick. Keeping the split means a fleet clerk maintains vehicle downtime and never sees the
 * personnel side, which is exactly the arrangement V26 built.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet")
@Tag(name = "Fleet availability", description = "Vehicle and driver downtime, and driver shifts")
public class ResourceAvailabilityController {

    private final ResourceAvailabilityService service;

    public ResourceAvailabilityController(ResourceAvailabilityService service) {
        this.service = service;
    }

    // ------------------------------------------------------------- vehicles

    @GetMapping("/vehicles/{vehicleId}/unavailability")
    @PreAuthorize("hasAuthority('fleet.vehicle:read')")
    @Operation(summary = "The windows in which a vehicle cannot work, most recent first")
    public List<UnavailabilityView> vehicleBlocks(CompanyScope scope, @PathVariable UUID vehicleId) {
        return service.listForVehicle(scope, vehicleId).stream().map(UnavailabilityView::of).toList();
    }

    @PostMapping("/vehicles/{vehicleId}/unavailability")
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Take a vehicle out of service for a window")
    public UnavailabilityView blockVehicle(CompanyScope scope, @PathVariable UUID vehicleId,
            @Valid @RequestBody UnavailabilityRequest request) {
        return UnavailabilityView.of(service.blockVehicle(scope, vehicleId, request.reason(),
                request.startsAt(), request.endsAt(), request.notes()));
    }

    @DeleteMapping("/vehicles/{vehicleId}/unavailability/{blockId}")
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Put a vehicle back in service")
    public void releaseVehicle(CompanyScope scope, @PathVariable UUID vehicleId, @PathVariable UUID blockId) {
        service.releaseVehicle(scope, vehicleId, blockId);
    }

    // -------------------------------------------------------------- drivers

    @GetMapping("/drivers/{driverId}/unavailability")
    @PreAuthorize("hasAuthority('fleet.driver:read')")
    @Operation(summary = "The windows in which a driver cannot work, most recent first")
    public List<UnavailabilityView> driverBlocks(CompanyScope scope, @PathVariable UUID driverId) {
        return service.listForDriver(scope, driverId).stream().map(UnavailabilityView::of).toList();
    }

    @PostMapping("/drivers/{driverId}/unavailability")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record that a driver cannot work over a window")
    public UnavailabilityView blockDriver(CompanyScope scope, @PathVariable UUID driverId,
            @Valid @RequestBody UnavailabilityRequest request) {
        return UnavailabilityView.of(service.blockDriver(scope, driverId, request.reason(),
                request.startsAt(), request.endsAt(), request.notes()));
    }

    @DeleteMapping("/drivers/{driverId}/unavailability/{blockId}")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a driver's unavailability window")
    public void releaseDriver(CompanyScope scope, @PathVariable UUID driverId, @PathVariable UUID blockId) {
        service.releaseDriver(scope, driverId, blockId);
    }

    // --------------------------------------------------------------- shifts

    @GetMapping("/drivers/{driverId}/shifts")
    @PreAuthorize("hasAuthority('fleet.driver:read')")
    @Operation(summary = "A driver's normal weekly hours, Monday first")
    public List<DriverShiftView> shifts(CompanyScope scope, @PathVariable UUID driverId) {
        return service.listShifts(scope, driverId).stream().map(DriverShiftView::of).toList();
    }

    /**
     * PUT rather than POST: one row per driver per day ({@code uq_driver_shift_day}), so setting
     * Tuesday twice is one shift and not two, whatever a caller repeats.
     */
    @PutMapping("/drivers/{driverId}/shifts")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @Operation(summary = "Set a driver's hours on one day of the week")
    public DriverShiftView setShift(CompanyScope scope, @PathVariable UUID driverId,
            @Valid @RequestBody DriverShiftRequest request) {
        return DriverShiftView.of(service.setShift(scope, driverId, request.dayOfWeek(),
                request.startsAt(), request.endsAt()));
    }

    @DeleteMapping("/drivers/{driverId}/shifts/{shiftId}")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a driver's hours on one day")
    public void clearShift(CompanyScope scope, @PathVariable UUID driverId, @PathVariable UUID shiftId) {
        service.clearShift(scope, driverId, shiftId);
    }
}
