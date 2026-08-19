package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.ZoneFilter;
import com.ebim.tms.masterdata.application.ZoneRequest;
import com.ebim.tms.masterdata.application.ZoneService;
import com.ebim.tms.masterdata.application.ZoneView;
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

/** Company-scoped CRUD for zones. See {@link OriginController} for the pattern this follows. */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/zones")
@Tag(name = "Zones", description = "Named operational areas")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.zone:read')")
    @Operation(summary = "List zones, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<ZoneView> list(
            CompanyScope scope, @ModelAttribute ZoneFilter filter, @ModelAttribute PageQuery pageQuery) {
        return zoneService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.zone:read')")
    @Operation(summary = "Get one zone")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ZoneView get(CompanyScope scope, @PathVariable UUID id) {
        return zoneService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.zone:manage')")
    @Operation(summary = "Create a zone")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ZoneView create(CompanyScope scope, @Valid @RequestBody ZoneRequest request) {
        return zoneService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.zone:manage')")
    @Operation(summary = "Update a zone")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ZoneView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody ZoneRequest request) {
        return zoneService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.zone:manage')")
    @Operation(summary = "Reactivate a zone")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ZoneView activate(CompanyScope scope, @PathVariable UUID id) {
        return zoneService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.zone:manage')")
    @Operation(summary = "Deactivate a zone")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ZoneView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return zoneService.deactivate(scope, id);
    }
}
