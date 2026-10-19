package com.ebim.tms.shared.settings;

/**
 * When a company's freight accrual is born (migration V65, ADR-023, decision D-07).
 *
 * <p>Read by {@code settlement}, written by {@code iam} with the rest of the company settings.
 */
public enum AccrualTrigger {

    /** The trip left: {@code actual_departure_at} is set. The default - the decision D-07 took. */
    DISPATCHED,

    /** The trip reached {@code COMPLETED}: {@code actual_completion_at} is set. */
    DELIVERED
}
