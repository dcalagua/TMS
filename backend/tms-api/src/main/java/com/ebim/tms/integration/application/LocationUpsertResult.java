package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.IntakeOutcome;
import com.ebim.tms.shared.reference.LocationIntakeResult;
import java.util.UUID;

/**
 * What one location delivery produced.
 *
 * <p>{@code id} is the TMS identity a partner should store, so a later delivery can be traced
 * without a lookup by code. {@code outcome} tells a redelivery apart from a first delivery, which
 * is what lets a sending system verify its own retry behaviour.
 */
public record LocationUpsertResult(UUID id, String code, IntakeOutcome outcome) {

    public static LocationUpsertResult from(LocationIntakeResult result) {
        return new LocationUpsertResult(result.id(), result.code(), result.outcome());
    }
}
