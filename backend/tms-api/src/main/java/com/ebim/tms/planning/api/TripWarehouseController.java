package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.TripWarehouseQueryService;
import com.ebim.tms.planning.application.TripWarehouseView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The trip workspace's warehouse card (ADR-013): documents, reconciliation and milestones. */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips/{tripId}/warehouse")
@Tag(name = "Trips", description = "Manual planning trips: vehicle, order assignments, stops and capacity")
public class TripWarehouseController {

    private final TripWarehouseQueryService queryService;

    public TripWarehouseController(TripWarehouseQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('planning.trip:read')")
    @Operation(summary = "How the trip departed and what the warehouse reported: dispatch documents, plan versus "
            + "dispatched, and milestones")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripWarehouseView get(CompanyScope scope, @PathVariable UUID tripId) {
        return queryService.forTrip(scope, tripId);
    }

    @GetMapping(value = "/documents/{documentId}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize("hasAuthority('planning.trip:read')")
    @Operation(summary = "A dispatch document exactly as the warehouse sent it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<String> raw(CompanyScope scope, @PathVariable UUID tripId, @PathVariable UUID documentId) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(queryService.rawDocument(scope, tripId, documentId));
    }
}
