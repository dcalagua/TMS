package com.ebim.tms.planning.application;

import java.util.List;

/** A tracked document with its history and evidence (ADR-018). */
public record DocumentDeliveryDetailView(DocumentDeliveryRowView delivery, List<DocumentDeliveryEventView> events,
        List<DocumentEvidenceView> evidence) {
}
