package com.ebim.tms.shared.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated caller as TMS knows them: a business profile plus the company scopes
 * their active memberships allow.
 *
 * <p>Built once per request from the validated JWT's {@code sub} and never from anything the
 * client sends. The snapshot is immutable for the duration of the request, so authorization
 * decisions taken in a controller, a service and a repository all see the same facts.
 *
 * @param appUserId  {@code tms.app_user.id} - the actor recorded in audit columns
 * @param authUserId {@code auth.users.id} from the token's {@code sub} claim
 * @param companies  every active, accessible company; empty for a provisioned user who has
 *     not been given a membership yet, which is a legitimate state and not an error
 */
public record TmsPrincipal(
        UUID appUserId,
        UUID authUserId,
        String email,
        String fullName,
        List<CompanyScope> companies) {

    public TmsPrincipal {
        companies = List.copyOf(companies);
    }

    /**
     * The scope for a requested company, or empty when the caller holds no active membership
     * in it. Empty is the answer both for "that company belongs to someone else" and for "no
     * such company": the API must not tell an outsider which companies exist.
     */
    public Optional<CompanyScope> companyScope(UUID companyId) {
        return companies.stream().filter(scope -> scope.companyId().equals(companyId)).findFirst();
    }
}
