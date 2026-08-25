package com.ebim.tms.audit.api;

import com.ebim.tms.audit.application.AuditEventView;
import com.ebim.tms.audit.application.AuditFilter;
import com.ebim.tms.audit.application.AuditQueryService;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail, for administration and compliance.
 *
 * <p><b>One verb, and no others.</b> There is no POST, PUT, PATCH or DELETE here and there will
 * not be: entries are written as a side effect of the actions they describe ({@code AuditRecorder}
 * on every business write), and the table refuses UPDATE and DELETE to the runtime role
 * (migration V22). An endpoint that could correct the trail would make it evidence of nothing.
 *
 * <p><b>Behind its own permission.</b> {@code audit.log:read} is granted to the two administrator
 * roles and not to {@code PLANNER} (migration V3): who changed what is a different kind of
 * question from the operational ones, and the answer names colleagues. A planner needing it can be
 * granted it; nobody has it by default because they can see the trips.
 *
 * <p>The company comes from {@code X-Company-Id} through {@link CompanyScope}, validated against
 * the caller's memberships before this method is reached - it is never a query parameter here.
 */
@RestController
@RequestMapping("${tms.api.base-path}/audit-events")
@Tag(name = "Audit", description = "Read-only history of who changed what, within the selected company")
public class AuditController {

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit.log:read')")
    @Operation(summary = "List audit entries, newest first, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<AuditEventView> list(
            CompanyScope scope, @ModelAttribute AuditFilter filter, @ModelAttribute PageQuery pageQuery) {
        return auditQueryService.list(scope, filter, pageQuery);
    }
}
