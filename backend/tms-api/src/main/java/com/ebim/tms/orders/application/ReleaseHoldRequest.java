package com.ebim.tms.orders.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lifting a hold. The reason is mandatory: "who let this order go, and why" is the half of a hold's
 * history that a dispute turns on.
 *
 * @param version the hold's version as the caller loaded it; optional, and a 409 when stale
 */
public record ReleaseHoldRequest(@NotBlank @Size(max = 500) String releaseReason, Long version) {
}
