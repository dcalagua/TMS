package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DocumentDeliveryReason;
import com.ebim.tms.planning.domain.DocumentDeliveryStatus;
import com.ebim.tms.shared.reference.LogisticsDocumentPort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A document on a trip as the carrier sees it (ADR-018). No amount: what an invoice is worth is
 * none of the carrier's business. {@code editable} is false once the office has the copy back.
 * {@code fiscal} (ADR-028) is the authority's identity of the document - an Ecuadorian guide's clave
 * de acceso, for a roadside check - or null when the ERP sent none.
 */
public record PortalDocumentView(
        UUID id,
        UUID documentId,
        String documentType,
        String documentNumber,
        List<String> orderNumbers,
        String customerName,
        DocumentDeliveryStatus status,
        DocumentDeliveryReason reasonCode,
        String remark,
        OffsetDateTime reportedAt,
        OffsetDateTime recordedAt,
        OffsetDateTime returnedAt,
        boolean editable,
        List<DocumentEvidenceRefView> evidence,
        LogisticsDocumentPort.FiscalIdentity fiscal) {
}
