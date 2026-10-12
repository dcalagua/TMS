package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentEvidenceType;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.storage.EvidenceStoragePort;
import com.ebim.tms.shared.storage.EvidenceStorageProperties;
import com.ebim.tms.shared.storage.StoredObject;
import com.ebim.tms.shared.storage.StoredObjectContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the photos and signatures of a document report to ADR-006's private store, with the
 * checks {@link DeliveryEvidenceService} applies to a proof of delivery: the media type is on the
 * deployment's allow-list before a byte is read, and the size is enforced by the store while it
 * writes. A signature must be an image - a PDF "signature" is a document, not a signature.
 */
@Component
public class DocumentEvidenceStore {

    /** One stored artefact, with what the database row needs. */
    public record Stored(DocumentEvidenceType type, StoredObject object, String contentType, String fileName) {
    }

    private final EvidenceStoragePort storage;
    private final EvidenceStorageProperties properties;

    public DocumentEvidenceStore(EvidenceStoragePort storage, EvidenceStorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return storage.isEnabled();
    }

    /** Refuses a file before any is written, so a report with one bad file stores nothing. */
    public void requireAcceptable(DocumentEvidenceType type, EvidenceUpload upload) {
        if (upload == null || upload.content() == null) {
            throw new InvalidRequestException("A file is required.");
        }
        String contentType = upload.contentType();
        if (!properties.allows(contentType)) {
            throw new InvalidRequestException("Photos and signatures must be one of "
                    + String.join(", ", properties.allowedContentTypes()) + ".");
        }
        if (type == DocumentEvidenceType.SIGNATURE && !contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new InvalidRequestException("A signature must be an image.");
        }
    }

    public Stored store(UUID companyId, DocumentEvidenceType type, EvidenceUpload upload) {
        requireAcceptable(type, upload);
        String contentType = upload.contentType().trim().toLowerCase(Locale.ROOT);
        try (InputStream content = upload.content()) {
            StoredObject stored = storage.store(companyId, contentType, upload.fileName(), content,
                    properties.maxBytes());
            return new Stored(type, stored, contentType, upload.fileName());
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The uploaded file could not be read. Try uploading it again.");
        }
    }

    public StoredObjectContent open(UUID companyId, String storageKey) {
        return storage.open(companyId, storageKey);
    }
}
