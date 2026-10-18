package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.GlAccountMappingRequest;
import com.ebim.tms.settlement.application.GlAccountMappingService;
import com.ebim.tms.settlement.application.GlAccountMappingView;
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
 * Which ERP account each accounting concept is booked to (V65, ADR-023). The accrual journal reads
 * these; without one it warns and leaves the account empty.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/gl-mappings")
@Tag(name = "Freight accruals", description = "Accruals, aging, unaccrued trips and the accrual journal")
public class GlAccountMappingController {

    private final GlAccountMappingService service;

    public GlAccountMappingController(GlAccountMappingService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.accrual:read')")
    @Operation(summary = "The GL account mappings of the company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<GlAccountMappingView> list(CompanyScope scope,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.list(scope, includeInactive);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settlement.accrual:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Map a concept to an account for the company, an origin or a route. One active per scope")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public GlAccountMappingView create(CompanyScope scope, @Valid @RequestBody GlAccountMappingRequest request) {
        return service.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.accrual:manage')")
    @Operation(summary = "Change the account or cost center of an active mapping")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public GlAccountMappingView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody GlAccountMappingRequest request) {
        return service.update(scope, id, request);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('settlement.accrual:manage')")
    @Operation(summary = "Retire a mapping. Kept for past exports; the next most general scope applies")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public GlAccountMappingView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return service.deactivate(scope, id);
    }
}
