package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * The addressed resource does not exist <em>inside the caller's company scope</em>.
 *
 * <p>Scoped repositories return nothing for a row that belongs to another company, so a
 * cross-tenant read is answered with 404 and not 403. That is intentional: 403 would confirm
 * that the id exists somewhere, which is itself a cross-tenant leak.
 */
public class ResourceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
