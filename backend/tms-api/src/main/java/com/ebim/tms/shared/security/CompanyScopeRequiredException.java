package com.ebim.tms.shared.security;

import java.io.Serial;

/**
 * A company-scoped endpoint was called without the {@code X-Company-Id} header.
 *
 * <p>A client error (400), not an authorization failure: the caller may well be entitled to
 * the operation, they simply did not say in which company it should happen. Mapped by
 * {@code ApiExceptionHandler}.
 */
public class CompanyScopeRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CompanyScopeRequiredException(String message) {
        super(message);
    }
}
