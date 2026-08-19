package com.ebim.tms.shared.audit;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TmsAuthenticationToken;
import com.ebim.tms.shared.security.TmsPrincipal;
import com.ebim.tms.shared.web.CorrelationId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Supplies the {@link AuditActor} of the current request to services that need to stamp a
 * change.
 *
 * <p>A bean rather than a static helper so a use case can declare the dependency and a test
 * can substitute it. It reads the {@code SecurityContext} instead of taking the actor as a
 * parameter, which keeps the actor out of service signatures - and therefore out of reach of
 * a caller who might want to choose a different one.
 */
@Component
public class AuditActorProvider {

    public Optional<AuditActor> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof TmsAuthenticationToken token)) {
            return Optional.empty();
        }

        TmsPrincipal principal = token.getPrincipal();
        Optional<CompanyScope> scope = token.companyScope();
        return Optional.of(new AuditActor(
                principal.appUserId(),
                principal.email(),
                scope.map(CompanyScope::companyId).orElse(null),
                scope.map(CompanyScope::organizationId).orElse(null),
                CorrelationId.current().orElse(null)));
    }

    /**
     * The acting user id, for a write that must record one.
     *
     * @throws IllegalStateException when there is no authenticated actor - a background job
     *     that legitimately has none must supply its own actor explicitly rather than write
     *     rows with a null {@code created_by}
     */
    public UUID requireAppUserId() {
        return current().map(AuditActor::appUserId).orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: this operation must run inside an authenticated request"));
    }
}
