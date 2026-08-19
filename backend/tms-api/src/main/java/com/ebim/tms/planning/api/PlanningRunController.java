package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.EligibleOrderFilter;
import com.ebim.tms.planning.application.EligibleOrderView;
import com.ebim.tms.planning.application.PlanningActionRequest;
import com.ebim.tms.planning.application.PlanningRunDetailView;
import com.ebim.tms.planning.application.PlanningRunFilter;
import com.ebim.tms.planning.application.PlanningRunRequest;
import com.ebim.tms.planning.application.PlanningRunService;
import com.ebim.tms.planning.application.PlanningRunView;
import com.ebim.tms.planning.application.TripCreateRequest;
import com.ebim.tms.planning.application.TripDetailView;
import com.ebim.tms.planning.application.TripService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The planning board: the orders waiting to be planned, the runs that plan them, and the trips
 * created inside a run. Company-scoped exactly like every other controller - the
 * {@link CompanyScope} argument is only ever supplied once {@code CompanyScopeFilter} has
 * validated {@code X-Company-Id} against an active membership.
 *
 * <p><b>Permissions.</b> Run-level operations use {@code planning.plan:read}/{@code :manage};
 * anything that reads or writes trips also requires {@code planning.trip:read}/{@code :manage},
 * and anything that returns order fields also requires {@code orders.order:read}. A response is
 * never allowed to smuggle data the caller could not have read from the module that owns it -
 * see {@code docs/domain/PLANNING_MANUAL_V1.md}, "Authorization".
 */
@RestController
@RequestMapping("${tms.api.base-path}/planning")
@Tag(name = "Planning", description = "Manual planning: eligible orders, planning runs and their trips")
public class PlanningRunController {

    private final PlanningRunService planningRunService;
    private final TripService tripService;

    public PlanningRunController(PlanningRunService planningRunService, TripService tripService) {
        this.planningRunService = planningRunService;
        this.tripService = tripService;
    }

    @GetMapping("/eligible-orders")
    @PreAuthorize("hasAuthority('planning.plan:read') and hasAuthority('orders.order:read')")
    @Operation(summary = "List the orders that are ready for planning and not yet on a trip")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<EligibleOrderView> eligibleOrders(
            CompanyScope scope, @ModelAttribute EligibleOrderFilter filter, @ModelAttribute PageQuery pageQuery) {
        return planningRunService.eligibleOrders(scope, filter, pageQuery);
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('planning.plan:read')")
    @Operation(summary = "List planning runs, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<PlanningRunView> list(
            CompanyScope scope, @ModelAttribute PlanningRunFilter filter, @ModelAttribute PageQuery pageQuery) {
        return planningRunService.list(scope, filter, pageQuery);
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority('planning.plan:read') and hasAuthority('planning.trip:read')")
    @Operation(summary = "Get one planning run with its trips and their capacity summaries")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PlanningRunDetailView get(CompanyScope scope, @PathVariable UUID id) {
        return planningRunService.get(scope, id);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('planning.plan:manage')")
    @Operation(summary = "Open a manual planning run for an origin and a planning date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PlanningRunDetailView create(CompanyScope scope, @Valid @RequestBody PlanningRunRequest request) {
        return planningRunService.create(scope, request);
    }

    @PostMapping("/runs/{id}/confirm")
    @PreAuthorize("hasAuthority('planning.plan:manage') and hasAuthority('planning.trip:manage')")
    @Operation(summary = "Confirm a draft plan: revalidates every trip and freezes its capacity")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PlanningRunDetailView confirm(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody PlanningActionRequest request) {
        return planningRunService.confirm(scope, id, request);
    }

    @PostMapping("/runs/{id}/cancel")
    @PreAuthorize("hasAuthority('planning.plan:manage') and hasAuthority('planning.trip:manage')")
    @Operation(summary = "Discard a draft plan, cancelling its trips and releasing every order back to the pool")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PlanningRunDetailView cancel(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody PlanningActionRequest request) {
        return planningRunService.cancel(scope, id, request);
    }

    /**
     * Trip creation lives here, not on {@code TripController}: a trip only exists inside a run,
     * and the caller must present the <em>run's</em> version, so this is a run-level operation
     * that happens to return a trip.
     */
    @PostMapping("/runs/{runId}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Create a trip inside a draft planning run")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView createTrip(
            CompanyScope scope, @PathVariable UUID runId, @Valid @RequestBody TripCreateRequest request) {
        return tripService.create(scope, runId, request);
    }
}
