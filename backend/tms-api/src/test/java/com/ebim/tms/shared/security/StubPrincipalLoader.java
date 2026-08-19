package com.ebim.tms.shared.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link PrincipalLoader} for web-layer tests.
 *
 * <p>Lets a test decide exactly what the identity resolution returns - a principal, or nothing
 * for an unknown or deactivated account - without a database. The real SQL behind the port is
 * covered separately by {@code JdbcIdentityRepositoryIntegrationTest} against a disposable
 * PostgreSQL, so neither half is left unverified.
 */
public class StubPrincipalLoader implements PrincipalLoader {

    private final Map<UUID, TmsPrincipal> principals = new LinkedHashMap<>();

    public StubPrincipalLoader register(TmsPrincipal principal) {
        principals.put(principal.authUserId(), principal);
        return this;
    }

    @Override
    public Optional<TmsPrincipal> loadByAuthUserId(UUID authUserId) {
        return Optional.ofNullable(principals.get(authUserId));
    }
}
