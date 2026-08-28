package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.CarrierInvoiceRequest;
import com.ebim.tms.settlement.application.CarrierInvoiceSummaryView;
import com.ebim.tms.settlement.application.CarrierInvoiceView;
import com.ebim.tms.settlement.application.FreightDiscrepancyView;
import com.ebim.tms.settlement.application.PayableExportView;
import com.ebim.tms.settlement.application.SettlementService;
import com.ebim.tms.settlement.domain.FreightDiscrepancy;
import com.ebim.tms.settlement.domain.InvoiceStatus;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freight audit: what a carrier billed, what TMS expected, and who decided (migration V46).
 *
 * <p><b>Six authorities, not one.</b> Recording an invoice, matching it, approving the expenditure
 * and exporting it are guarded separately, because an installation will want them in different
 * hands - and a single {@code settlement:manage} would let whoever types an invoice approve their
 * own.
 *
 * <p><b>TMS does not pay.</b> {@code /export} produces the artifact an ERP consumes and records that
 * it was handed over. Nothing here moves money.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/invoices")
@Tag(name = "Freight settlement", description = "Carrier invoices, matching, approval and payable export")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "The freight audit queue: what was billed, what was expected, and the difference")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<CarrierInvoiceSummaryView> list(CompanyScope scope,
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) List<InvoiceStatus> status,
            @ModelAttribute PageQuery pageQuery) {
        return settlementService.list(scope, carrierId, status, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "One invoice with its lines, its match, every difference and the decisions taken")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView get(CompanyScope scope, @PathVariable UUID id) {
        return settlementService.get(scope, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settlement.invoice:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record an invoice a carrier has sent")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView receive(CompanyScope scope, @Valid @RequestBody CarrierInvoiceRequest request) {
        return settlementService.receive(scope, request);
    }

    /**
     * Compares the invoice with what TMS expected.
     *
     * <p>Its own authority: matching reads every shipment's cost, which is a commercial disclosure
     * beyond simply seeing that an invoice arrived.
     */
    @PostMapping("/{id}/match")
    @PreAuthorize("hasAuthority('settlement.invoice:match')")
    @Operation(summary = "Compare the invoice with expected and actual cost, and record the verdict")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView match(CompanyScope scope, @PathVariable UUID id) {
        return settlementService.match(scope, id);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('settlement.invoice:match')")
    @Operation(summary = "Take a disputed invoice into review")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView beginReview(CompanyScope scope, @PathVariable UUID id) {
        return settlementService.beginReview(scope, id);
    }

    @PostMapping("/{id}/discrepancies/{discrepancyId}/resolve")
    @PreAuthorize("hasAuthority('settlement.invoice:match')")
    @Operation(summary = "Accept or reject one difference, with a note")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FreightDiscrepancyView resolveDiscrepancy(CompanyScope scope, @PathVariable UUID id,
            @PathVariable UUID discrepancyId, @Valid @RequestBody DiscrepancyDecisionRequest request) {
        return settlementService.resolveDiscrepancy(scope, id, discrepancyId, request.decision(),
                request.notes());
    }

    /**
     * A person authorises the obligation.
     *
     * <p>Its own permission, deliberately separate from everything else: this is the moment money
     * is committed. {@code requireAppUserId} refuses machine principals and
     * {@code settlement_approval.decided_by} is NOT NULL, so an unattended approval is not merely
     * forbidden here - it cannot be represented (debt D4).
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('settlement.invoice:approve')")
    @Operation(summary = "Authorise the obligation. Refused while any difference is unresolved")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView approve(CompanyScope scope, @PathVariable UUID id,
            @RequestBody(required = false) SettlementDecisionRequest request) {
        return settlementService.approve(scope, id, request == null ? null : request.comment());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('settlement.invoice:approve')")
    @Operation(summary = "Refuse the invoice. A reason is required - the carrier has to be able to answer it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierInvoiceView reject(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody SettlementDecisionRequest request) {
        return settlementService.reject(scope, id, request.comment());
    }

    /**
     * Hands the approved obligation to whoever pays.
     *
     * <p><b>Idempotent.</b> An invoice already exported returns its existing reference rather than
     * failing: two clicks must not create two obligations, and telling an operator "already
     * exported, here is the reference" is more useful than an error they have to interpret.
     */
    @PostMapping("/{id}/export")
    @PreAuthorize("hasAuthority('settlement.invoice:export')")
    @Operation(summary = "Export the approved obligation for accounting. Repeating it returns the same export")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableExportView export(CompanyScope scope, @PathVariable UUID id) {
        return settlementService.export(scope, id);
    }

    /** A decision on one difference. */
    public record DiscrepancyDecisionRequest(
            @jakarta.validation.constraints.NotNull(message = "is required")
            FreightDiscrepancy.Status decision,
            @jakarta.validation.constraints.Size(max = 1000) String notes) {
    }

    /** A decision on the invoice. The comment is required on a rejection - the service enforces it. */
    public record SettlementDecisionRequest(
            @jakarta.validation.constraints.Size(max = 1000) String comment) {
    }
}
