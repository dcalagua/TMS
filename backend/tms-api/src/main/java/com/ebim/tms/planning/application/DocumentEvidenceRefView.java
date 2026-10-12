package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentEvidenceType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A photo or signature behind a document report, without its bytes. */
public record DocumentEvidenceRefView(UUID id, DocumentEvidenceType evidenceType, String contentType,
        OffsetDateTime uploadedAt) {
}
