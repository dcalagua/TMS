package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.IncidentCentreService;
import com.ebim.tms.settlement.application.IncidentCentreService.IncidentCentreView;
import com.ebim.tms.settlement.application.IncidentCentreService.IncidentFilter;
import com.ebim.tms.settlement.application.IncidentCentreService.IncidentKind;
import com.ebim.tms.settlement.application.IncidentCentreService.StatusGroup;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The incident centre (V71): open trip exceptions and freight claims on one list. Read-only; the
 * exception rows are planning's, so the endpoint needs {@code planning.trip:read} beside
 * {@code settlement.claim:read} - a claim reader who cannot read trips does not get them through
 * here.
 */
@RestController
@RequestMapping("${tms.api.base-path}/incidents")
@Tag(name = "Freight claims", description = "Claims against carriers for damage, shortage, loss and delay")
public class IncidentCentreController {

    private final IncidentCentreService service;

    public IncidentCentreController(IncidentCentreService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.claim:read') and hasAuthority('planning.trip:read')")
    @Operation(summary = "Trip exceptions and freight claims, oldest open first, with their age in days."
            + " Capped at limit per source; truncated says when there was more")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public IncidentCentreView list(CompanyScope scope,
            @RequestParam(required = false) StatusGroup status,
            @RequestParam(required = false) IncidentKind kind,
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        return service.list(scope, new IncidentFilter(status, kind, carrierId, type, from, to, limit));
    }
}
