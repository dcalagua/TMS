package com.ebim.tms.integration.domain;

/**
 * How an inbound delivery ended, as recorded in the integration inbox.
 *
 * <p>The distinction that matters operationally is {@link #REJECTED} versus {@link #FAILED}:
 * the first is the partner's problem and resending the same payload will not help, the second is
 * ours and resending is exactly the right reaction. A single "error" status would make that
 * question unanswerable from the inbox, which is the one place someone looks at 3am.
 */
public enum IntegrationRequestStatus {

    /** Everything in the delivery was accepted. */
    SUCCEEDED,

    /** A batch in which some items were accepted and others were not. Never used for a single object. */
    PARTIAL,

    /** The payload was refused on its merits: validation, a business conflict, an unknown master. */
    REJECTED,

    /** TMS could not process it. The partner should retry; the cause is in the server log. */
    FAILED
}
