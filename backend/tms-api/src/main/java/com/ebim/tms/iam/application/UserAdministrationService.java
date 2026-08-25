package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.AdministeredUserRow;
import com.ebim.tms.iam.infrastructure.CompanyAdministrationRepository;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository.ExistingAppUser;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository.ExistingMembership;
import com.ebim.tms.iam.infrastructure.UserAdministrationRepository.RoleReference;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may act in this company, and with which roles.
 *
 * <p>Takes a {@link CompanyScope}, so "a company administrator cannot administer another company"
 * is structural rather than checked. Four further rules are enforced here and are worth stating
 * together, because each of them is a way a multi-tenant administration screen goes wrong:
 *
 * <ol>
 *   <li><b>An organization-wide membership is read-only from a company screen.</b> Its
 *       {@code company_id} is NULL, so revoking it from company A would remove that person from
 *       companies B and C as well. It is listed - hiding it would make "who can act here" false -
 *       and every write against one is refused. The repository's {@code company_id = :companyId}
 *       predicate refuses it a second time at the statement level.</li>
 *   <li><b>Access is revoked at the membership, never at the profile.</b> {@code tms.app_user.active}
 *       is installation-wide: switching it off from here would lock the person out of every
 *       organization they work for, including ones this caller has never heard of. So
 *       {@link #revoke} deactivates {@code tms.membership} and the profile flag is read-only.</li>
 *   <li><b>Nobody edits their own access.</b> An administrator cannot revoke their own membership
 *       or change their own roles. The guard is deliberately blunt - "not yourself", rather than
 *       "not if it would leave you unable to administer" - because the clever version has to reason
 *       about organization-wide memberships that also apply to this company, and a subtly wrong
 *       clever version locks a customer out of their own tenant with no way back through the UI.
 *       The cost is that a sole administrator must invite a second one before demoting themselves,
 *       which is the correct order to do that in anyway.</li>
 *   <li><b>A role that would grant nothing is refused, not saved.</b> An {@code ORGANIZATION}-scoped
 *       role attached to a company-scoped membership is discarded by
 *       {@code JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL} - the pairing the database cannot
 *       express and V2 handed over as "a Java rule". Saving it would show an administrator a role
 *       the person visibly holds and invisibly does not.</li>
 * </ol>
 */
@Service
public class UserAdministrationService {

    private final UserAdministrationRepository repository;
    /**
     * For one question only: does this person already reach this company through an
     * organization-wide membership. It lives on the company repository because company creation asks
     * it first (see that statement's comment), and asking it from two services is better than a
     * second copy of the SQL that decides who is an organization administrator.
     */
    private final CompanyAdministrationRepository companyRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public UserAdministrationService(UserAdministrationRepository repository,
            CompanyAdministrationRepository companyRepository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        return repository.findRoleCatalogue().stream().map(RoleView::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AdministeredUserView> list(
            CompanyScope scope, AdministeredUserFilter filter, PageQuery pageQuery) {
        String search = searchTerm(filter.search());
        List<AdministeredUserRow> rows = repository.listUsers(
                scope.organizationId(), scope.companyId(), search, filter.active(), pageQuery);
        long total = repository.countUsers(scope.organizationId(), scope.companyId(), search, filter.active());

        // One batched role lookup for the whole page - the discipline every list in TMS follows,
        // and the one that matters most here because the alternative is a query per person.
        Map<UUID, List<String>> rolesByMembership =
                repository.roleCodesOf(rows.stream().map(AdministeredUserRow::membershipId).toList());
        List<AdministeredUserView> content = rows.stream()
                .map(row -> AdministeredUserView.from(
                        row, rolesByMembership.getOrDefault(row.membershipId(), List.of())))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), total);
    }

    @Transactional(readOnly = true)
    public AdministeredUserView get(CompanyScope scope, UUID membershipId) {
        return toView(find(scope, membershipId));
    }

    /**
     * Gives one person access to this company.
     *
     * <p>Three outcomes, and the reason there are three is that {@code tms.app_user} is global while
     * a membership is not:
     *
     * <ul>
     *   <li>the email is unknown: a profile is created, then a membership;</li>
     *   <li>the email belongs to somebody who already works elsewhere in the installation: the
     *       existing profile is reused and only a membership is created. The name in the request is
     *       ignored, because that profile is theirs and an administrator of one company does not get
     *       to rename a person on the strength of knowing their address;</li>
     *   <li>the email belongs to somebody whose access here was revoked: the membership is
     *       reactivated with the roles now chosen, rather than a second row being attempted -
     *       {@code uq_membership_user_organization_company} forbids that, and the honest reading of
     *       "invite somebody who used to work here" is "let them back in".</li>
     * </ul>
     */
    @Transactional
    public AdministeredUserView invite(CompanyScope scope, UserInviteRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Set<UUID> roleIds = resolveAssignableRoles(request.roleCodes());
        List<String> roleCodes = normalizedCodes(request.roleCodes());

        UUID appUserId = resolveOrCreateProfile(email, request.fullName().trim(), actorId);
        if (companyRepository.hasOrganizationWideMembership(appUserId, scope.organizationId())) {
            // They already reach this company, and every other company of the organization, through
            // a membership this screen may not touch. A company-scoped row beside it is legal - the
            // two unique indexes are partial and do not collide - and it would be a second grant
            // nobody can reason about: the permissions of both would union, and revoking the one
            // visible here would appear to do nothing.
            throw new ConflictException("'" + email + "' already has an organization-wide role in "
                    + scope.organizationCode() + ", which reaches this company.");
        }
        Optional<ExistingMembership> existing =
                repository.findCompanyMembership(appUserId, scope.organizationId(), scope.companyId());

        UUID membershipId;
        AuditAction action;
        if (existing.isPresent()) {
            ExistingMembership membership = existing.get();
            if (membership.active()) {
                throw new ConflictException("'" + email + "' already has access to this company.");
            }
            repository.setMembershipActive(membership.id(), scope.companyId(), true, actorId);
            membershipId = membership.id();
            action = AuditAction.ACTIVATE;
        } else {
            try {
                membershipId = repository.insertMembership(
                        appUserId, scope.organizationId(), scope.companyId(), actorId);
            } catch (DataIntegrityViolationException raced) {
                // uq_membership_user_organization_company, lost between the read above and here.
                throw new ConflictException("'" + email + "' already has access to this company.");
            }
            action = AuditAction.CREATE;
        }
        repository.replaceMembershipRoles(membershipId, roleIds, actorId);

        auditRecorder.record(scope, AuditAggregateType.MEMBERSHIP, membershipId, action,
                Map.of("email", email, "roles", roleCodes));
        return toView(find(scope, membershipId));
    }

    /** Corrects the display name. See {@link UserProfileRequest} for what is deliberately not here. */
    @Transactional
    public AdministeredUserView updateProfile(CompanyScope scope, UUID membershipId, UserProfileRequest request) {
        AdministeredUserRow row = requireCompanyScoped(find(scope, membershipId));
        UUID actorId = auditActorProvider.requireAppUserId();
        String fullName = request.fullName().trim();

        if (!fullName.equals(row.fullName())) {
            repository.updateAppUserName(row.appUserId(), fullName, actorId);
            // Against APP_USER and not MEMBERSHIP: the row that changed is the global profile, and
            // an event filed under the membership would suggest the change stopped at this company.
            auditRecorder.record(scope, AuditAggregateType.APP_USER, row.appUserId(), AuditAction.UPDATE,
                    Map.of("email", row.email(), "fullName", fullName));
        }
        return toView(find(scope, membershipId));
    }

    /** Replaces the roles held in this company. Refused for yourself and for an organization-wide row. */
    @Transactional
    public AdministeredUserView changeRoles(CompanyScope scope, UUID membershipId, UserRolesRequest request) {
        AdministeredUserRow row = requireCompanyScoped(find(scope, membershipId));
        UUID actorId = auditActorProvider.requireAppUserId();
        requireNotSelf(row, actorId, "change your own roles");

        Set<UUID> roleIds = resolveAssignableRoles(request.roleCodes());
        List<String> before = repository.roleCodesOf(membershipId);
        repository.replaceMembershipRoles(membershipId, roleIds, actorId);
        List<String> after = normalizedCodes(request.roleCodes());

        // Before and after, both, because that is the whole value of the event: "PLANNER -> VIEWER"
        // answers the question, "VIEWER" alone only says where it ended up.
        auditRecorder.record(scope, AuditAggregateType.MEMBERSHIP, membershipId, AuditAction.ROLES_CHANGED,
                Map.of("email", row.email(), "rolesBefore", before, "rolesAfter", after));
        return toView(find(scope, membershipId));
    }

    /**
     * Ends this person's access to this company.
     *
     * <p>The membership is deactivated and nothing is deleted, which is V2's rule for every
     * long-lived row and matters more here than anywhere: the orders they created and the trips they
     * dispatched carry their id in {@code created_by}, and those columns are {@code ON DELETE
     * RESTRICT} precisely so that history cannot be erased by an administrative act.
     */
    @Transactional
    public AdministeredUserView revoke(CompanyScope scope, UUID membershipId) {
        return setAccess(scope, membershipId, false, "revoke your own access");
    }

    /** Lets somebody back in, with the roles their membership already carried. */
    @Transactional
    public AdministeredUserView restore(CompanyScope scope, UUID membershipId) {
        return setAccess(scope, membershipId, true, "restore your own access");
    }

    private AdministeredUserView setAccess(CompanyScope scope, UUID membershipId, boolean active, String selfAction) {
        AdministeredUserRow row = requireCompanyScoped(find(scope, membershipId));
        UUID actorId = auditActorProvider.requireAppUserId();
        requireNotSelf(row, actorId, selfAction);

        if (row.membershipActive() != active) {
            repository.setMembershipActive(membershipId, scope.companyId(), active, actorId);
            auditRecorder.record(scope, AuditAggregateType.MEMBERSHIP, membershipId,
                    active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, Map.of("email", row.email()));
        }
        return toView(find(scope, membershipId));
    }

    private AdministeredUserView toView(AdministeredUserRow row) {
        return AdministeredUserView.from(row, repository.roleCodesOf(row.membershipId()));
    }

    /**
     * A membership that reaches this company, or a 404. Not "a membership with this id": the
     * repository pins the organization and the company, so an id belonging to another tenant is
     * indistinguishable from one that does not exist - which is the answer a caller probing for
     * other customers' ids should get.
     */
    private AdministeredUserRow find(CompanyScope scope, UUID membershipId) {
        return repository.findUser(scope.organizationId(), scope.companyId(), membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found in this company."));
    }

    /** Rule 1 of the class comment. */
    private static AdministeredUserRow requireCompanyScoped(AdministeredUserRow row) {
        if (row.organizationWide()) {
            throw new ConflictException("'" + row.email() + "' holds an organization-wide role. "
                    + "It reaches every company of the organization and cannot be changed from a company screen.");
        }
        return row;
    }

    /** Rule 3 of the class comment. */
    private static void requireNotSelf(AdministeredUserRow row, UUID actorId, String action) {
        if (row.appUserId().equals(actorId)) {
            throw new ConflictException(
                    "You cannot " + action + ". Ask another administrator of this company to do it.");
        }
    }

    /**
     * Resolves role codes to ids, refusing anything the catalogue does not hold and anything that
     * would grant nothing here (rule 4). Refusing rather than filtering is the point: an
     * administrator who mistypes a code has to be told, not quietly given a smaller grant than they
     * asked for.
     */
    private Set<UUID> resolveAssignableRoles(List<String> requestedCodes) {
        Set<String> codes = new LinkedHashSet<>(normalizedCodes(requestedCodes));
        Map<String, RoleReference> found = repository.findRolesByCode(codes);

        Set<UUID> roleIds = new LinkedHashSet<>();
        for (String code : codes) {
            RoleReference role = found.get(code);
            if (role == null) {
                throw new InvalidRequestException("'" + code + "' is not a role in this installation.");
            }
            if (!role.assignableToCompanyMembership()) {
                throw new InvalidRequestException("'" + code + "' is an organization-wide role and cannot be "
                        + "granted for a single company. It would grant nothing.");
            }
            roleIds.add(role.id());
        }
        return roleIds;
    }

    /**
     * Creates the profile, or reuses the one that already exists for this email.
     *
     * <p>A deactivated profile is refused rather than reactivated: {@code tms.app_user.active} is
     * installation-wide, and an administrator of one company turning it back on would restore that
     * person's access to every other organization they belong to at the same time. The sentence
     * says so, because "this person exists but you may not do this" is otherwise indistinguishable
     * from a bug.
     */
    private UUID resolveOrCreateProfile(String email, String fullName, UUID actorId) {
        Optional<ExistingAppUser> existing = repository.findAppUserByEmail(email);
        if (existing.isPresent()) {
            ExistingAppUser profile = existing.get();
            if (!profile.active()) {
                throw new ConflictException("The account for '" + email + "' is deactivated for the whole "
                        + "installation. An organization administrator has to restore it before it can be "
                        + "given access to a company.");
            }
            return profile.id();
        }
        try {
            return repository.insertAppUser(email, fullName, actorId);
        } catch (DataIntegrityViolationException raced) {
            // uq_app_user_email: two administrators inviting the same person at the same instant.
            // Reported rather than recovered from, deliberately. The tempting fix - re-read the row
            // that now exists and attach a membership to it - cannot work here: a constraint
            // violation aborts the PostgreSQL transaction, so every further statement in it fails
            // with "current transaction is aborted", and the recovery would surface as a 500 instead
            // of the conflict it actually is. Recovering would need a savepoint around the insert,
            // which is machinery for a race between two people typing the same address in the same
            // second. The retry succeeds, because by then the profile exists and the read path finds
            // it.
            throw new ConflictException("'" + email + "' was registered by somebody else at the same "
                    + "moment. Try the invitation again.");
        }
    }

    /** Codes are stored upper case ({@code ck_role_code_shape}), so they are compared upper case. */
    private static List<String> normalizedCodes(List<String> codes) {
        return codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** {@code null} when nothing was typed; otherwise lower-cased and wrapped for a {@code LIKE}. */
    private static String searchTerm(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
