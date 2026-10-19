package com.ebim.tms.settlement.application;

/**
 * What one sweep of one company did (V65). Counts only.
 *
 * @param failed trips whose accrual threw and was skipped for this pass (logged; the next pass retries)
 */
public record AccrualSweepResult(int created, int adjusted, int consumed, int unaccrued, int failed) {

    public static final AccrualSweepResult NONE = new AccrualSweepResult(0, 0, 0, 0, 0);

    public AccrualSweepResult(int created, int adjusted, int consumed, int unaccrued) {
        this(created, adjusted, consumed, unaccrued, 0);
    }

    public AccrualSweepResult plus(AccrualSweepResult other) {
        return new AccrualSweepResult(created + other.created, adjusted + other.adjusted,
                consumed + other.consumed, unaccrued + other.unaccrued, failed + other.failed);
    }
}
