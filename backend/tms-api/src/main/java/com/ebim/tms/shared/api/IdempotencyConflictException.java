package com.ebim.tms.shared.api;

import java.io.Serial;

/**
 * The same {@code Idempotency-Key} arrived again with a different payload.
 *
 * <p>Answering it with the first payload's response would be the worst possible outcome: the
 * caller would be told their second, different object was accepted when nothing was written. So
 * it is refused, loudly, as the client bug it is.
 *
 * <p>A subclass of {@link ConflictException} so that any handler treating conflicts generically
 * still does the right thing, with {@code ApiExceptionHandler} giving it a distinct machine code
 * on top.
 */
public class IdempotencyConflictException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
