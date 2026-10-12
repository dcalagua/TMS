package com.ebim.tms.planning.application;

/** The tracking report's totals for its filter (ADR-019 section 2). */
public record ShipmentTrackingSummaryView(long shipments, long inTransit, long completed, long withPendingDocuments,
        long documentsTotal, long documentsPending, long documentsDelivered, long documentsRejected,
        long documentsNotDelivered, long awaitingReturn, long dispatchMismatches) {
}
