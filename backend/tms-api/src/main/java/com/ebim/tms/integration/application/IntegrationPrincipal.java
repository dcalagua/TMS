package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationScope;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated machine caller: which credential it is, which company it is permanently bound
 * to, and what it may do.
 *
 * <p>The counterpart of {@code TmsPrincipal}, with one structural difference that is the entire
 * tenancy argument for this API: {@code TmsPrincipal} carries a <em>list</em> of companies and a
 * header selects one; this carries exactly one, resolved from the credential. There is nothing to
 * select and therefore nothing to get wrong.
 *
 * @param id       {@code tms.integration_client.id}, recorded on every inbox row
 * @param clientId the public identifier, safe to log
 */
public record IntegrationPrincipal(
        UUID id,
        String clientId,
        String name,
        CompanyScope companyScope,
        Set<IntegrationScope> scopes) {

    public IntegrationPrincipal {
        scopes = Set.copyOf(scopes);
    }

    public UUID companyId() {
        return companyScope.companyId();
    }

    public boolean has(IntegrationScope scope) {
        return scopes.contains(scope);
    }

    /** The label the audit context and the log lines use; never secret material. */
    public String actorLabel() {
        return "integration-client:" + clientId;
    }
}
