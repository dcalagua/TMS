package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.AssignOrderRequest;
import com.ebim.tms.planning.application.MoveOrderRequest;
import com.ebim.tms.planning.application.PlanningActionRequest;
import com.ebim.tms.planning.application.TripCapacityView;
import com.ebim.tms.planning.application.TripDetailView;
import com.ebim.tms.planning.application.TripService;
import com.ebim.tms.planning.application.TripStopOrderRequest;
import com.ebim.tms.planning.application.TripVehicleRequest;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything done to one trip: its vehicle, the orders on it and the order of its stops.
 *
 * <p>Creating a trip is not here - it belongs to the run that contains it
 * ({@code PlanningRunController.createTrip}). There is no delete endpoint either: a trip is
 * cancelled, which releases its orders and keeps the record, matching the "deletes never erase
 * history" rule the whole schema follows.
 *
 * <p>Assignment endpoints take no {@code version}: they are serialised by the trip's row lock and
 * guarded by the assignment uniqueness invariant, so one planner assigning an order does not
 * invalidate another planner's open board. Operations that edit a field the caller read
 * (vehicle, cancellation) do take one - see {@code PlanningActionRequest}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips")
@Tag(name = "Trips", description = "Manual planning trips: vehicle, order assignments, stops and capacity")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
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

    @PutMapping("/{id}/stops")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Reorder the trip's stops; the list must be exactly the destinations it currently serves")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView reorderStops(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody TripStopOrderRequest request) {
        return tripService.reorderStops(scope, id, request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('planning.trip:manage')")
    @Operation(summary = "Cancel a draft trip, releasing every order on it back to the eligible pool")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripDetailView cancel(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody PlanningActionRequest request) {
        return tripService.cancel(scope, id, request);
    }
}
