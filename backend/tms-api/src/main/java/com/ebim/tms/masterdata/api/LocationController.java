package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.EligibilityView;
import com.ebim.tms.masterdata.application.LocationEligibilityService;
import com.ebim.tms.masterdata.application.LocationFilter;
import com.ebim.tms.masterdata.application.LocationFrequencyDateRangeRequest;
import com.ebim.tms.masterdata.application.LocationFrequencyRequest;
import com.ebim.tms.masterdata.application.LocationFrequencyService;
import com.ebim.tms.masterdata.application.LocationFrequencyView;
import com.ebim.tms.masterdata.application.LocationRequest;
import com.ebim.tms.masterdata.application.LocationService;
import com.ebim.tms.masterdata.application.LocationView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-scoped CRUD for the canonical Location master. Follows the {@code OriginController}
 * template: the {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership.
 *
 * <p>There is no delete endpoint. Locations are deactivated, never removed - the same rule the
 * other masters follow, and here it is also a hard requirement: {@code route},
 * {@code transport_order} and {@code planning_run} reference the compatibility projections with
 * {@code ON DELETE RESTRICT}.
 *
 * <p>{@code /masterdata/origins} and {@code /masterdata/destinations} keep working unchanged.
 * They are compatibility surfaces now, kept in sync by
 * {@code LocationCompatibilityProjector} - see {@code docs/architecture/ADR_LOCATION_MODEL.md}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/locations")
@Tag(name = "Locations", description = "Canonical physical places: stores, warehouses, plants, hubs and delivery points")
public class LocationController {

    private final LocationService locationService;
    private final LocationFrequencyService locationFrequencyService;
    private final LocationEligibilityService locationEligibilityService;

    public LocationController(LocationService locationService, LocationFrequencyService locationFrequencyService,
            LocationEligibilityService locationEligibilityService) {
        this.locationService = locationService;
        this.locationFrequencyService = locationFrequencyService;
        this.locationEligibilityService = locationEligibilityService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "List locations, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<LocationView> list(
            CompanyScope scope, @ModelAttribute LocationFilter filter, @ModelAttribute PageQuery pageQuery) {
        return locationService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "Get one location")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationView get(CompanyScope scope, @PathVariable UUID id) {
        return locationService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Create a location, together with the origin/destination projections its roles imply")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationView create(CompanyScope scope, @Valid @RequestBody LocationRequest request) {
        return locationService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Update a location and its projections")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationView update(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        return locationService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Reactivate a location and its projections")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationView activate(CompanyScope scope, @PathVariable UUID id) {
        return locationService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Deactivate a location and its projections")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return locationService.deactivate(scope, id);
    }

    @GetMapping("/{id}/frequencies")
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "List a location's service-frequency (calendar) associations")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<LocationFrequencyView> listFrequencies(CompanyScope scope, @PathVariable UUID id) {
        return locationFrequencyService.list(scope, id);
    }

    @PostMapping("/{id}/frequencies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Associate a frequency (service calendar) with a location")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationFrequencyView createFrequency(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody LocationFrequencyRequest request) {
        return locationFrequencyService.create(scope, id, request);
    }

    @PutMapping("/{id}/frequencies/{associationId}")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Update the effective date range of a location's frequency association")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationFrequencyView updateFrequency(CompanyScope scope, @PathVariable UUID id,
            @PathVariable UUID associationId, @Valid @RequestBody LocationFrequencyDateRangeRequest request) {
        return locationFrequencyService.update(scope, id, associationId, request);
    }

    @PostMapping("/{id}/frequencies/{associationId}/activate")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Reactivate a location's frequency association")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationFrequencyView activateFrequency(
            CompanyScope scope, @PathVariable UUID id, @PathVariable UUID associationId) {
        return locationFrequencyService.activate(scope, id, associationId);
    }

    @PostMapping("/{id}/frequencies/{associationId}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Pause a location's frequency association without losing its date range")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public LocationFrequencyView deactivateFrequency(
            CompanyScope scope, @PathVariable UUID id, @PathVariable UUID associationId) {
        return locationFrequencyService.deactivate(scope, id, associationId);
    }

    @DeleteMapping("/{id}/frequencies/{associationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Remove a location's frequency association")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public void deleteFrequency(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID associationId) {
        locationFrequencyService.delete(scope, id, associationId);
    }

    @GetMapping("/{id}/eligibility")
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "Evaluate whether a location can be serviced/dispatched on a given date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public EligibilityView eligibility(CompanyScope scope, @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return locationEligibilityService.evaluate(scope, id, date);
    }
}
