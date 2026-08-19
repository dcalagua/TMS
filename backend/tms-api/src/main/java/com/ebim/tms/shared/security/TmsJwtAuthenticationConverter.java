package com.ebim.tms.shared.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Turns a validated Supabase JWT into a TMS authentication.
 *
 * <p>This is the single place where an external identity becomes an internal one:
 *
 * <pre>
 *   JWT.sub  ->  tms.app_user.auth_user_id  ->  active memberships  ->  company scopes
 * </pre>
 *
 * <p>Nothing about the caller's identity, tenancy or permissions is read from the token
 * beyond {@code sub}. Supabase custom claims (for example {@code app_metadata.role}) are
 * ignored on purpose: they are set outside this application's control, and trusting them
 * would move authorization out of the backend.
 */
@Component
public class TmsJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(TmsJwtAuthenticationConverter.class);

    private final PrincipalLoader principalLoader;

    public TmsJwtAuthenticationConverter(PrincipalLoader principalLoader) {
        this.principalLoader = principalLoader;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID authUserId = parseSubject(jwt.getSubject());

        TmsPrincipal principal = principalLoader.loadByAuthUserId(authUserId).orElseThrow(() -> {
            // The auth user id is not a secret and is the only way to find the account in
            // Supabase Studio; the token itself is never logged.
            log.warn("Rejected a valid token for auth user {}: no active tms.app_user is mapped to it", authUserId);
            return new UnprovisionedPrincipalException("no active TMS profile is mapped to this identity");
        });

        if (log.isDebugEnabled()) {
            log.debug("Authenticated app_user {} with access to {} company scope(s)",
                    principal.appUserId(), principal.companies().size());
        }
        return TmsAuthenticationToken.authenticated(jwt, principal);
    }

    private static UUID parseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("the token carries no subject");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            // Supabase subjects are UUIDs. Anything else means the token was issued by
            // something that is not the configured Supabase project.
            throw new InvalidBearerTokenException("the token subject is not a Supabase user id");
        }
    }
}
