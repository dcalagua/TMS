package com.ebim.tms.shared.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * The default store: there isn't one.
 *
 * <p>Every call refuses with {@link EvidenceStorageUnavailableException}, which the API turns into
 * a 503 carrying the {@code storage-unavailable} code. That is the whole implementation, and it is
 * the point of it: a deployment that has not configured a store gets a clear refusal rather than a
 * quietly invented location, a base64 column, or an upload that appears to succeed and stores
 * nothing.
 *
 * <p>Only the evidence half is disabled. Delivery <em>results</em> are recorded normally - the
 * result is the fact and the artefact is corroboration - so a company running without a store
 * still answers "was this delivered, to whom, and when".
 */
public class DisabledEvidenceStorage implements EvidenceStoragePort {

    private static final String MESSAGE =
            "Delivery evidence storage is not configured for this deployment. "
                    + "Delivery results can still be recorded without an attachment.";

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public StoredObject store(UUID companyId, String contentType, String originalFilename, InputStream content,
            long maxBytes) {
        throw new EvidenceStorageUnavailableException(MESSAGE);
    }

    @Override
    public StoredObjectContent open(UUID companyId, String storageKey) {
        throw new EvidenceStorageUnavailableException(MESSAGE);
    }
}
