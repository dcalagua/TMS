package com.ebim.tms.shared.storage;

/**
 * The store was asked to do something it should have been able to do, and could not: the volume is
 * full, the object is missing, the write failed halfway.
 *
 * <p>Unchecked, and deliberately <em>not</em> mapped to a 4xx by {@code ApiExceptionHandler}: this
 * is a server-side fault, and the caller's correct reaction is to try again later rather than to
 * change their request. The message is for the log; the client sees the generic 500 document with
 * a correlation id, exactly like any other unexpected failure.
 */
public class EvidenceStorageException extends RuntimeException {

    public EvidenceStorageException(String message) {
        super(message);
    }

    public EvidenceStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
