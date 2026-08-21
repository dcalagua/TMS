package com.ebim.tms.shared.security;

import java.io.Serial;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * A machine-to-machine credential that was not accepted.
 *
 * <p>It exists so that the two authentication surfaces can fail in their own words. The generic
 * {@code AuthenticationException} handler answers a browser caller with "this endpoint requires a
 * Supabase access token in the Authorization header", which is correct advice there and actively
 * misleading here: a partner integration has no Supabase token, must never be given one, and
 * would waste a support cycle trying to obtain one. Handled separately, an inbound integration
 * request is told the truth - its <em>integration credential</em> was not accepted.
 *
 * <p>It extends {@link BadCredentialsException} rather than {@code AuthenticationException}
 * directly so that anything reasoning about "the credential did not verify" - Spring Security's
 * own machinery included - still classifies it correctly.
 *
 * <p>The message carried here is caller-facing and is deliberately the <b>same for every
 * rejection</b>: unknown client, wrong secret, revoked, inactive, scopeless, deactivated company.
 * Telling them apart would let an attacker enumerate credentials. Which one it actually was is
 * written to the server log under the request's correlation id, so an operator can still diagnose
 * a genuine misconfiguration.
 */
public class MachineCredentialException extends BadCredentialsException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MachineCredentialException(String message) {
        super(message);
    }
}
