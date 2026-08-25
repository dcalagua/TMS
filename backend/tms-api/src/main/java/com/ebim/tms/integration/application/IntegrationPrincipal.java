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
 * @param carrierId the carrier this credential answers for, or null for an ordinary partner
 *     credential (migration V31). Resolved from the credential exactly as {@code companyScope} is,
 *     and for exactly the same reason: it decides <em>whose</em> tenders the caller may see, so
 *     accepting it from a header or a payload would let one partner answer for another
 */
public record IntegrationPrincipal(
        UUID id,
        String clientId,
        String name,
        CompanyScope companyScope,
        UUID carrierId,
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

    /**
     * Whether this credential speaks for a carrier.
     *
     * <p>Asked separately from {@link #has(IntegrationScope)} because the two failures are
     * different and deserve different messages: a credential without the scope is not allowed to
     * answer tenders, and a credential with the scope but no carrier is misconfigured - somebody
     * granted the capability and forgot to say who it is for.
     */
    public boolean speaksForACarrier() {
        return carrierId != null;
    }

    /** The label the audit context and the log lines use; never secret material. */
    public String actorLabel() {
        return "integration-client:" + clientId;
    }
}
