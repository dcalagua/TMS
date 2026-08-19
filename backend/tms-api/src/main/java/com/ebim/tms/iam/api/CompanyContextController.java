package com.ebim.tms.iam.api;

import com.ebim.tms.iam.application.CompanyAccessView;
import com.ebim.tms.iam.application.CompanyContextService;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first company-scoped endpoint, and the template for every one that follows.
 *
 * <p>Three things make it company-scoped, and all three are decided on the server:
 *
 * <ol>
 *   <li>the {@link CompanyScope} parameter, which the framework supplies only if
 *       {@code X-Company-Id} named a company the caller holds an active membership in;</li>
 *   <li>{@code @PreAuthorize}, evaluated against authorities that are the permissions of
 *       <em>that</em> company and no other;</li>
 *   <li>a use case that takes the resolved scope rather than an id from the request.</li>
 * </ol>
 *
 * <p>The controller itself stays thin: authorize, delegate, return.
 */
@RestController
@RequestMapping("${tms.api.base-path}/companies")
@Tag(name = "Company context", description = "The company the current request operates in")
public class CompanyContextController {

    private final CompanyContextService companyContextService;

    public CompanyContextController(CompanyContextService companyContextService) {
        this.companyContextService = companyContextService;
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('iam.company:read')")
    @Operation(summary = "The selected company and the caller's effective access inside it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public CompanyAccessView current(CompanyScope scope) {
        return companyContextService.describe(scope);
    }
}
