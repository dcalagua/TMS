package com.ebim.tms.orders.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /orders/release} (ADR-014 section 8): several releases in one call.
 *
 * @param overrideReason one reason for the whole batch, applied to every item that needs one and
 *                       recorded on each of their {@code ORDER_RELEASED} events
 */
public record BulkReleaseRequest(
        @NotEmpty @Size(max = 200) List<@NotNull UUID> orderIds,
        @Size(max = 500) String overrideReason) {
}
