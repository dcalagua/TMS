package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.FleetComplianceService;
import com.ebim.tms.fleet.application.FleetComplianceViews;
import com.ebim.tms.fleet.application.FleetDocumentFilter;
import com.ebim.tms.fleet.application.FleetDocumentRequest;
import com.ebim.tms.fleet.application.FleetDocumentService;
import com.ebim.tms.fleet.application.FleetDocumentView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fleet compliance documents (migration V72): the papers of vehicles and drivers, their expiry
 * status, what is missing, and the company's policy of which papers are mandatory and which block.
 * No delete endpoint - a document is deactivated, never removed.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet")
@Tag(name = "Fleet documents", description = "Compliance documents of vehicles and drivers, with expiry")
public class FleetDocumentController {

    private final FleetDocumentService documentService;
    private final FleetComplianceService complianceService;

    public FleetDocumentController(FleetDocumentService documentService, FleetComplianceService complianceService) {
        this.documentService = documentService;
        this.complianceService = complianceService;
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "List fleet documents with their status today, soonest expiry first")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<FleetDocumentView> list(CompanyScope scope, @ModelAttribute FleetDocumentFilter filter,
            @ModelAttribute PageQuery pageQuery) {
        return documentService.list(scope, filter, pageQuery);
    }

    @GetMapping("/documents/summary")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "The fleet's documents counted by status and by how far their expiry is")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetComplianceViews.Summary summary(CompanyScope scope) {
        return complianceService.summary(scope);
    }

    @GetMapping("/documents/missing")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "Active vehicles and drivers without a document of a mandatory type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<FleetComplianceViews.MissingDocument> missing(CompanyScope scope) {
        return complianceService.missing(scope);
    }

    @GetMapping("/documents/subjects/{subjectType}/{subjectId}")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "One vehicle's or driver's documents, active or not, and what is wrong today")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetComplianceViews.SubjectCompliance subject(CompanyScope scope,
            @PathVariable FleetDocumentSubject subjectType, @PathVariable UUID subjectId) {
        return complianceService.subject(scope, subjectType, subjectId);
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "Get one fleet document")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetDocumentView get(CompanyScope scope, @PathVariable UUID id) {
        return documentService.get(scope, id);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('fleet.document:manage')")
    @Operation(summary = "Record a fleet document for a vehicle or a driver")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetDocumentView create(CompanyScope scope, @Valid @RequestBody FleetDocumentRequest request) {
        return documentService.create(scope, request);
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('fleet.document:manage')")
    @Operation(summary = "Update a fleet document; its vehicle or driver never changes")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetDocumentView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody FleetDocumentRequest request) {
        return documentService.update(scope, id, request);
    }

    @PostMapping("/documents/{id}/activate")
    @PreAuthorize("hasAuthority('fleet.document:manage')")
    @Operation(summary = "Reactivate a fleet document")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetDocumentView activate(CompanyScope scope, @PathVariable UUID id) {
        return documentService.activate(scope, id);
    }

    @PostMapping("/documents/{id}/deactivate")
    @PreAuthorize("hasAuthority('fleet.document:manage')")
    @Operation(summary = "Deactivate a fleet document (it stops counting, and stays on record)")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetDocumentView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return documentService.deactivate(scope, id);
    }

    @GetMapping("/document-policy")
    @PreAuthorize("hasAuthority('fleet.document:read')")
    @Operation(summary = "Which document types are mandatory, which block when expired, and the alert window")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetComplianceViews.Policy policy(CompanyScope scope) {
        return complianceService.policy(scope);
    }

    @PutMapping("/document-policy")
    @PreAuthorize("hasAuthority('fleet.document_policy:manage')")
    @Operation(summary = "Save the alert window and the policy of the document types sent")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FleetComplianceViews.Policy updatePolicy(CompanyScope scope,
            @Valid @RequestBody FleetComplianceViews.PolicyRequest request) {
        return complianceService.updatePolicy(scope, request);
    }
}
