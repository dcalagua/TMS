package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentEvidenceType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A photo or signature in the office monitor, without its bytes. */
public record DocumentEvidenceView(UUID id, DocumentEvidenceType evidenceType, String contentType, long sizeBytes,
        String originalFilename, OffsetDateTime uploadedAt, String uploadedByName) {
}
