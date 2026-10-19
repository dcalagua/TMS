package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.MaterialProfileFilter;
import com.ebim.tms.masterdata.application.MaterialProfileService;
import com.ebim.tms.masterdata.application.MaterialProfileView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Material profiles, read only (ADR-022 section 1). The write path is
 * {@code PUT /integration/v1/material-profiles/{materialCode}}: the warehouse owns the profile.
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/material-profiles")
@Tag(name = "Material profiles", description = "Physical profiles of materials, synchronised from the warehouse")
public class MaterialProfileController {

    private final MaterialProfileService materialProfileService;

    public MaterialProfileController(MaterialProfileService materialProfileService) {
        this.materialProfileService = materialProfileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.material_profile:read')")
    @Operation(summary = "List material profiles, searched by code or description, within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<MaterialProfileView> list(CompanyScope scope, @ModelAttribute MaterialProfileFilter filter,
            @ModelAttribute PageQuery pageQuery) {
        return materialProfileService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.material_profile:read')")
    @Operation(summary = "Get one material profile")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public MaterialProfileView get(CompanyScope scope, @PathVariable UUID id) {
        return materialProfileService.get(scope, id);
    }
}
