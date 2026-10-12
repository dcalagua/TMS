package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a carrier reports about one document (ADR-018). Multipart stops at the controller; the
 * service takes streams, so it stays callable from a job or an integration ({@code LayeringTest}).
 *
 * @param reportedAt when it happened, if the carrier says; null is "now"
 * @param photos one to five, mandatory for {@link DocumentDeliveryStatus#DELIVERED}
 * @param signature optional
 */
public record DocumentReportCommand(DocumentDeliveryStatus status, DocumentDeliveryReason reasonCode, String remark,
        OffsetDateTime reportedAt, List<EvidenceUpload> photos, EvidenceUpload signature) {

    public DocumentReportCommand {
        photos = photos == null ? List.of() : List.copyOf(photos);
    }
}
