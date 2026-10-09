package com.ebim.tms.shared.security;

import java.io.Serial;

/**
 * The organization's commercial contract does not allow it to operate TMS right now
 * ({@code appActive=false}, or no snapshot where one is required). Nothing is deleted: operation
 * resumes as soon as MasterAdmin sends a snapshot that allows it.
 */
public class CommercialAccessSuspendedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CommercialAccessSuspendedException(String message) {
        super(message);
    }
}
