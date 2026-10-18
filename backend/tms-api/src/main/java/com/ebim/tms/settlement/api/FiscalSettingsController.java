package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.FiscalSettingsRequest;
import com.ebim.tms.settlement.application.FiscalSettingsService;
import com.ebim.tms.settlement.application.TaxRegimeView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's tax regime (V66, ADR-027): resolved, so the invoice form labels, validates and
 * previews a carrier document with the same rules the server applies and the frontend carries no rate,
 * code or format of its own; and editable by whoever holds {@code settlement.tax:manage}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/fiscal-settings")
@Tag(name = "Freight settlement", description = "Carrier invoices, matching, approval and payable export")
public class FiscalSettingsController {

    private final FiscalSettingsService service;

    public FiscalSettingsController(FiscalSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "The company's tax regime: VAT, document kinds and numbering, tax id, withholdings")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TaxRegimeView get(CompanyScope scope) {
        return service.get(scope);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('settlement.tax:manage')")
    @Operation(summary = "Replace the company's fiscal country, VAT rates and withholding overrides")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TaxRegimeView replace(CompanyScope scope, @Valid @RequestBody FiscalSettingsRequest request) {
        return service.replace(scope, request);
    }
}
