package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.TripDocumentQueryService;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The documents travelling on a trip (ADR-015). Read-only. */
@RestController
@RequestMapping("${tms.api.base-path}/planning/trips/{tripId}/documents")
@Tag(name = "Trips", description = "Manual planning trips: vehicle, order assignments, stops and capacity")
public class TripDocumentController {

    private final TripDocumentQueryService queryService;

    public TripDocumentController(TripDocumentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('planning.trip:read') and hasAuthority('orders.order:read')")
    @Operation(summary = "The documents of every order this trip carries")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TripDocumentQueryService.OrderDocuments> list(CompanyScope scope, @PathVariable UUID tripId) {
        return queryService.documentsOf(scope, tripId);
    }
}
