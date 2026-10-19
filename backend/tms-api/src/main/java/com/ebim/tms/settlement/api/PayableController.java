package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.PayableReportViews;
import com.ebim.tms.settlement.application.PayableRequests;
import com.ebim.tms.settlement.application.PayableService;
import com.ebim.tms.settlement.application.PayableView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment status of carrier invoices after the export (V67, ADR-023 section 2). TMS records what the
 * ERP paid, or what two people keyed; it moves no money (D-01).
 *
 * <p>Reading is {@code settlement.invoice:read}. Keying a payment, scheduling and holding are
 * {@code settlement.payment:record}; confirming or rejecting somebody else's payment is
 * {@code settlement.payment:confirm}, and the service refuses the person who keyed it.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/payables")
@Tag(name = "Freight settlement - payables",
        description = "Due dates, payment status, aging and four-eyes payment recording. TMS pays nothing")
public class PayableController {

    private final PayableService payableService;

    public PayableController(PayableService payableService) {
        this.payableService = payableService;
    }

    @GetMapping("/aging")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "What is owed to carriers, by days past due, per currency and per carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableReportViews.AgingView aging(CompanyScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return payableService.aging(scope, asOf);
    }

    @GetMapping("/due")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "Open payables due within the next days, overdue ones included, by due date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<PayableReportViews.DuePayableView> due(CompanyScope scope,
            @RequestParam(defaultValue = "15") int within,
            @RequestParam(required = false) UUID carrierId) {
        return payableService.due(scope, within, carrierId);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "The Control Tower card: overdue and soon-due payables per currency, pending confirmations")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableReportViews.SummaryView summary(CompanyScope scope) {
        return payableService.summary(scope);
    }

    @GetMapping("/payment-requests")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "Payments keyed by a person and waiting for a second person")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<PayableReportViews.PendingPaymentRequestView> pendingRequests(CompanyScope scope) {
        return payableService.pendingRequests(scope);
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "One invoice as a payable: due date, balance, payments and pending confirmations")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView get(CompanyScope scope, @PathVariable UUID invoiceId) {
        return payableService.get(scope, invoiceId);
    }

    @PostMapping("/{invoiceId}/schedule")
    @PreAuthorize("hasAuthority('settlement.payment:record')")
    @Operation(summary = "Plan the payment of an exported invoice for a date")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView schedule(CompanyScope scope, @PathVariable UUID invoiceId,
            @Valid @RequestBody PayableRequests.ScheduleRequest request) {
        return payableService.schedule(scope, invoiceId, request.scheduledPaymentDate());
    }

    @PostMapping("/{invoiceId}/hold")
    @PreAuthorize("hasAuthority('settlement.payment:record')")
    @Operation(summary = "Stop the payment of an invoice, with a reason")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView hold(CompanyScope scope, @PathVariable UUID invoiceId,
            @Valid @RequestBody PayableRequests.ReasonRequest request) {
        return payableService.hold(scope, invoiceId, request.reason());
    }

    @PostMapping("/{invoiceId}/release")
    @PreAuthorize("hasAuthority('settlement.payment:record')")
    @Operation(summary = "Lift a hold, with a reason")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView release(CompanyScope scope, @PathVariable UUID invoiceId,
            @Valid @RequestBody PayableRequests.ReasonRequest request) {
        return payableService.release(scope, invoiceId, request.reason());
    }

    @PostMapping("/{invoiceId}/payment-requests")
    @PreAuthorize("hasAuthority('settlement.payment:record')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Key a payment the ERP made; a different person must confirm it",
            description = "422 payment-exceeds-balance or payment-currency-mismatch; 409 before the export, on a"
                    + " note, or with a reference already used.")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView requestPayment(CompanyScope scope, @PathVariable UUID invoiceId,
            @Valid @RequestBody PayableRequests.ManualPaymentRequest request) {
        return payableService.requestPayment(scope, invoiceId, request);
    }

    @PostMapping("/{invoiceId}/payment-requests/{requestId}/confirm")
    @PreAuthorize("hasAuthority('settlement.payment:confirm')")
    @Operation(summary = "Confirm a payment somebody else keyed. Refused to the person who keyed it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView confirm(CompanyScope scope, @PathVariable UUID invoiceId, @PathVariable UUID requestId) {
        return payableService.confirmPayment(scope, invoiceId, requestId);
    }

    @PostMapping("/{invoiceId}/payment-requests/{requestId}/reject")
    @PreAuthorize("hasAuthority('settlement.payment:confirm')")
    @Operation(summary = "Refuse a keyed payment, with a reason")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PayableView reject(CompanyScope scope, @PathVariable UUID invoiceId, @PathVariable UUID requestId,
            @Valid @RequestBody PayableRequests.DecisionRequest request) {
        return payableService.rejectPayment(scope, invoiceId, requestId, request.comment());
    }
}
