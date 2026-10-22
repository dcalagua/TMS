package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.CarrierScorecardService;
import com.ebim.tms.planning.application.CarrierScorecardTripsView;
import com.ebim.tms.planning.application.CarrierScorecardView;
import com.ebim.tms.planning.application.KpiExportFile;
import com.ebim.tms.planning.application.KpiFilter;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The carrier scorecard ({@code docs/domain/CARRIER_SCORECARD_V1.md}): per carrier, over a range of
 * operating days, how its trips ran. Read and never written.
 *
 * <p><b>{@code monitoring.transport:read}, and no new permission.</b> The same reasoning
 * {@code KpiController} gives: this is the transport the control tower already shows by carrier, asked
 * over a range. It gets a capability of its own ({@code CARRIER_SCORECARD_VIEW}) so its menu entry can
 * be told apart, but a capability is UX - the permission is what is enforced here.
 *
 * <p>What is commercially sensitive is answered with null, inside the service, for a caller without
 * the permission that owns it: tenders, documents, invoices and cost. The CSV leaves those columns out
 * of its header for the same caller.
 */
@RestController
@RequestMapping("${tms.api.base-path}/reporting/carrier-scorecard")
@Tag(name = "Carrier scorecard", description = "Read-only performance of each carrier over a range of operating days")
public class CarrierScorecardController {

    private final CarrierScorecardService service;

    public CarrierScorecardController(CarrierScorecardService service) {
        this.service = service;
    }

    /** One row per carrier and one for the own fleet; {@code from}/{@code to} as in the KPI report. */
    @GetMapping
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Performance of each carrier over a range of operating days")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierScorecardView report(CompanyScope scope, @ModelAttribute KpiFilter filter) {
        return service.report(scope, filter);
    }

    /**
     * The trips one row was counted from.
     *
     * @param carrier the carrier's id, or {@code OWN_FLEET}
     */
    @GetMapping("/trips")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "The trips of one carrier (or of the own fleet) in the range")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierScorecardTripsView trips(CompanyScope scope, @ModelAttribute KpiFilter filter,
            @RequestParam("carrier") String carrier) {
        return service.trips(scope, filter, carrier);
    }

    /** The scorecard as CSV, {@code no-store} like every download of tenant figures. */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Download the carrier scorecard as CSV")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> export(CompanyScope scope, @ModelAttribute KpiFilter filter) {
        KpiExportFile file = service.export(scope, filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName())
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(file.content().length)
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(file.content()));
    }
}
