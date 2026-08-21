package com.ebim.tms.shared.audit;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.MachineAuthentication;
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
        if (authentication instanceof TmsAuthenticationToken token) {
            TmsPrincipal principal = token.getPrincipal();
            Optional<CompanyScope> scope = token.companyScope();
            return Optional.of(AuditActor.person(
                    principal.appUserId(),
                    principal.email(),
                    scope.map(CompanyScope::companyId).orElse(null),
                    scope.map(CompanyScope::organizationId).orElse(null),
                    CorrelationId.current().orElse(null)));
        }
        if (authentication instanceof MachineAuthentication machine) {
            Optional<CompanyScope> scope = machine.companyScope();
            return Optional.of(AuditActor.machine(
                    machine.machineActorLabel(),
                    scope.map(CompanyScope::companyId).orElse(null),
                    scope.map(CompanyScope::organizationId).orElse(null),
                    CorrelationId.current().orElse(null)));
        }
        return Optional.empty();
    }

    /**
     * The acting user id, for a write that must record one.
     *
     * @throws IllegalStateException when there is no authenticated actor, or when the actor is a
     *     machine. A use case that calls this is declaring "only a person may do this"; a
     *     background job or an integration that legitimately has no {@code app_user} must use
     *     {@link #writerAppUserId()} instead of writing rows under someone else's identity
     */
    public UUID requireAppUserId() {
        AuditActor actor = current().orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: this operation must run inside an authenticated request"));
        if (actor.isMachine()) {
            throw new IllegalStateException(
                    "this operation is restricted to an interactive user: " + actor.machineActorLabel()
                            + " has no app_user to record");
        }
        return actor.appUserId();
    }

    /**
     * The value to stamp into {@code created_by} / {@code updated_by} for a use case that is
     * reachable both from the UI and from the inbound integration API.
     *
     * <p>It is the acting {@code app_user} for a person, and {@code null} for a machine - which
     * is what those columns are nullable for (V2: "created_by/updated_by only where a real actor
     * exists"). The alternative, provisioning an {@code app_user} row per partner credential,
     * would put a fake person in {@code tms.app_user} and make every membership and user-list
     * query step around it.
     *
     * <p>The machine's identity is not lost by this: it is recorded in {@code tms.integration_request}
     * together with the correlation id, which is a stronger record than a name in one column -
     * it also says which delivery, which payload and which outcome.
     *
     * @throws IllegalStateException when there is no authenticated actor at all, so a write can
     *     never silently land with a null actor because nobody was authenticated
     */
    public UUID writerAppUserId() {
        AuditActor actor = current().orElseThrow(() -> new IllegalStateException(
                "no authenticated actor: this operation must run inside an authenticated request"));
        return actor.appUserId();
    }
}
