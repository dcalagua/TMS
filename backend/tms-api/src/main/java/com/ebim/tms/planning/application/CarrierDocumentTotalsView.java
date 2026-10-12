package com.ebim.tms.planning.application;

import java.util.UUID;

/** One carrier's line of the monitor's totals. A null carrier is the own fleet. */
public record CarrierDocumentTotalsView(UUID carrierId, String carrierName, long total, long pending, long delivered,
        long rejected, long notDelivered, long awaitingReturn) {
}
