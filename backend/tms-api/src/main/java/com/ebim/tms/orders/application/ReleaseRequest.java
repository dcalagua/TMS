package com.ebim.tms.orders.application;

import jakarta.validation.constraints.Size;

/**
 * The optional body of {@code POST /orders/{id}/mark-ready} (ADR-014 section 8).
 *
 * @param overrideReason why the order is released despite a warning that requires one (cutoff missed,
 *                       frequency override); audited on {@code ORDER_RELEASED}. Ignored as blank.
 */
public record ReleaseRequest(@Size(max = 500) String overrideReason) {
}
