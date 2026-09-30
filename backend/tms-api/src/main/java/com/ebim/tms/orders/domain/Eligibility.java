package com.ebim.tms.orders.domain;

/**
 * Whether an order may be released for planning right now (ADR-014 section 4).
 *
 * <p><b>Not an order state.</b> It is derived every time it is read and never persisted; the only
 * persisted meaning of "released" is {@code OrderStatus.READY_FOR_PLANNING}.
 */
public enum Eligibility {
    ELIGIBLE,
    WARNING,
    BLOCKED
}
