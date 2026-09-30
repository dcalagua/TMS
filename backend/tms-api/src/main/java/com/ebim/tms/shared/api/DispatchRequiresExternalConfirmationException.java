package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * A manual dispatch refused because the company requires the warehouse to confirm it (ADR-013
 * section 4). A {@link ConflictException} so every existing handler of a conflict still applies,
 * answered with its own problem code by {@code ApiExceptionHandler}.
 */
public class DispatchRequiresExternalConfirmationException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DispatchRequiresExternalConfirmationException(String message) {
        super(message);
    }
}
