package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.DriverFilter;
import com.ebim.tms.fleet.application.DriverRequest;
import com.ebim.tms.fleet.application.DriverService;
import com.ebim.tms.fleet.application.DriverView;
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
 * Company-scoped CRUD for drivers. See {@link CarrierController} for the pattern this follows.
 * There is no delete endpoint - a driver is deactivated, never removed, because the shipments
 * they ran have to keep resolving their name.
 *
 * <p>Guarded by {@code fleet.driver:*} rather than by {@code fleet.vehicle:*} (migration V26): a
 * driver record holds personal data a vehicle record does not, and an installation must be able
 * to let a fleet clerk maintain trucks without opening the personnel file.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/drivers")
@Tag(name = "Drivers", description = "The people who drive: identity, licence and carrier assignment")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('fleet.driver:read')")
    @Operation(summary = "List drivers, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<DriverView> list(
            CompanyScope scope, @ModelAttribute DriverFilter filter, @ModelAttribute PageQuery pageQuery) {
        return driverService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.driver:read')")
    @Operation(summary = "Get one driver")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DriverView get(CompanyScope scope, @PathVariable UUID id) {
        return driverService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @Operation(summary = "Create a driver")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DriverView create(CompanyScope scope, @Valid @RequestBody DriverRequest request) {
        return driverService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @Operation(summary = "Update a driver")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DriverView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody DriverRequest request) {
        return driverService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @Operation(summary = "Reactivate a driver")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DriverView activate(CompanyScope scope, @PathVariable UUID id) {
        return driverService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('fleet.driver:manage')")
    @Operation(summary = "Deactivate a driver; the trips they already run keep them")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DriverView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return driverService.deactivate(scope, id);
    }
}
