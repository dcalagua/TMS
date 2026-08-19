package com.ebim.tms.shared.security;

import java.io.Serial;

/**
 * The caller selected a company they hold no active membership in.
 *
 * <p>Answered with 403 and a message that does not distinguish "no such company" from "not
 * your company", because either answer would confirm the existence of another tenant's data.
 */
public class CompanyScopeDeniedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CompanyScopeDeniedException(String message) {
        super(message);
    }
}
