package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * The request is syntactically valid but asks for something the API does not accept - an
 * unsortable column, an unsupported filter combination.
 *
 * <p>Distinct from a Bean Validation failure, which reports per-field violations, and from an
 * {@link IllegalArgumentException}, which stays what it has always been: a programming error
 * that must surface as 500 rather than be laundered into a 400.
 */
public class InvalidRequestException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
