package com.ebim.tms.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a validated Supabase identity into the TMS principal.
 *
 * <p>Declared in {@code shared} and implemented by the {@code iam} module. The dependency
 * therefore points {@code iam -> shared}, which is the direction the module rules require:
 * shared infrastructure never depends on a business module, and identity resolution stays a
 * business concern with a real repository behind it rather than leaking SQL into the filter
 * chain.
 */
public interface PrincipalLoader {

    /**
     * @param authUserId the {@code sub} claim of a JWT that has already passed signature,
     *     issuer, audience and expiry validation
     * @return the principal, or empty when no <em>active</em> {@code app_user} carries that
     *     {@code auth_user_id}. Empty means "authenticated by Supabase, not provisioned in
     *     TMS" and is answered with 403, not 401: another sign-in will not change it.
     */
    Optional<TmsPrincipal> loadByAuthUserId(UUID authUserId);
}
