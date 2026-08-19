package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.VehicleTypeFilter;
import com.ebim.tms.fleet.application.VehicleTypeRequest;
import com.ebim.tms.fleet.application.VehicleTypeService;
import com.ebim.tms.fleet.application.VehicleTypeView;
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
 * Company-scoped CRUD for vehicle types. See {@link CarrierController} for the pattern this
 * follows. There is no delete endpoint - vehicle types are deactivated, never removed.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/vehicle-types")
@Tag(name = "Vehicle types", description = "Default capacities and physical characteristics for a class of vehicle")
public class VehicleTypeController {

    private final VehicleTypeService vehicleTypeService;

    public VehicleTypeController(VehicleTypeService vehicleTypeService) {
        this.vehicleTypeService = vehicleTypeService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('fleet.vehicle_type:read')")
    @Operation(summary = "List vehicle types, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<VehicleTypeView> list(
            CompanyScope scope, @ModelAttribute VehicleTypeFilter filter, @ModelAttribute PageQuery pageQuery) {
        return vehicleTypeService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.vehicle_type:read')")
    @Operation(summary = "Get one vehicle type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleTypeView get(CompanyScope scope, @PathVariable UUID id) {
        return vehicleTypeService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('fleet.vehicle_type:manage')")
    @Operation(summary = "Create a vehicle type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleTypeView create(CompanyScope scope, @Valid @RequestBody VehicleTypeRequest request) {
        return vehicleTypeService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.vehicle_type:manage')")
    @Operation(summary = "Update a vehicle type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleTypeView update(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody VehicleTypeRequest request) {
        return vehicleTypeService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('fleet.vehicle_type:manage')")
    @Operation(summary = "Reactivate a vehicle type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleTypeView activate(CompanyScope scope, @PathVariable UUID id) {
        return vehicleTypeService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('fleet.vehicle_type:manage')")
    @Operation(summary = "Deactivate a vehicle type")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public VehicleTypeView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return vehicleTypeService.deactivate(scope, id);
    }
}
