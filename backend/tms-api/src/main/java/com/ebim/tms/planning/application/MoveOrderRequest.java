package com.ebim.tms.planning.application;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Moving an already-assigned order to another trip in one transaction: the source assignment is
 * closed and the target one opened together, so a target that has no room leaves the source
 * exactly as it was.
 */
public record MoveOrderRequest(@NotNull(message = "is required") UUID targetTripId) {
}
