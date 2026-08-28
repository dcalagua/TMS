package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.AssignOrderRequest;
import com.ebim.tms.planning.application.MoveOrderRequest;
import com.ebim.tms.planning.application.PlanningActionRequest;
import com.ebim.tms.planning.application.TransportEventView;
import com.ebim.tms.planning.application.TripCapacityView;
import com.ebim.tms.planning.application.TripDetailView;
import com.ebim.tms.planning.application.TripDriverRequest;
import com.ebim.tms.planning.application.TripExceptionRequest;
import com.ebim.tms.planning.application.TripExceptionResolutionRequest;
import com.ebim.tms.planning.application.TripExceptionService;
import com.ebim.tms.planning.application.TripExecutionRequest;
import com.ebim.tms.planning.application.TripExecutionService;
import com.ebim.tms.planning.application.TripFilter;
import com.ebim.tms.planning.application.TripRouteRequest;
import com.ebim.tms.planning.application.StopEtaService;
import com.ebim.tms.planning.application.TripService;
import com.ebim.tms.planning.application.TripStopExecutionRequest;
import com.ebim.tms.planning.application.TripStopExecutionService;
import com.ebim.tms.planning.application.TripStopFailureRequest;
import com.ebim.tms.planning.application.TripStopOrderRequest;
import com.ebim.tms.planning.application.TripVehicleRequest;
import com.ebim.tms.planning.application.TripView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything done to one trip - its vehicle, the orders on it, the order of its stops - plus the
 * execution transitions that move it through a day, and the cross-run list the Trips screen reads.
 *
 * <p><b>Two write authorities, not one.</b> {@code planning.trip:manage} is planning: what a trip
 * carries and who runs it, and only while it is a draft. {@code planning.trip:execute} (migration
 * V25) is operating one: ready, dispatch, complete. Neither implies the other, so a dispatcher
 * cannot reopen a plan and a planner cannot report a departure they did not see. Both are granted
 * together to the seeded roles today; splitting them is a customer's choice, and the API is what
 * makes the choice possible.
 *
 * <p>Creating a trip is not here - it belongs to the run that contains it
 * ({@code PlanningRunController.createTrip}). There is no delete endpoint either: a trip is
 * cancelled, which releases its orders and keeps the record, matching the "deletes never erase
 * history" rule the whole schema follows.
 *
 * <p>Assignment endpoints take no {@code version}: they are serialised by the trip's row lock and
 * guarded by the assignment uniqueness invariant, so one planner assigning an order does not
 * invalidate another planner's open board. Operations that edit a field the caller read
 * (vehicle, route, cancellation) do take one - see {@code PlanningActionRequest}.
 *
 * <p>The route endpoint carries no extra authority beyond {@code planning.trip:manage}, matching
 * {@code updateVehicle}: like a vehicle, a route reaches a planning response as a display
 * reference (code and name), which every planning read already does for origins and destinations.
 * The extra {@code orders.order:read} on the read endpoints exists because those return order
 * <em>fields</em>, not a master's label.
 */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips")
@Tag(name = "Trips", description = "Manual planning trips: vehicle, order assignments, stops and capacity")
public class TripController {

    private final TripService tripService;
    private final StopEtaService stopEtaService;
    private final TripExecutionService executionService;
    private final TripStopExecutionService stopExecutionService;
    private final TripExceptionService exceptionService;

