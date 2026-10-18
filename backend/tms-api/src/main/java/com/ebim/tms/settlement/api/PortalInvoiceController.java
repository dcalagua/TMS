package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.PortalInvoiceService;
import com.ebim.tms.settlement.application.PortalInvoiceViews;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Mis facturas" in the carrier portal (V67, ADR-023 section 2). Same pattern as
 * {@code planning.api.PortalTripController}: only {@code CARRIER_OPERATOR} holds
 * {@code portal.invoice:read}, and only through a carrier membership.
 */
@RestController
@RequestMapping("${tms.api.base-path}/portal/invoices")
@Tag(name = "Carrier portal", description = "My carrier's invoices: status, due date, what was paid, detraction")
public class PortalInvoiceController {

    private final PortalInvoiceService portal;

    public PortalInvoiceController(PortalInvoiceService portal) {
        this.portal = portal;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('portal.invoice:read')")
    @Operation(summary = "My carrier's invoices and notes, newest first")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<PortalInvoiceViews.Summary> list(CompanyScope scope) {
        return portal.list(scope);
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasAuthority('portal.invoice:read')")
    @Operation(summary = "One of my carrier's invoices: lines, payments, detraction and, if refused, why")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PortalInvoiceViews.Detail detail(CompanyScope scope, @PathVariable UUID invoiceId) {
        return portal.detail(scope, invoiceId);
    }
}
