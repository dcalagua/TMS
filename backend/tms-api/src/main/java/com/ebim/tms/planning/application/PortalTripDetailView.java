package com.ebim.tms.planning.application;

import java.util.List;

/**
 * One trip in the carrier portal (ADR-018): its stops in sequence and the documents to report at
 * each. {@code evidenceEnabled} false means this deployment stores no photos, so a document can be
 * reported rejected or not delivered but not delivered.
 */
public record PortalTripDetailView(PortalTripSummaryView trip, boolean evidenceEnabled, List<PortalStopView> stops) {
}
