package com.ebim.tms.iam.lifecycle.domain;

/**
 * The operational state MasterAdmin sets for a tenant ({@code lifecycle.v1}).
 *
 * <p>{@link #SUSPENDED} refuses writes and keeps reads; {@link #ACTIVE} resumes everything without
 * intervention. Neither deletes or rewrites data. A tenant with no lifecycle row is {@link #ACTIVE}.
 */
public enum LifecycleState {
    ACTIVE,
    SUSPENDED;

    /** Why a tenant is {@link #SUSPENDED}. An {@link #ACTIVE} tenant has no reason. */
    public enum Reason {
        NON_PAYMENT,
        MANUAL,
        TERMINATED
    }
}
