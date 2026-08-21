package com.ebim.tms.rates.api;

import com.ebim.tms.rates.application.ActualCostRequest;
import com.ebim.tms.rates.application.TripCostService;
import com.ebim.tms.rates.application.TripCostView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What one shipment was estimated at and what it actually cost.
 *
 * <p>Served under {@code /rates} rather than under {@code /planning/trips/{id}/cost}, even though
 * the path variable is a trip: the URL space follows the module that owns the resource, the same
 * way {@code /fleet/carriers} does, so a reader of the API can tell which permission gates it
 * ({@code rates.trip_cost:*}) without looking it up.
 *
 * <p>{@code GET} answers 200 with the "not priced yet" shape for a trip nobody has costed, and 404
 * only when the trip itself does not exist in this company - see {@code TripCostView.none}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/rates/trips/{tripId}/cost")
@Tag(name = "Trip cost", description = "The estimated and actual cost of one shipment")
public class TripCostController {

    private final TripCostService tripCostService;

    public TripCostController(TripCostService tripCostService) {
        this.tripCostService = tripCostService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('rates.trip_cost:read')")
    @Operation(summary = "The estimated and actual cost of a shipment, with the lines behind the estimate")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCostView get(CompanyScope scope, @PathVariable UUID tripId) {
        return tripCostService.get(scope, tripId);
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasAuthority('rates.trip_cost:manage')")
    @Operation(summary = "Price the shipment against the rate card in force on its planning date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCostView estimate(CompanyScope scope, @PathVariable UUID tripId) {
        return tripCostService.estimate(scope, tripId);
    }

    @PutMapping("/actual")
    @PreAuthorize("hasAuthority('rates.trip_cost:manage')")
    @Operation(summary = "Record or correct what the carrier actually charged")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCostView recordActual(
            CompanyScope scope, @PathVariable UUID tripId, @Valid @RequestBody ActualCostRequest request) {
        return tripCostService.recordActual(scope, tripId, request);
    }

    @PostMapping("/close")
    @PreAuthorize("hasAuthority('rates.trip_cost:manage')")
    @Operation(summary = "Settle the cost; nothing may change it afterwards until it is reopened")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCostView close(CompanyScope scope, @PathVariable UUID tripId) {
        return tripCostService.close(scope, tripId);
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasAuthority('rates.trip_cost:manage')")
    @Operation(summary = "Make a settled cost writable again, audited as its own action")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripCostView reopen(CompanyScope scope, @PathVariable UUID tripId) {
        return tripCostService.reopen(scope, tripId);
    }
}
