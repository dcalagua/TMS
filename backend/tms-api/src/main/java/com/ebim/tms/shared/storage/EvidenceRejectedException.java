package com.ebim.tms.shared.storage;

/**
 * The store refused these bytes for a reason the caller can fix: the upload is larger than the
 * configured ceiling, or there are no bytes at all.
 *
 * <p>Raised <em>while writing</em> rather than from a length check beforehand, because a chunked
 * upload does not have to declare its length: the only number that cannot be lied about is the
 * count of bytes that actually arrived. Whatever was written before the refusal is discarded, so a
 * rejected upload leaves nothing behind.
 *
 * <p>A caller-facing 400 through {@code ApiExceptionHandler}, with the limit in the message so an
 * operator knows to send a smaller photo - unlike {@link EvidenceStorageException}, which is a
 * server-side fault and says nothing.
 */
public class EvidenceRejectedException extends RuntimeException {

    public EvidenceRejectedException(String message) {
        super(message);
    }
}
