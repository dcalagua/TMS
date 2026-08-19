package com.ebim.tms.iam.application;

import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.security.TmsPrincipal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Projects the resolved principal into the "who am I and what may I do" view.
 *
 * <p>No repository call: the principal already carries the authoritative snapshot resolved for
 * this request. Querying again could answer with facts different from the ones the request is
 * being authorized against, which is exactly the kind of race a snapshot is meant to remove.
 */
@Service
public class MeService {

    public MeView describe(TmsPrincipal principal) {
        List<CompanyAccessView> companies = principal.companies().stream()
                .sorted(Comparator.comparing(CompanyScope::organizationCode)
                        .thenComparing(CompanyScope::companyCode))
                .map(CompanyAccessViews::from)
                .toList();

        return new MeView(
                new UserView(principal.appUserId(), principal.email(), principal.fullName()),
                companies);
    }
}
