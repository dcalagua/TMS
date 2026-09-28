package com.ebim.tms.orders.domain;

/**
 * One reason behind an order's {@link Eligibility}.
 *
 * @param severity         {@code BLOCKED} or {@code WARNING}, from the code
 * @param requiresOverride whether releasing over it needs a person's reason, from the code
 * @param detail           the sentence a planner reads
 */
public record SchedulingReason(SchedulingReasonCode code, Eligibility severity, boolean requiresOverride,
        String detail) {

    public static SchedulingReason of(SchedulingReasonCode code, String detail) {
        return new SchedulingReason(code, code.severity(), code.requiresOverride(), detail);
    }
}
