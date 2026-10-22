package com.ebim.tms.planning.application;

import java.util.List;

/**
 * One trip in the carrier portal (ADR-018): its stops in sequence and the documents to report at
 * each. {@code evidenceEnabled} false means this deployment stores no photos, so a document can be
 * reported rejected or not delivered but not delivered, and a delivery result is recorded without
 * evidence (V70).
 *
 * <p>{@code incidents} (V70) are the trip's problems - the ones the carrier reported and the ones
 * the office did - so the driver sees what is already on file before reporting it again.
 */
public record PortalTripDetailView(PortalTripSummaryView trip, boolean evidenceEnabled, List<PortalStopView> stops,
        List<PortalIncidentView> incidents) {
}
