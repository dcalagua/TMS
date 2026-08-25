package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.CompanyProfileRow;
import com.ebim.tms.iam.infrastructure.CompanyAdministrationRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettings;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant administration for one company: read its profile, change it, and create a sibling company
 * inside the same organization.
 *
 * <p>Takes a {@link CompanyScope} and never a company id, like every company-scoped use case in
 * TMS. That is what makes "a company administrator cannot administer another company" a property of
 * the type system rather than a check somebody has to remember: the scope is resolved from the
 * caller's own active memberships before this class is reached, so {@code iam.company:manage} held
 * in one company grants nothing in another.
 *
 * <p>{@link #create} is the single exception and is guarded explicitly - see its comment.
 */
@Service
public class CompanyAdministrationService {

    private final CompanyAdministrationRepository repository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public CompanyAdministrationService(CompanyAdministrationRepository repository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public CompanyProfileView current(CompanyScope scope) {
        return view(scope, load(scope.companyId()));
    }

    /**
     * Saves the profile and the settings together, because they are one screen and a partial save
     * would leave an administrator unable to tell which half took.
     *
     * <p>The settings row is written with an upsert: a company created after migration V34 has no
     * row until this runs, for the tenant-policy reason V34 section 4 explains.
     */
    @Transactional
    public CompanyProfileView update(CompanyScope scope, CompanyProfileRequest request) {
        CompanyProfileRow before = load(scope.companyId());
        UUID actorId = auditActorProvider.requireAppUserId();

        String name = request.name().trim();
        String taxIdentifier = blankToNull(request.taxIdentifier());
        String timeZone = requireKnownZone(request.timeZone());
        CompanySettings settings = new CompanySettings(
                upper(request.defaultCountry()),
                upper(request.orderNumberPrefix()),
                upper(request.shipmentNumberPrefix()));

        if (repository.updateCompany(scope.companyId(), name, taxIdentifier, timeZone, actorId) == 0) {
            // The row was there a statement ago, so this is a company deactivated or removed
            // underneath the request rather than a caller reaching somewhere they should not.
            throw new ResourceNotFoundException("Company not found.");
        }
        repository.saveSettings(scope.companyId(), settings, actorId);

        auditRecorder.record(scope, AuditAggregateType.COMPANY, scope.companyId(), AuditAction.UPDATE,
                changes(before, name, timeZone, settings));
        return view(scope, load(scope.companyId()));
    }

    /**
     * Creates a company in the organization the caller is signed into.
     *
     * <p>This is the one write in TMS that produces a tenant the caller has no membership in yet,
     * so it cannot be protected the way everything else is - there is no scope to resolve for a
     * company that does not exist. Two conditions therefore have to hold, and both are checked
     * here rather than in a {@code @PreAuthorize} expression, because only one of them is a
     * permission:
     *
     * <ol>
     *   <li>{@code iam.company:manage} in the company the request is scoped to (the controller);</li>
     *   <li>an active organization-wide membership in that organization (this method). A
     *       COMPANY_ADMIN holds the permission and is by definition the administrator of one
     *       company; an ORGANIZATION_ADMIN's membership carries {@code company_id IS NULL} and is
     *       the authority that actually reaches the level being changed.</li>
     * </ol>
     *
     * <p>Nothing grants access to the new company afterwards, and nothing needs to: an
     * organization-wide membership expands to every company of its organization
     * ({@code JdbcIdentityRepository.COMPANY_PERMISSIONS_SQL}), so the creator can select it on
     * their next {@code /me}. That is also why creating one from a company-scoped membership is
     * refused rather than silently followed by a membership insert - a company nobody can see is
     * worse than a request that says no.
     */
    @Transactional
    public CompanyProfileView create(CompanyScope scope, CompanyCreateRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        if (!repository.hasOrganizationWideMembership(actorId, scope.organizationId())) {
            // AccessDeniedException and not InvalidRequestException: this is an authorization
            // outcome, and it belongs with the 403 the permission check would have produced rather
            // than among the 400s that mean "fix your payload". The response prose is the generic
            // one ApiExceptionHandler writes for every denial, on purpose - the caller learns they
            // may not, not which shape of membership would have let them.
            throw new AccessDeniedException("organization-wide membership required to create a company");
        }

        String code = upper(request.code());
        String name = request.name().trim();
        String timeZone = requireKnownZone(request.timeZone());
        if (repository.companyCodeTaken(scope.organizationId(), code)) {
            throw duplicateCode(code, scope.organizationCode());
        }

        UUID companyId;
        try {
            companyId = repository.insertCompany(scope.organizationId(), code, name,
                    blankToNull(request.taxIdentifier()), timeZone, actorId);
        } catch (DataIntegrityViolationException raced) {
            // uq_company_organization_code, lost between the check above and this insert.
            throw duplicateCode(code, scope.organizationCode());
        }

        // Audited against the *creating* company's scope, not the new one. The audit trail is
        // company-scoped (V22), and an event stamped with a company that did not exist when the act
        // happened would be unreadable from either side; the new company's id and code travel in
        // the metadata, which is where "what did this act produce" belongs.
        auditRecorder.record(scope, AuditAggregateType.COMPANY, companyId, AuditAction.CREATE,
                Map.of("code", code, "name", name, "organizationCode", scope.organizationCode()));

        // Read back through the same path the settings screen uses, so the caller gets the
        // defaults the product will actually apply rather than an echo of what they sent.
        // canCreateCompany is true by construction here - the check above just passed.
        return CompanyProfileView.from(load(companyId), true);
    }

    private CompanyProfileRow load(UUID companyId) {
        return repository.findProfile(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found."));
    }

    /**
     * Adds the one flag the row cannot carry: whether <em>this</em> caller may add a company to the
     * organization. It is a property of the pair (person, organization), not of the company, which
     * is why it is resolved here and not in the projection.
     */
    private CompanyProfileView view(CompanyScope scope, CompanyProfileRow row) {
        boolean canCreate = repository.hasOrganizationWideMembership(
                auditActorProvider.writerAppUserId(), scope.organizationId());
        return CompanyProfileView.from(row, canCreate);
    }

    /**
     * What actually changed, for the audit metadata. Only the fields that moved, and never the
     * whole row: an audit event that restates every value on every save buries the one edit
     * somebody is looking for. The tax identifier is deliberately absent - it is the one field here
     * that could be a person's national id in a single-owner company.
     */
    private static Map<String, Object> changes(
            CompanyProfileRow before, String name, String timeZone, CompanySettings after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!before.name().equals(name)) {
            changes.put("name", name);
        }
        if (!before.timeZone().equals(timeZone)) {
            changes.put("timeZone", timeZone);
        }
        if (!before.settings().defaultCountry().equals(after.defaultCountry())) {
            changes.put("defaultCountry", after.defaultCountry());
        }
        if (!before.settings().orderNumberPrefix().equals(after.orderNumberPrefix())) {
            changes.put("orderNumberPrefix", after.orderNumberPrefix());
        }
        if (!before.settings().shipmentNumberPrefix().equals(after.shipmentNumberPrefix())) {
            changes.put("shipmentNumberPrefix", after.shipmentNumberPrefix());
        }
        return changes;
    }

    /**
     * The zone check V2 left to Java. Rejected rather than defaulted: {@link CompanyScope#zoneId()}
     * falls back to the server's zone for an unrecognised value, which is the right behaviour for a
     * read that must not fail - and exactly the wrong behaviour here, where accepting the typo is
     * what would silently move a whole tenant's operating day.
     */
    private static String requireKnownZone(String timeZone) {
        String trimmed = timeZone.trim();
        try {
            ZoneId.of(trimmed);
        } catch (DateTimeException unknown) {
            throw new InvalidRequestException(
                    "'" + trimmed + "' is not a known time zone. Use an IANA id such as America/Lima.");
        }
        return trimmed;
    }

    private static ConflictException duplicateCode(String code, String organizationCode) {
        return new ConflictException(
                "A company with code '" + code + "' already exists in " + organizationCode + ".");
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
