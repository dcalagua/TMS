package com.ebim.tms.shared.security;

import java.io.Serial;

/** The {@code X-Company-Id} header is present but is not a UUID. A client error (400). */
public class CompanyScopeInvalidException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CompanyScopeInvalidException(String message) {
        super(message);
    }
}
