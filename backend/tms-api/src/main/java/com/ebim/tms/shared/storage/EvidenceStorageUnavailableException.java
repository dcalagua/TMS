package com.ebim.tms.shared.storage;

/**
 * This deployment has no evidence store configured, so there is nowhere to put a signature or a
 * photo - and nowhere to read one back from.
 *
 * <p>Its own type rather than an {@link EvidenceStorageException} because the answer to the caller
 * is different in kind: nothing is broken, a feature is simply not turned on here, and no retry
 * will change that until an administrator sets {@code tms.storage.evidence.mode}. It maps to a 503
 * with the {@code storage-unavailable} code, which is what lets a screen hide its upload button
 * instead of showing an error that reads like a fault.
 *
 * <p>Recording the delivery <em>result</em> is unaffected: the result is the fact, the artefact is
 * corroboration, and a company with no store still records what was delivered.
 */
public class EvidenceStorageUnavailableException extends RuntimeException {

    public EvidenceStorageUnavailableException(String message) {
        super(message);
    }
}
