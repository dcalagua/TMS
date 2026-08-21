package com.ebim.tms.shared.reference;

/**
 * What an idempotent inbound upsert actually did to the business row.
 *
 * <p>Returned to the sending system so a partner can reconcile without diffing: a redelivery of
 * an unchanged object reports {@link #UPDATED} or {@link #UNCHANGED} rather than failing, which
 * is what makes "safe to retry" true in practice and not only in the documentation.
 */
public enum IntakeOutcome {

    /** No row carried this identity before; one was created. */
    CREATED,

    /** The row already existed and was rewritten from the payload. */
    UPDATED,

    /**
     * The row already existed and the payload asked for nothing this module would change - a
     * pure redelivery. Distinguished from {@link #UPDATED} because a partner replaying a week of
     * traffic wants to see that it changed nothing.
     */
    UNCHANGED
}
