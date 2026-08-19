package com.ebim.tms.shared.security;

import java.io.Serial;
import org.springframework.security.core.AuthenticationException;

/**
 * The bearer token is genuine, but no active {@code tms.app_user} carries its {@code sub}.
 *
 * <p>Separate from an invalid-token failure because the remedy is different: the caller
 * authenticated correctly and cannot fix this by signing in again - an administrator has to
 * create or reactivate the profile. {@link TmsAuthenticationEntryPoint} therefore answers 403
 * for this exception and 401 for every other authentication failure.
 */
public class UnprovisionedPrincipalException extends AuthenticationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnprovisionedPrincipalException(String message) {
        super(message);
    }
}
