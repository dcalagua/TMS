package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.DestinationFilter;
import com.ebim.tms.masterdata.application.DestinationRequest;
import com.ebim.tms.masterdata.application.DestinationService;
import com.ebim.tms.masterdata.application.DestinationView;
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
 * Company-scoped CRUD for destinations. Follows the {@code OriginController} template: the
 * {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership.
 *
 * <p>There is no delete endpoint. Destinations are deactivated, never removed (step brief).
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/destinations")
@Tag(name = "Destinations", description = "Delivery and service points")
public class DestinationController {

    private final DestinationService destinationService;

    public DestinationController(DestinationService destinationService) {
        this.destinationService = destinationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.destination:read')")
    @Operation(summary = "List destinations, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<DestinationView> list(
            CompanyScope scope, @ModelAttribute DestinationFilter filter, @ModelAttribute PageQuery pageQuery) {
        return destinationService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.destination:read')")
    @Operation(summary = "Get one destination")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DestinationView get(CompanyScope scope, @PathVariable UUID id) {
        return destinationService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.destination:manage')")
    @Operation(summary = "Create a destination")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DestinationView create(CompanyScope scope, @Valid @RequestBody DestinationRequest request) {
        return destinationService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.destination:manage')")
    @Operation(summary = "Update a destination")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DestinationView update(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody DestinationRequest request) {
        return destinationService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.destination:manage')")
    @Operation(summary = "Reactivate a destination")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DestinationView activate(CompanyScope scope, @PathVariable UUID id) {
        return destinationService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.destination:manage')")
    @Operation(summary = "Deactivate a destination")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public DestinationView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return destinationService.deactivate(scope, id);
    }
}