    public TripController(TripService tripService, StopEtaService stopEtaService,
            TripExecutionService executionService,
            TripStopExecutionService stopExecutionService, TripExceptionService exceptionService) {
        this.tripService = tripService;
        this.stopEtaService = stopEtaService;
        this.executionService = executionService;
        this.stopExecutionService = stopExecutionService;
        this.exceptionService = exceptionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('planning.trip:read')")
    @Operation(summary = "List this company's trips across planning runs - the execution board")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<TripView> list(
            CompanyScope scope, @ModelAttribute TripFilter filter, @ModelAttribute PageQuery pageQuery) {
        return tripService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('planning.trip:read') and hasAuthority('orders.order:read')")
    @Operation(summary = "Get one trip with its assignments, stops and capacity summary")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView get(CompanyScope scope, @PathVariable UUID id) {
        return tripService.get(scope, id);
    }

    @GetMapping("/{id}/capacity")
    @PreAuthorize("hasAuthority('planning.trip:read')")
    @Operation(summary = "Get the weight/volume/pallet utilisation of one trip, computed server-side")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCapacityView capacity(CompanyScope scope, @PathVariable UUID id) {
        return tripService.capacity(scope, id);
    }

    @PutMapping("/{id}/vehicle")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Set or swap the trip's vehicle, revalidating everything already assigned to it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView updateVehicle(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripVehicleRequest request) {
        return tripService.updateVehicle(scope, id, request);
    }

    /**
     * {@code planning.trip:manage} <em>or</em> {@code planning.trip:execute}, like cancellation and
     * for the same reason: naming the driver is a decision both halves of the day make. A planner
     * assigns one when they build the shipment; a dispatcher swaps one at 05:00 when the person
     * who was going to drive calls in sick. Which states still allow it is {@code TripService}'s
     * answer, not this method's.
     */
    @PutMapping("/{id}/driver")
    @PreAuthorize("hasAuthority('planning.trip:manage') or hasAuthority('planning.trip:execute')")
    @Operation(summary = "Name, swap or clear the trip's driver, checking licence and carrier compatibility")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView updateDriver(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripDriverRequest request) {
        return tripService.updateDriver(scope, id, request);
    }

    /**
     * Recomputes when the shipment is expected at each stop (migration V43, ADR-011).
     *
     * <p>{@code planning.trip:manage} <b>or</b> {@code planning.trip:execute}: an ETA is asked for
     * by whoever is looking at the shipment, and both halves of the day look at it. It writes no
     * business fact - the arrival a dispatcher records is still {@code actual_arrival_at}, entered
     * by a person - so it needs no authority sharper than "may work on this shipment".
     *
     * <p>Explicit, and not run on every write. A shipment whose stops changed has a stale estimate
     * until somebody asks, and {@code etaCalculatedAt} on each stop is how a reader tells. See
     * {@link StopEtaService} for why there is no background job.
     */
    @PostMapping("/{id}/eta")
    @PreAuthorize("hasAuthority('planning.trip:manage') or hasAuthority('planning.trip:execute')")
    @Operation(summary = "Recompute the expected arrival at each stop from the routing estimates")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView recomputeEta(CompanyScope scope, @PathVariable UUID id) {
        stopEtaService.recompute(scope, id);
        return tripService.get(scope, id);
    }

    @PostMapping("/{id}/assignments")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Assign one whole order to this trip, rejecting it if it does not fit")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView assignOrder(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody AssignOrderRequest request) {
        return tripService.assignOrder(scope, id, request);
    }

