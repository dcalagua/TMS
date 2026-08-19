package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * A business invariant refused the write: most commonly a duplicate value in a scope that
 * must be unique (a master data code already used inside the same company).
 *
 * <p>Services check for the conflict before writing whenever practical, so the message can
 * name the offending value. The database's own unique constraint remains the backstop against
 * a race between two concurrent requests; a caller that hits it is still answered with this
 * same exception, translated from {@code DataIntegrityViolationException}, so the response
 * shape never depends on which of the two paths caught the duplicate.
 */
public class ConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
