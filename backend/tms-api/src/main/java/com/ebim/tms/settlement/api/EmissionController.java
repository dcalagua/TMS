package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.EmissionReportView;
import com.ebim.tms.settlement.application.TripEmissionService;
import com.ebim.tms.settlement.application.TripEmissionView;
import com.ebim.tms.settlement.infrastructure.CostToServeQueries.GroupBy;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The carbon footprint (migration V73): one trip's, split between its orders, and a period's by
 * customer, destination or route. Read-only; nothing about it is stored. A trip without a factor or
 * a measurable route answers with its status and no figure - "sin datos", never zero.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/emissions")
@Tag(name = "Settlement - carbon footprint")
public class EmissionController {

    private final TripEmissionService emissionService;

    public EmissionController(TripEmissionService emissionService) {
        this.emissionService = emissionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.emission:read')")
    @Operation(summary = "kg CO2e of a period by customer, destination or route, with the trips that have no figure counted")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public EmissionReportView report(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy) {
        return emissionService.report(scope, from, to, groupBy);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('settlement.emission:read')")
    @Operation(summary = "The carbon footprint report as CSV or JSON")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<?> export(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy,
            @RequestParam(defaultValue = "CSV") String format) {
        EmissionReportView report = emissionService.report(scope, from, to, groupBy);
        String name = "carbon-footprint-" + scope.companyCode() + "-" + report.groupBy().toLowerCase() + "-" + from
                + "-" + to;
        if ("JSON".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(name + ".json").build().toString())
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(report);
        }
        if (!"CSV".equalsIgnoreCase(format)) {
            throw new InvalidRequestException("format must be CSV or JSON.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + ".csv").build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(TripEmissionService.csv(report));
    }

    @GetMapping("/trips/{tripId}")
    @PreAuthorize("hasAuthority('settlement.emission:read')")
    @Operation(summary = "One trip's kg CO2e and its split between its orders on the cost-allocation basis")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TripEmissionView trip(CompanyScope scope, @PathVariable UUID tripId) {
        return emissionService.trip(scope, tripId);
    }
}
