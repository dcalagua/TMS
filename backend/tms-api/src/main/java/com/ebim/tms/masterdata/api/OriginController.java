package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.OriginFilter;
import com.ebim.tms.masterdata.application.OriginRequest;
import com.ebim.tms.masterdata.application.OriginService;
import com.ebim.tms.masterdata.application.OriginView;
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
 * Company-scoped CRUD for origins. Follows the {@code CompanyContextController} template:
 * the {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership,
 * so no method here can run against a company the caller does not belong to.
 *
 * <p>There is no delete endpoint. Origins are deactivated, never removed (step brief).
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/origins")
@Tag(name = "Origins", description = "Loading and dispatch points")
public class OriginController {

    private final OriginService originService;

    public OriginController(OriginService originService) {
        this.originService = originService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.origin:read')")
    @Operation(summary = "List origins, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<OriginView> list(
            CompanyScope scope, @ModelAttribute OriginFilter filter, @ModelAttribute PageQuery pageQuery) {
        return originService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.origin:read')")
    @Operation(summary = "Get one origin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OriginView get(CompanyScope scope, @PathVariable UUID id) {
        return originService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.origin:manage')")
    @Operation(summary = "Create an origin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OriginView create(CompanyScope scope, @Valid @RequestBody OriginRequest request) {
        return originService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.origin:manage')")
    @Operation(summary = "Update an origin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OriginView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody OriginRequest request) {
        return originService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.origin:manage')")
    @Operation(summary = "Reactivate an origin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OriginView activate(CompanyScope scope, @PathVariable UUID id) {
        return originService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.origin:manage')")
    @Operation(summary = "Deactivate an origin")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OriginView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return originService.deactivate(scope, id);
    }
}
