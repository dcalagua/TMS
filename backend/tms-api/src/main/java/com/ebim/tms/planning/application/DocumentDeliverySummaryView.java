package com.ebim.tms.planning.application;

import java.util.List;

/**
 * The monitor's totals (ADR-018). {@code awaitingReturn} is reported and not yet back at the office.
 */
public record DocumentDeliverySummaryView(long total, long pending, long delivered, long rejected, long notDelivered,
        long returned, long awaitingReturn, long inconsistent, List<CarrierDocumentTotalsView> byCarrier) {
}
