package com.ebim.tms.iam.application;

import com.ebim.tms.iam.domain.AppUserProfile;
import com.ebim.tms.iam.domain.CompanyPermissionRow;
import com.ebim.tms.iam.infrastructure.IdentityRepository;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.Permission;
import com.ebim.tms.shared.security.PrincipalLoader;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a validated Supabase identity into the request's principal and company scopes.
 *
 * <p>This is the use case behind every authenticated request. It is the {@code iam} module's
 * implementation of the {@link PrincipalLoader} port declared in {@code shared}, which is what
 * lets the security filter chain stay free of business queries while the business rules stay
 * in a module with a repository behind it.
 *
 * <p>The whole resolution is one read-only transaction, so a membership change committed
 * halfway through cannot produce a principal that holds a company in one half and not in the
 * other.
 */
@Service
public class PrincipalResolutionService implements PrincipalLoader {

    private static final Logger log = LoggerFactory.getLogger(PrincipalResolutionService.class);

    private final IdentityRepository identityRepository;

    public PrincipalResolutionService(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TmsPrincipal> loadByAuthUserId(UUID authUserId) {
        Optional<AppUserProfile> profile = identityRepository.findActiveProfileByAuthUserId(authUserId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }

        AppUserProfile user = profile.get();
        List<CompanyScope> scopes = toCompanyScopes(identityRepository.findCompanyPermissions(user.id()));
        return Optional.of(new TmsPrincipal(user.id(), user.authUserId(), user.email(), user.fullName(), scopes));
    }

    /**
     * Groups the flat (company, permission) rows into one scope per company, unioning the
     * permissions that reach that company from every applicable membership.
     */
    private static List<CompanyScope> toCompanyScopes(List<CompanyPermissionRow> rows) {
        Map<UUID, CompanyPermissionRow> companies = new LinkedHashMap<>();
        Map<UUID, Set<Permission>> permissions = new LinkedHashMap<>();

        for (CompanyPermissionRow row : rows) {
            companies.putIfAbsent(row.companyId(), row);
            Permission permission = Permission.fromCode(row.permissionCode()).orElse(null);
            if (permission == null) {
                // The database holds a permission this build does not know: normal during a
                // rolling deploy of a new module. It is ignored, never granted.
                log.warn("Ignoring unknown permission code '{}' held in company {}",
                        row.permissionCode(), row.companyId());
                continue;
            }
            permissions.computeIfAbsent(row.companyId(), key -> EnumSet.noneOf(Permission.class)).add(permission);
        }

        List<CompanyScope> scopes = new ArrayList<>(companies.size());
        companies.forEach((companyId, row) -> scopes.add(new CompanyScope(
                companyId,
                row.companyCode(),
                row.companyName(),
                row.timeZone(),
                row.organizationId(),
                row.organizationCode(),
                row.organizationName(),
                permissions.getOrDefault(companyId, Set.of()))));
        return List.copyOf(scopes);
    }
}
