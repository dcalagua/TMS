package com.ebim.tms.settlement.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Executed trips with no accrual, and why (V65, ADR-023). Own-fleet trips are not listed: they are
 * not freight payables. {@code truncated} says the list stopped at its cap.
 */
public record UnaccruedTripsView(LocalDate from, LocalDate to, String trigger, List<Row> rows, boolean truncated) {

    public UnaccruedTripsView {
        rows = List.copyOf(rows);
    }

    public record Row(
            UUID tripId,
            String shipmentNumber,
            UUID carrierId,
            String carrierName,
            OffsetDateTime executedAt,
            String reason) {
    }
}
