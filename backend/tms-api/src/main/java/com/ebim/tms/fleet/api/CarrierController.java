package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.CarrierFilter;
import com.ebim.tms.fleet.application.CarrierRequest;
import com.ebim.tms.fleet.application.CarrierService;
import com.ebim.tms.fleet.application.CarrierView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Company-scoped CRUD for carriers. Follows the {@code OriginController} (masterdata module)
 * template: the {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership.
 *
 * <p>There is no delete endpoint. Carriers are deactivated, never removed (step brief).
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/carriers")
@Tag(name = "Carriers", description = "Transport providers used by vehicles")
public class CarrierController {

    private final CarrierService carrierService;

    public CarrierController(CarrierService carrierService) {
        this.carrierService = carrierService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('fleet.carrier:read')")
    @Operation(summary = "List carriers, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<CarrierView> list(
            CompanyScope scope, @ModelAttribute CarrierFilter filter, @ModelAttribute PageQuery pageQuery) {
        return carrierService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.carrier:read')")
    @Operation(summary = "Get one carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierView get(CompanyScope scope, @PathVariable UUID id) {
        return carrierService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('fleet.carrier:manage')")
    @Operation(summary = "Create a carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierView create(CompanyScope scope, @Valid @RequestBody CarrierRequest request) {
        return carrierService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.carrier:manage')")
    @Operation(summary = "Update a carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody CarrierRequest request) {
        return carrierService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('fleet.carrier:manage')")
    @Operation(summary = "Reactivate a carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierView activate(CompanyScope scope, @PathVariable UUID id) {
        return carrierService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('fleet.carrier:manage')")
    @Operation(summary = "Deactivate a carrier")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CarrierView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return carrierService.deactivate(scope, id);
    }
}
