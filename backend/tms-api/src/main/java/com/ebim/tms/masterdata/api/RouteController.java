package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.RouteDetailView;
import com.ebim.tms.masterdata.application.RouteFilter;
import com.ebim.tms.masterdata.application.RouteRequest;
import com.ebim.tms.masterdata.application.RouteService;
import com.ebim.tms.masterdata.application.RouteView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-scoped CRUD for master routes. Follows the {@code OriginController} template: the
 * {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership.
 *
 * <p>List returns {@link RouteView} (a stop count, not the stops themselves); every other
 * endpoint returns {@link RouteDetailView} (the ordered stops) - see those records' class
 * comments for why the shapes differ.
 *
 * <p>There is no delete endpoint. Routes are deactivated, never removed (step brief) - this is
 * also what keeps a deactivated origin/destination/zone from silently erasing route history.
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/routes")
@Tag(name = "Routes", description = "Master planned corridors: origin plus ordered destination stops")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.route:read')")
    @Operation(summary = "List routes, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<RouteView> list(
            CompanyScope scope, @ModelAttribute RouteFilter filter, @ModelAttribute PageQuery pageQuery) {
        return routeService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.route:read')")
    @Operation(summary = "Get one route, including its ordered stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RouteDetailView get(CompanyScope scope, @PathVariable UUID id) {
        return routeService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.route:manage')")
    @Operation(summary = "Create a route with its ordered destination stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RouteDetailView create(CompanyScope scope, @Valid @RequestBody RouteRequest request) {
        return routeService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.route:manage')")
    @Operation(summary = "Update a route, transactionally replacing its ordered stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RouteDetailView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody RouteRequest request) {
        return routeService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.route:manage')")
    @Operation(summary = "Reactivate a route")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RouteDetailView activate(CompanyScope scope, @PathVariable UUID id) {
        return routeService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.route:manage')")
    @Operation(summary = "Deactivate a route")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RouteDetailView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return routeService.deactivate(scope, id);
    }
}
