package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.FiscalSettingsResolver;
import com.ebim.tms.settlement.domain.FiscalRules;
import com.ebim.tms.settlement.domain.TaxCalculator;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's resolved fiscal rules (V66, ADR-024 part 2, item 1), read-only, so the invoice form
 * previews the IGV and the detraction with the same figures the server will apply - and the
 * frontend carries no rate of its own.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/fiscal-settings")
@Tag(name = "Freight settlement", description = "Carrier invoices, matching, approval and payable export")
public class FiscalSettingsController {

    private final FiscalSettingsResolver resolver;

    public FiscalSettingsController(FiscalSettingsResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @param detractionStrictlyAboveThreshold how the threshold compares (a named rule, not a guess)
     * @param breakdownTolerance               how far subtotal + IGV may be from the total
     */
    public record FiscalSettingsView(String currency, BigDecimal igvRate, boolean detractionEnabled,
            String detractionCode, BigDecimal detractionRate, BigDecimal detractionThresholdAmount,
            boolean detractionStrictlyAboveThreshold, boolean retentionAgent, BigDecimal retentionRate,
            BigDecimal breakdownTolerance) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "The company's IGV, detraction and retention rules, defaults resolved")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FiscalSettingsView get(CompanyScope scope) {
        FiscalRules rules = resolver.rulesOf(scope.companyId());
        return new FiscalSettingsView(TaxCalculator.FISCAL_CURRENCY, rules.igvRate(), rules.detractionEnabled(),
                rules.detractionCode(), rules.detractionRate(), rules.detractionThresholdAmount(),
                TaxCalculator.DETRACTION_STRICTLY_ABOVE_THRESHOLD, rules.retentionAgent(), rules.retentionRate(),
                TaxCalculator.BREAKDOWN_TOLERANCE);
    }
}
