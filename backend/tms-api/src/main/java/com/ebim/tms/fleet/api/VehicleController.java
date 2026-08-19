package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.VehicleFilter;
import com.ebim.tms.fleet.application.VehicleRequest;
import com.ebim.tms.fleet.application.VehicleService;
import com.ebim.tms.fleet.application.VehicleView;
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
 * Company-scoped CRUD for vehicles. See {@link CarrierController} for the pattern this follows.
 * There is no delete endpoint - vehicles are deactivated, never removed.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/vehicles")
@Tag(name = "Vehicles", description = "Physical vehicles, their carrier/type assignment and effective capacity")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('fleet.vehicle:read')")
    @Operation(summary = "List vehicles, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<VehicleView> list(
            CompanyScope scope, @ModelAttribute VehicleFilter filter, @ModelAttribute PageQuery pageQuery) {
        return vehicleService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.vehicle:read')")
    @Operation(summary = "Get one vehicle")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleView get(CompanyScope scope, @PathVariable UUID id) {
        return vehicleService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @Operation(summary = "Create a vehicle")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleView create(CompanyScope scope, @Valid @RequestBody VehicleRequest request) {
        return vehicleService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @Operation(summary = "Update a vehicle")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody VehicleRequest request) {
        return vehicleService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @Operation(summary = "Reactivate a vehicle")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleView activate(CompanyScope scope, @PathVariable UUID id) {
        return vehicleService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('fleet.vehicle:manage')")
    @Operation(summary = "Deactivate a vehicle")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return vehicleService.deactivate(scope, id);
    }
}
