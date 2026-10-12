package com.ebim.tms.iam.application;

import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Projects the resolved principal into the "who am I and what may I do" view.
 *
 * <p>No repository call for access: the principal already carries the authoritative snapshot
 * resolved for this request. Querying again could answer with facts different from the ones the
 * request is being authorized against, which is exactly the kind of race a snapshot is meant to
 * remove. The one lookup is a carrier user's carrier <em>name</em> (ADR-018), a label for the
 * screen that decides nothing.
 */
@Service
public class MeService {

    /** Optional: the label is a convenience, and a context without fleet still answers {@code /me}. */
    private final ObjectProvider<CarrierLookupPort> carriers;

    public MeService(ObjectProvider<CarrierLookupPort> carriers) {
        this.carriers = carriers;
    }

    public MeView describe(TmsPrincipal principal) {
        List<CompanyAccessView> companies = principal.companies().stream()
                .sorted(Comparator.comparing(CompanyScope::organizationCode)
                        .thenComparing(CompanyScope::companyCode))
                .map(scope -> CompanyAccessViews.from(scope, carriers.getIfAvailable()))
                .toList();

        return new MeView(
                new UserView(principal.appUserId(), principal.email(), principal.fullName()),
                companies);
    }
}
