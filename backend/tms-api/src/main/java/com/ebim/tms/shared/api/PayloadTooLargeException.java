package com.ebim.tms.shared.api;

import java.io.Serial;

/** A request body over an endpoint's documented limit; answered 413 by {@code ApiExceptionHandler}. */
public class PayloadTooLargeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException(String message) {
        super(message);
    }
}
