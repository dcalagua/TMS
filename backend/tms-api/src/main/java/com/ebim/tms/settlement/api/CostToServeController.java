package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.CostAllocationService;
import com.ebim.tms.settlement.application.CostAllocationView;
import com.ebim.tms.settlement.application.CostToServeReportService;
import com.ebim.tms.settlement.application.CostToServeView;
import com.ebim.tms.settlement.infrastructure.CostToServeQueries.GroupBy;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cost to serve (V68, ADR-026): the report by customer, destination or route, and the allocation of
 * one trip with its versions. There is no margin - TMS has no sales tariff - and every response says so.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement")
@Tag(name = "Settlement - cost to serve")
public class CostToServeController {

    private final CostToServeReportService reportService;
    private final CostAllocationService allocationService;
    private final AuditActorProvider auditActorProvider;

    public CostToServeController(CostToServeReportService reportService, CostAllocationService allocationService,
            AuditActorProvider auditActorProvider) {
        this.reportService = reportService;
        this.allocationService = allocationService;
        this.auditActorProvider = auditActorProvider;
    }

    @GetMapping("/cost-to-serve")
    @PreAuthorize("hasAuthority('settlement.cost_allocation:read')")
    @Operation(summary = "Cost per customer, destination or route: cost, per kg, per m3, per order; no margin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CostToServeView report(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy) {
        return reportService.report(scope, from, to, groupBy);
    }

    @GetMapping("/cost-to-serve/export")
    @PreAuthorize("hasAuthority('settlement.cost_allocation:read')")
    @Operation(summary = "The cost-to-serve report as CSV or JSON")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<?> export(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy,
            @RequestParam(defaultValue = "CSV") String format) {
        CostToServeView report = reportService.report(scope, from, to, groupBy);
        String name = "cost-to-serve-" + scope.companyCode() + "-" + report.groupBy().toLowerCase() + "-" + from
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
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(CostToServeReportService.csv(report));
    }

    @GetMapping("/cost-allocation/trips/{tripId}")
    @PreAuthorize("hasAuthority('settlement.cost_allocation:read')")
    @Operation(summary = "A trip's current allocation to its orders and every earlier version")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CostAllocationView trip(CompanyScope scope, @PathVariable UUID tripId) {
        return allocationService.view(scope, tripId);
    }

    @GetMapping("/cost-allocation/trips/{tripId}/versions/{version}")
    @PreAuthorize("hasAuthority('settlement.cost_allocation:read')")
    @Operation(summary = "One version of a trip's allocation, with its rows")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CostAllocationView.RunView version(CompanyScope scope, @PathVariable UUID tripId,
            @PathVariable int version) {
        return allocationService.version(scope, tripId, version);
    }

    @PostMapping("/cost-allocation/trips/{tripId}/recalculate")
    @PreAuthorize("hasAuthority('settlement.cost_allocation:manage')")
    @Operation(summary = "Recalculate: a new version that supersedes the current one, never overwriting it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CostAllocationView recalculate(CompanyScope scope, @PathVariable UUID tripId) {
        return allocationService.recalculate(scope, tripId, auditActorProvider.requireAppUserId());
    }
}
