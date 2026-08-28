package com.ebim.tms.appointments.api;

import com.ebim.tms.appointments.application.LocationResourceRequest;
import com.ebim.tms.appointments.application.LocationResourceService;
import com.ebim.tms.appointments.application.LocationResourceView;
import com.ebim.tms.appointments.application.ResourceCalendarRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The doors themselves, their opening hours and their closures (migration V41).
 *
 * <p>Reading is the appointment permission - the yard, the gate and the warehouse all need to see
 * which doors exist. Configuring is {@code appointments.resource:manage}, an administrator's
 * authority: adding a door changes what the whole site can promise.
 */
@RestController
@RequestMapping("${tms.api.base-path}/appointments/resources")
public class LocationResourceController {

    private final LocationResourceService resourceService;

    public LocationResourceController(LocationResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('appointments.appointment:read')")
    @Operation(summary = "The doors at one site, with their opening hours")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<LocationResourceView> list(CompanyScope scope, @RequestParam("locationId") UUID locationId) {
        return resourceService.listForLocation(scope, locationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Add a door",
            description = "Each door takes one vehicle at a time. A site with six doors has six of "
                    + "these - that is what makes no-double-booking a database guarantee.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationResourceView create(CompanyScope scope, @Valid @RequestBody LocationResourceRequest request) {
        return resourceService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Rename a door or change its default slot length")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationResourceView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody LocationResourceRequest request) {
        return resourceService.update(scope, id, request);
    }

    @PutMapping("/{id}/calendar")
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Replace a door's whole week of opening hours",
            description = "Local times at the site, never the server's. No overnight windows: a "
                    + "door open 22:00-06:00 is two entries, one on each day.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationResourceView replaceCalendar(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody ResourceCalendarRequest request) {
        return resourceService.replaceCalendar(scope, id, request);
    }

    @PostMapping("/{id}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Close a door for a specific interval",
            description = "A holiday, a stocktake, a broken leveller. Absolute instants, unlike the "
                    + "weekly calendar. Existing bookings in the interval are not cancelled - a "
                    + "person deals with the trucks already promised.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public void block(CompanyScope scope, @PathVariable UUID id,
            @RequestParam("startsAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
            @RequestParam("endsAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endsAt,
            @RequestParam("reason") String reason) {
        resourceService.block(scope, id, startsAt, endsAt, reason);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Take a door out of service",
            description = "New bookings are refused; existing ones are left alone, because a truck "
                    + "already on the road for a slot booked yesterday must not silently lose it.")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationResourceView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return resourceService.deactivate(scope, id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('appointments.resource:manage')")
    @Operation(summary = "Put a door back in service")
    @Parameter(name = ApiHeaders.COMPANY_ID, in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationResourceView activate(CompanyScope scope, @PathVariable UUID id) {
        return resourceService.activate(scope, id);
    }
}
