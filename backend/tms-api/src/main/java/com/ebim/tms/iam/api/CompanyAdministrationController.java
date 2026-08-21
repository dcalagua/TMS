package com.ebim.tms.iam.api;

import com.ebim.tms.iam.application.CompanyAdministrationService;
import com.ebim.tms.iam.application.CompanyCreateRequest;
import com.ebim.tms.iam.application.CompanyProfileRequest;
import com.ebim.tms.iam.application.CompanyProfileView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant administration: the company profile, its operational settings, and adding a company to the
 * organization.
 *
 * <p>Separate from {@link CompanyContextController}, which serves {@code /companies/current} to the
 * shell on every sign-in. That one answers "what may I do here" for the menu; this one answers
 * "what is this company" for one screen, and the two are guarded by the same read permission but
 * have opposite traffic profiles. Merging them would put the settings on the authentication path -
 * see migration V34 section 1.
 *
 * <p>There is no deactivate endpoint, and that is a decision rather than an omission. Deactivating
 * the company the request is scoped to would end the caller's own session mid-request and leave the
 * only screen that could undo it unreachable, because {@code CompanyScope} is resolved from active
 * companies. Switching a tenant off is an organization-level act and belongs with the organization
 * administration this job did not build.
 */
@RestController
@RequestMapping("${tms.api.base-path}/admin/companies")
@Tag(name = "Company administration", description = "The company profile, its settings, and adding a company")
public class CompanyAdministrationController {

    private final CompanyAdministrationService companyAdministrationService;

    public CompanyAdministrationController(CompanyAdministrationService companyAdministrationService) {
        this.companyAdministrationService = companyAdministrationService;
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('iam.company:read')")
    @Operation(summary = "The selected company, the organization above it and its operational settings")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CompanyProfileView current(CompanyScope scope) {
        return companyAdministrationService.current(scope);
    }

    @PutMapping("/current")
    @PreAuthorize("hasAuthority('iam.company:manage')")
    @Operation(summary = "Update the selected company's profile and operational settings")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CompanyProfileView update(CompanyScope scope, @Valid @RequestBody CompanyProfileRequest request) {
        return companyAdministrationService.update(scope, request);
    }

    /**
     * Adds a company to the organization the caller is signed into.
     *
     * <p>{@code iam.company:manage} is necessary and not sufficient: the service additionally
     * requires an active organization-wide membership, which is what separates an ORGANIZATION_ADMIN
     * from a COMPANY_ADMIN who holds the same permission inside one company. That second condition
     * cannot be written as an authority expression because it is a fact about the caller's
     * membership shape, not about their permissions - so it lives in the service, where the
     * database can be asked.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('iam.company:manage')")
    @Operation(summary = "Create a company inside the caller's organization; requires an organization-wide role")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of; its organization receives the new company")
    public CompanyProfileView create(CompanyScope scope, @Valid @RequestBody CompanyCreateRequest request) {
        return companyAdministrationService.create(scope, request);
    }
}