    @DeleteMapping("/{id}/assignments/{orderId}")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Take an order off this trip and return it to the eligible pool")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView removeOrder(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID orderId,
            @RequestParam(name = "reason", required = false) String reason) {
        return tripService.removeOrder(scope, id, orderId, reason);
    }

    @PostMapping("/{id}/assignments/{orderId}/move")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Move an order to another trip atomically; the source keeps it if the target has no room")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView moveOrder(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID orderId,
            @Valid @RequestBody MoveOrderRequest request) {
        return tripService.moveOrder(scope, id, orderId, request);
    }

    @PutMapping("/{id}/route")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Point the shipment at a master route as a suggestion, optionally adopting its stop order")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView updateRoute(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripRouteRequest request) {
        return tripService.updateRoute(scope, id, request);
    }

    @PutMapping("/{id}/stops")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Reorder the trip's stops; the list must be exactly the destinations it currently serves")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView reorderStops(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripStopOrderRequest request) {
        return tripService.reorderStops(scope, id, request);
    }

    @PostMapping("/{id}/ready")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Declare a confirmed trip loaded and ready for dispatch")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView markReady(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripExecutionRequest request) {
        return executionService.markReadyForDispatch(scope, id, request);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Send the vehicle out, recording the actual departure time")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView dispatch(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripExecutionRequest request) {
        return executionService.dispatch(scope, id, request);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Close a trip that is in transit, recording when it finished")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView complete(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripExecutionRequest request) {
        return executionService.complete(scope, id, request);
    }

    // --- stop execution (migration V27) --------------------------------------------------
    //
    // Five actions on one stop, all under planning.trip:execute, all refused unless the trip is
    // IN_TRANSIT. None takes a version - see TripStopExecutionRequest for why the trip's row lock
    // and the stop transition table are the guard here, exactly as they are for assignments.

    @PostMapping("/{id}/stops/{stopId}/arrive")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record that the vehicle reached this stop")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView arriveAtStop(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID stopId,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return stopExecutionService.arrive(scope, id, stopId, request);
    }

    @PostMapping("/{id}/stops/{stopId}/service")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record that loading or unloading has started at this stop")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView startStopService(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID stopId,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return stopExecutionService.startService(scope, id, stopId, request);
    }

    @PostMapping("/{id}/stops/{stopId}/complete")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record that this stop was served and the vehicle left")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView completeStop(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID stopId,
            @Valid @RequestBody TripStopExecutionRequest request) {
        return stopExecutionService.complete(scope, id, stopId, request);
    }

    /**
     * Skipping and failing take a typed reason and open a {@code TripException} with it, which is
     * what makes "how many deliveries did we miss, and why" a query. See
     * {@code TripStopFailureRequest}.
     */
    @PostMapping("/{id}/stops/{stopId}/skip")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record that this stop was never attempted, with the reason why")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView skipStop(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID stopId,
            @Valid @RequestBody TripStopFailureRequest request) {
        return stopExecutionService.skip(scope, id, stopId, request);
    }

    @PostMapping("/{id}/stops/{stopId}/fail")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Record that this stop was attempted and could not be served, with the reason why")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView failStop(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID stopId,
            @Valid @RequestBody TripStopFailureRequest request) {
        return stopExecutionService.fail(scope, id, stopId, request);
    }

    // --- timeline and exceptions (migration V27) -----------------------------------------

    /**
     * Reading the timeline is a read of the trip, so it carries {@code planning.trip:read} and not
     * the execute authority: a supervisor who may not touch a shipment must still be able to see
     * what happened to it.
     */
    @GetMapping("/{id}/events")
    @PreAuthorize("hasAuthority('planning.trip:read')")
    @Operation(summary = "The trip's operational timeline, oldest first")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TransportEventView> events(CompanyScope scope, @PathVariable UUID id) {
        return exceptionService.events(scope, id);
    }

    @PostMapping("/{id}/exceptions")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Report an operational problem on the trip or at one of its stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView reportException(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripExceptionRequest request) {
        return exceptionService.report(scope, id, request);
    }

    @PostMapping("/{id}/exceptions/{exceptionId}/resolve")
    @PreAuthorize("hasAuthority('planning.trip:execute')")
    @Operation(summary = "Close an open problem, recording what was done about it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView resolveException(CompanyScope scope, @PathVariable UUID id,
            @PathVariable UUID exceptionId, @Valid @RequestBody TripExceptionResolutionRequest request) {
        return exceptionService.resolve(scope, id, exceptionId, request);
    }

    /**
     * Cancellation is the one lifecycle action a planner and a dispatcher share, so it accepts
     * either authority rather than forcing a company to grant both to whoever pulls a shipment.
     * Which states it may still be reached from is {@code TripStatus}'s answer, not this method's.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('planning.trip:manage') or hasAuthority('planning.trip:execute')")
    @Operation(summary = "Cancel a trip before it departs, releasing every order on it back to the eligible pool")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView cancel(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody PlanningActionRequest request) {
        return tripService.cancel(scope, id, request);
    }
}
