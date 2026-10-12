package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.ShipmentTrackingFilter;
import com.ebim.tms.planning.application.ShipmentTrackingRowView;
import com.ebim.tms.planning.application.ShipmentTrackingService;
import com.ebim.tms.planning.application.ShipmentTrackingSummaryView;
import com.ebim.tms.planning.domain.ShipmentDocumentState;
import com.ebim.tms.planning.domain.TripStatus;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dispatched-shipment tracking report (ADR-019 section 2). Read-only. */
@RestController
@RequestMapping("${tms.api.base-path}/monitoring/shipment-tracking")
@Tag(name = "Shipment tracking", description = "What the warehouse dispatched, and how its stops and documents stand")
public class ShipmentTrackingController {

    private final ShipmentTrackingService service;

    public ShipmentTrackingController(ShipmentTrackingService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Dispatched shipments, newest dispatch first")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<ShipmentTrackingRowView> list(CompanyScope scope,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(name = "carrierId", required = false) UUID carrierId,
            @RequestParam(name = "status", required = false) TripStatus status,
            @RequestParam(name = "documents", required = false) ShipmentDocumentState documents,
            @RequestParam(name = "q", required = false) String q,
            @Valid @ModelAttribute PageQuery pageQuery) {
        return service.list(scope, new ShipmentTrackingFilter(dateFrom, dateTo, carrierId, status, documents, q),
                pageQuery);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Totals of the dispatched shipments for the filter")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ShipmentTrackingSummaryView summary(CompanyScope scope,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(name = "carrierId", required = false) UUID carrierId,
            @RequestParam(name = "q", required = false) String q) {
        return service.summary(scope, new ShipmentTrackingFilter(dateFrom, dateTo, carrierId, null, null, q));
    }
}
