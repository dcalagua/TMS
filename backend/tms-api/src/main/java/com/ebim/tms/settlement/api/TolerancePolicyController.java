package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.TolerancePolicyRequest;
import com.ebim.tms.settlement.application.TolerancePolicyService;
import com.ebim.tms.settlement.application.TolerancePolicyView;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * How far an invoice may differ before a person looks (V46 table, endpoint since V64, ADR-024 part 1).
 *
 * <p>Read with {@code settlement.invoice:read} - whoever works the audit queue must be able to see
 * the bound an invoice was judged against. Written with {@code settlement.tolerance:manage}, the
 * permission V46 seeded and nothing used until now.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/tolerance-policies")
@Tag(name = "Freight settlement", description = "Carrier invoices, matching, approval and payable export")
public class TolerancePolicyController {

    private final TolerancePolicyService service;

    public TolerancePolicyController(TolerancePolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.invoice:read')")
    @Operation(summary = "The tolerance policies: the company default and any per-carrier ones")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<TolerancePolicyView> list(CompanyScope scope,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.list(scope, includeInactive);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settlement.tolerance:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create the company default (no carrierId) or a carrier's policy. One active per scope")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TolerancePolicyView create(CompanyScope scope, @Valid @RequestBody TolerancePolicyRequest request) {
        return service.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.tolerance:manage')")
    @Operation(summary = "Change the bounds of an active policy. Matches already computed keep their snapshot")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TolerancePolicyView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody TolerancePolicyRequest request) {
        return service.update(scope, id, request);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('settlement.tolerance:manage')")
    @Operation(summary = "Retire a policy. Matching falls back to the company default, or to no tolerance")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public TolerancePolicyView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return service.deactivate(scope, id);
    }
}
