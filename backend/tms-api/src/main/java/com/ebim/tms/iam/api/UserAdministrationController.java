package com.ebim.tms.iam.api;

import com.ebim.tms.iam.application.AdministeredUserFilter;
import com.ebim.tms.iam.application.AdministeredUserView;
import com.ebim.tms.iam.application.RoleView;
import com.ebim.tms.iam.application.UserAdministrationService;
import com.ebim.tms.iam.application.UserInviteRequest;
import com.ebim.tms.iam.application.UserProfileRequest;
import com.ebim.tms.iam.application.UserRolesRequest;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * Who may act in the selected company, and with which roles.
 *
 * <p>The path variable is a <b>membership</b> id, not a user id, everywhere below. That is the
 * resource an administrator of one company owns: the same person may hold memberships in several
 * companies, and a URL keyed on their global id would mean different things depending on which
 * {@code X-Company-Id} accompanied it.
 *
 * <p>Two permissions, drawn where V3 drew them. Reading and correcting the person is
 * {@code iam.user:*}; granting, changing and revoking what they may do is {@code iam.membership:*}.
 * An installation can therefore let a supervisor keep the directory tidy without letting them hand
 * out authority, which is the whole reason those are two permissions and not one.
 *
 * <p>There is no delete. A membership is revoked and a profile is deactivated, never removed: every
 * order and shipment those people created points at them through {@code created_by} with
 * {@code ON DELETE RESTRICT}, so removal would either fail or erase history (V2).
 */
@RestController
@RequestMapping("${tms.api.base-path}/admin/users")
@Tag(name = "User administration", description = "Access to the selected company: people, roles and revocation")
public class UserAdministrationController {

    private final UserAdministrationService userAdministrationService;

    public UserAdministrationController(UserAdministrationService userAdministrationService) {
        this.userAdministrationService = userAdministrationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('iam.user:read')")
    @Operation(summary = "List the people who can act in this company, with the roles they hold")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<AdministeredUserView> list(
            CompanyScope scope, @ModelAttribute AdministeredUserFilter filter, @ModelAttribute PageQuery pageQuery) {
        return userAdministrationService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{membershipId}")
    @PreAuthorize("hasAuthority('iam.user:read')")
    @Operation(summary = "One person's access to this company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView get(CompanyScope scope, @PathVariable UUID membershipId) {
        return userAdministrationService.get(scope, membershipId);
    }

    /**
     * Guarded by {@code iam.membership:manage} rather than {@code iam.user:manage}: the act is
     * granting access, and it only incidentally creates a profile when the email is new to the
     * installation. A role that may tidy the directory must not be able to let somebody in.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('iam.membership:manage')")
    @Operation(summary = "Give one person access to this company, creating their profile if the email is new")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView invite(CompanyScope scope, @Valid @RequestBody UserInviteRequest request) {
        return userAdministrationService.invite(scope, request);
    }

    @PutMapping("/{membershipId}")
    @PreAuthorize("hasAuthority('iam.user:manage')")
    @Operation(summary = "Correct the display name of somebody who works in this company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView updateProfile(
            CompanyScope scope, @PathVariable UUID membershipId, @Valid @RequestBody UserProfileRequest request) {
        return userAdministrationService.updateProfile(scope, membershipId, request);
    }

    @PutMapping("/{membershipId}/roles")
    @PreAuthorize("hasAuthority('iam.membership:manage')")
    @Operation(summary = "Replace the roles this person holds in this company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView changeRoles(
            CompanyScope scope, @PathVariable UUID membershipId, @Valid @RequestBody UserRolesRequest request) {
        return userAdministrationService.changeRoles(scope, membershipId, request);
    }

    /**
     * Named {@code revoke} and not {@code deactivate}, because what it switches off is the
     * membership and not the person: the account keeps working everywhere else it has access.
     */
    @PostMapping("/{membershipId}/revoke")
    @PreAuthorize("hasAuthority('iam.membership:manage')")
    @Operation(summary = "End this person's access to this company; their history and account are untouched")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView revoke(CompanyScope scope, @PathVariable UUID membershipId) {
        return userAdministrationService.revoke(scope, membershipId);
    }

    @PostMapping("/{membershipId}/restore")
    @PreAuthorize("hasAuthority('iam.membership:manage')")
    @Operation(summary = "Let this person back into this company with the roles they already held")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AdministeredUserView restore(CompanyScope scope, @PathVariable UUID membershipId) {
        return userAdministrationService.restore(scope, membershipId);
    }

    /**
     * The role catalogue. Guarded by {@code iam.membership:read} and not by a permission of its own:
     * it is the vocabulary of the screen above, it is identical in every installation, and it is
     * already published in {@code docs/security/AUTHORIZATION_MODEL.md}.
     *
     * <p>The {@link CompanyScope} parameter is unused by the service and is not decoration: it is
     * what makes this endpoint company-scoped at all. Without it the resolver would not require
     * {@code X-Company-Id}, and {@code @PreAuthorize} would have no company whose permissions to
     * evaluate the authority against.
     */
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('iam.membership:read')")
    @Operation(summary = "The roles that can be granted, and what each of them carries")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<RoleView> roles(CompanyScope scope) {
        return userAdministrationService.roles();
    }
}
