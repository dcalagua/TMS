package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.KpiExportFile;
import com.ebim.tms.planning.application.KpiFilter;
import com.ebim.tms.planning.application.KpiReportView;
import com.ebim.tms.planning.application.KpiService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The KPI report ({@code docs/domain/KPIS_REPORTING_V1.md}): a span of operating days, read and
 * never written.
 *
 * <p><b>Two endpoints for one report.</b> The screen reads JSON; the export writes a CSV of the
 * daily table. They are separate because they are asked for at different moments - one when a
 * filter changes, one when somebody presses a button - and because a JSON body that carried a
 * base64 file would make every filter change re-encode a file nobody asked for.
 *
 * <p><b>{@code monitoring.transport:read} and no new permission.</b> The same decision
 * {@code ControlTowerController} and {@code TrackingController} made, for the same reason. That
 * permission has meant "see where the transport is" since migration V3, and this is the same
 * disclosure asked over a quarter instead of over a day: how many shipments ran, how many left on
 * time, how many stops were missed. Minting {@code reporting.kpi:read} beside it would give a
 * company two permissions for one idea and force every seeded role to be re-granted before a screen
 * they were already entitled to would open.
 *
 * <p>It has no {@code manage} counterpart and never will. There is nothing here to manage: every
 * number is an aggregate of rows other endpoints wrote, and a report that could change what it
 * reports would not be one.
 *
 * <p><b>Where authority is finer than the endpoint.</b> Three sections of the response are about
 * something other than transport and are answered with null for a caller who does not also hold the
 * permission that owns them - orders ({@code orders.order:read}), tendering
 * ({@code planning.tender:read}) and cost ({@code rates.trip_cost:read}). Those checks live in
 * {@code KpiService}, on the fields they protect, rather than as three more {@code hasAuthority}
 * expressions here that would take the whole screen away over one card. The export carries none of
 * the three: it is the daily operational table, so there is nothing on it to gate.
 *
 * <p><b>What it discloses.</b> Counts and percentages, and nothing that names a shipment, an order,
 * a customer, a driver or a carrier. A caller cannot use this to learn what one shipment cost or
 * who refused it - which is what makes reusing a monitoring permission over a quarter of history
 * defensible.
 */
@RestController
@RequestMapping("${tms.api.base-path}/reporting/kpis")
@Tag(name = "KPIs", description = "Read-only operational and commercial indicators over a range of operating days")
public class KpiController {

    private final KpiService kpiService;

    public KpiController(KpiService kpiService) {
        this.kpiService = kpiService;
    }

    /**
     * The report: headline sections plus one row per day in the range.
     *
     * <p>{@code from} and {@code to} are both optional and both inclusive. Omitting {@code to}
     * means today <em>in the company's zone</em>, so a browser in another time zone cannot shift
     * which days the report is about; omitting {@code from} means thirty days before that. The
     * range is capped - see {@code KpiRange} - and a range longer than the cap is refused by name
     * rather than quietly truncated.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Operational and commercial indicators over a range of operating days")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public KpiReportView report(CompanyScope scope, @ModelAttribute KpiFilter filter) {
        return kpiService.report(scope, filter);
    }

    /**
     * The daily table as a CSV, named for the range it covers.
     *
     * <p>{@code no-store}, like every other download here: the file is a tenant's operating figures
     * and must not sit in a shared proxy. The name is the server's, so a file on somebody's desktop
     * still says which days it is about - see {@code KpiExportFile}.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "Download the daily indicator table as CSV")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> export(CompanyScope scope, @ModelAttribute KpiFilter filter) {
        KpiExportFile file = kpiService.exportDaily(scope, filter);
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
