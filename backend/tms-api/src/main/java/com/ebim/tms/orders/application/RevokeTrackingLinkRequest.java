package com.ebim.tms.orders.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Taking a link back (ADR-029). The reason is mandatory - "sent to the wrong customer" and "the order
 * was cancelled" are the two a leak investigation needs to tell apart.
 *
 * @param version the link's version as loaded, for optimistic locking; optional
 */
public record RevokeTrackingLinkRequest(
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters")
        String reason,
        Long version) {
}
