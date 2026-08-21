package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.EvidenceType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One proof-of-delivery artefact, as a screen sees it (migration V28).
 *
 * <p><b>No URL and no bytes.</b> The client asks for
 * {@code GET /planning/trips/{id}/deliveries/{deliveryId}/evidence/{evidenceId}/content} when it
 * actually needs the file, and that request is authenticated, company-scoped and permission-checked
 * like every other. A link in this payload would be a second, quieter way to reach a customer's
 * signed delivery note.
 *
 * <p>{@code storageKey} is absent for the same reason: it is an internal handle, and publishing it
 * would invite a client to build its own address from it.
 *
 * @param sizeBytes what the store wrote, for a size next to the file name
 * @param checksumSha256 what those bytes hash to - shown so a dispute can be argued from a
 *     downloaded copy against the record, which is the only reason it was computed
 * @param capturedAt when the photo was taken or the signature captured, when that differs from the
 *     upload; null when it does not
 */
public record DeliveryEvidenceView(
        UUID id,
        EvidenceType evidenceType,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String originalFilename,
        OffsetDateTime capturedAt,
        OffsetDateTime uploadedAt) {
}
