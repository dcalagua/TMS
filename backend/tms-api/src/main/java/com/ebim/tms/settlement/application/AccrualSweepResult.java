package com.ebim.tms.settlement.application;

/** What one sweep of one company did (V65). Counts only. */
public record AccrualSweepResult(int created, int adjusted, int consumed, int unaccrued) {

    public static final AccrualSweepResult NONE = new AccrualSweepResult(0, 0, 0, 0);

    public AccrualSweepResult plus(AccrualSweepResult other) {
        return new AccrualSweepResult(created + other.created, adjusted + other.adjusted,
                consumed + other.consumed, unaccrued + other.unaccrued);
    }
}
