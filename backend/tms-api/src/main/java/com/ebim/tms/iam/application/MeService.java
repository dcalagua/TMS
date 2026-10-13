package com.ebim.tms.iam.application;

import com.ebim.tms.iam.lifecycle.application.TenantLifecycleService;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Projects the resolved principal into the "who am I and what may I do" view.
 *
 * <p>No repository call for access: the principal already carries the authoritative snapshot
 * resolved for this request. Querying again could answer with facts different from the ones the
 * request is being authorized against, which is exactly the kind of race a snapshot is meant to
 * remove. The one lookup is a carrier user's carrier <em>name</em> (ADR-018), a label for the
 * screen that decides nothing - and the operational state of each organization (ADR-021), which is
 * the input of the "Cuenta suspendida" banner and decides nothing either.
 */
@Service
public class MeService {

    /** Optional: the label is a convenience, and a context without fleet still answers {@code /me}. */
    private final ObjectProvider<CarrierLookupPort> carriers;
    /** Optional too: a context without iam.lifecycle reports every organization ACTIVE. */
    private final ObjectProvider<TenantLifecycleService> lifecycle;

    public MeService(ObjectProvider<CarrierLookupPort> carriers, ObjectProvider<TenantLifecycleService> lifecycle) {
        this.carriers = carriers;
        this.lifecycle = lifecycle;
    }

    public MeView describe(TmsPrincipal principal) {
        Map<UUID, OperationalStatusView> states = OperationalStatuses.of(lifecycle.getIfAvailable(),
                principal.companies().stream().map(CompanyScope::organizationId).collect(Collectors.toSet()));
        List<CompanyAccessView> companies = principal.companies().stream()
                .sorted(Comparator.comparing(CompanyScope::organizationCode)
                        .thenComparing(CompanyScope::companyCode))
                .map(scope -> CompanyAccessViews.from(scope, carriers.getIfAvailable(),
                        states.get(scope.organizationId())))
                .toList();

        return new MeView(
                new UserView(principal.appUserId(), principal.email(), principal.fullName()),
                companies);
    }
}
