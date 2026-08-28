package com.ebim.tms.appointments.api;

import com.ebim.tms.appointments.application.AppointmentRequest;
import com.ebim.tms.appointments.application.AppointmentService;
import com.ebim.tms.appointments.application.AppointmentView;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dock bookings (migration V41).
 *
 * <p>Follows the {@code TripTenderController} template: {@link CompanyScope} is only ever supplied
 * by the framework once {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an
 * active membership.
 *
 * <p>There is no delete endpoint. An appointment is cancelled or marked no-show; who booked which
 * door and what happened is exactly what a carrier disputing a detention charge asks for, and V41
 * withholds the {@code DELETE} grant to make that a database fact rather than a convention.
 */
@RestController
@RequestMapping("${tms.api.base-path}/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('appointments.appointment:read')")
    @Operation(summary = "Every booking at a site between two instants, across every door",
            description = "The dock board. Both bounds are absolute instants; the site's own time "
                    + "zone is how they are displayed, not how they are stored.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<AppointmentView> forLocation(
            CompanyScope scope,
            @RequestParam("locationId") UUID locationId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return appointmentService.forLocation(scope, locationId, from, to);
    }

    @GetMapping("/by-trip/{tripId}")
    @PreAuthorize("hasAuthority('appointments.appointment:read')")
    @Operation(summary = "A shipment's dock bookings, in visiting order")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<AppointmentView> forTrip(CompanyScope scope, @PathVariable UUID tripId) {
        return appointmentService.forTrip(scope, tripId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('appointments.appointment:read')")
    @Operation(summary = "One booking")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView get(CompanyScope scope, @PathVariable UUID id) {
        return appointmentService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "Hold a door for a window",
            description = "Refused with 409 when the door is already booked, closed then, or shut "
                    + "at that hour. Omitting windowEnd uses the door's own default slot length.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView book(CompanyScope scope, @Valid @RequestBody AppointmentRequest request) {
        return appointmentService.book(scope, request);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "The site agrees to the slot")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView confirm(CompanyScope scope, @PathVariable UUID id) {
        return appointmentService.confirm(scope, id);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "Move the window, keeping the same booking",
            description = "Not a cancel-and-rebook: this is the same commitment moved, and the "
                    + "window it originally stood at is kept on the row.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView reschedule(
            CompanyScope scope, @PathVariable UUID id,
            @RequestParam("windowStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime windowStart,
            @RequestParam(name = "windowEnd", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime windowEnd) {
        return appointmentService.reschedule(scope, id, windowStart, windowEnd);
    }

    @PostMapping("/{id}/arrive")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "The vehicle is at the door")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView arrive(
            CompanyScope scope, @PathVariable UUID id,
            @RequestParam(name = "at", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return appointmentService.arrive(scope, id, at);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "Loaded or unloaded and gone")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView complete(
            CompanyScope scope, @PathVariable UUID id,
            @RequestParam(name = "at", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at) {
        return appointmentService.complete(scope, id, at);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "Release the slot; the record stays")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView cancel(CompanyScope scope, @PathVariable UUID id,
            @RequestParam(name = "reason", required = false) String reason) {
        return appointmentService.cancel(scope, id, reason);
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('appointments.appointment:manage')")
    @Operation(summary = "Nobody came",
            description = "Releases the slot and keeps the record: a no-show is what a demurrage "
                    + "or missed-slot conversation is argued from. A vehicle that arrived can "
                    + "never be marked no-show.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AppointmentView markNoShow(CompanyScope scope, @PathVariable UUID id) {
        return appointmentService.markNoShow(scope, id);
    }
}
