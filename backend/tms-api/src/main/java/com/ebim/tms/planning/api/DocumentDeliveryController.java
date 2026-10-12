package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.DocumentDeliveryDetailView;
import com.ebim.tms.planning.application.DocumentDeliveryFilter;
import com.ebim.tms.planning.application.DocumentDeliveryMonitorService;
import com.ebim.tms.planning.application.DocumentDeliveryRowView;
import com.ebim.tms.planning.application.DocumentDeliverySummaryView;
import com.ebim.tms.planning.application.DocumentReturnRequest;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
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
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The office's document delivery monitor (ADR-018). */
@RestController
@RequestMapping("${tms.api.base-path}/document-deliveries")
@Tag(name = "Document delivery", description = "What carriers reported about the documents on their trips")
public class DocumentDeliveryController {

    private final DocumentDeliveryMonitorService monitor;

    public DocumentDeliveryController(DocumentDeliveryMonitorService monitor) {
        this.monitor = monitor;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('planning.document_delivery:read')")
    @Operation(summary = "Tracked documents, filtered by dispatch date, carrier and state")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<DocumentDeliveryRowView> list(CompanyScope scope,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(name = "carrierId", required = false) UUID carrierId,
            @RequestParam(name = "status", required = false) DocumentDeliveryStatus status,
            @RequestParam(name = "returned", required = false) Boolean returned,
            @RequestParam(name = "q", required = false) String q,
            @Valid @ModelAttribute PageQuery pageQuery) {
        return monitor.list(scope, new DocumentDeliveryFilter(dateFrom, dateTo, carrierId, status, returned, q),
                pageQuery);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('planning.document_delivery:read')")
    @Operation(summary = "Totals by state and by carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DocumentDeliverySummaryView summary(CompanyScope scope,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate dateTo,
            @RequestParam(name = "carrierId", required = false) UUID carrierId,
            @RequestParam(name = "q", required = false) String q) {
        return monitor.summary(scope, new DocumentDeliveryFilter(dateFrom, dateTo, carrierId, null, null, q));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('planning.document_delivery:read')")
    @Operation(summary = "One tracked document with its history and evidence")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DocumentDeliveryDetailView detail(CompanyScope scope, @PathVariable UUID id) {
        return monitor.detail(scope, id);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('planning.document_delivery:return')")
    @Operation(summary = "Confirm the signed copy is back at the office")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DocumentDeliveryDetailView confirmReturn(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody(required = false) DocumentReturnRequest request) {
        return monitor.confirmReturn(scope, id, request);
    }

    @GetMapping("/{id}/evidence/{evidenceId}/content")
    @PreAuthorize("hasAuthority('planning.document_delivery:read')")
    @Operation(summary = "Download a photo or signature")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> evidence(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID evidenceId) {
        return PortalTripController.asAttachment(monitor.download(scope, id, evidenceId));
    }
}
