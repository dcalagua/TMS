package com.ebim.tms.shared.reference;

import java.util.UUID;

/**
 * What happened to one reported position, at the index it was sent.
 *
 * <p>Carries the index rather than relying on list order so that a caller building a per-item
 * response never has to trust two lists to line up - the mistake that makes a batch API report the
 * wrong reason against the wrong item, which is worse than reporting none.
 *
 * @param positionId the stored row, or null for every outcome except
 *     {@link TrackingIntakeOutcome#RECORDED}
 * @param reason a caller-safe sentence for a refusal, null when {@link #accepted()}. Never names a
 *     table, a column or another tenant's data - an {@code UNKNOWN_SHIPMENT} says only that this
 *     company has no such shipment, which is all a partner is entitled to learn
 */
public record TrackingIntakeResult(int index, TrackingIntakeOutcome outcome, UUID positionId, String reason) {

    public static TrackingIntakeResult recorded(int index, UUID positionId) {
        return new TrackingIntakeResult(index, TrackingIntakeOutcome.RECORDED, positionId, null);
    }

    /** An accepted position that needed no row - duplicate, thinned or stale. */
    public static TrackingIntakeResult skipped(int index, TrackingIntakeOutcome outcome) {
        return new TrackingIntakeResult(index, outcome, null, null);
    }

    public static TrackingIntakeResult refused(int index, TrackingIntakeOutcome outcome, String reason) {
        return new TrackingIntakeResult(index, outcome, null, reason);
    }

    public boolean accepted() {
        return outcome.accepted();
    }
}
