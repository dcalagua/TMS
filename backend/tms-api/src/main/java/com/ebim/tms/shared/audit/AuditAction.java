package com.ebim.tms.shared.audit;

/**
 * What happened to the aggregate an {@link AuditRecorder#record} call names. Mirrors
 * {@code ck_audit_event_action} (migrations V22, V25 and V26).
 */
public enum AuditAction {
    CREATE,
    UPDATE,
    ACTIVATE,
    DEACTIVATE,
    ASSIGN_ORDER,
    REMOVE_ORDER,
    MOVE_ORDER,
    VEHICLE_CHANGE,
    /**
     * A trip's driver was set, swapped or cleared (migration V26) - the driver sibling of
     * {@link #VEHICLE_CHANGE}, distinct from a plain {@link #UPDATE} because "who was driving
     * this shipment, and when did that change" is a question asked on its own.
     */
    DRIVER_CHANGE,
    CONFIRM,
    CANCEL,
    CREDENTIAL_CREATE,
    CREDENTIAL_ROTATE,
    CREDENTIAL_REVOKE,
    /** An automatic planning proposal was written onto a run as draft trips. */
    AUTO_PLAN,
    IMPORT_EXECUTED,
    SHIPMENT_CONFIRMED,
    /** The four execution transitions of a trip (migration V25) - see {@code TripStatus}. */
    SHIPMENT_READY,
    SHIPMENT_DISPATCHED,
    SHIPMENT_COMPLETED,
    SHIPMENT_CANCELLED,
    /**
     * What was handed over at a stop was recorded, or corrected (migration V28). Audited, unlike
     * the stop transitions of V27, because it is the record a dispute, an insurance claim or a
     * credit note is argued from: "somebody recorded that the customer refused these goods" is a
     * compliance fact as well as an operational one, exactly as {@link #SHIPMENT_CONFIRMED} is.
     */
    DELIVERY_RESULT_RECORDED,
    /**
     * A trip was priced against a rate card (migration V30). Its own action rather than an
     * {@link #UPDATE}, because "when was this priced, and against which agreement" is a question
     * asked on its own - and the answer has to survive the card being edited afterwards.
     */
    COST_ESTIMATED,
    /** What the carrier actually invoiced was recorded, or corrected. */
    COST_ACTUAL_RECORDED,
    /**
     * A cost was settled and frozen. Audited separately because it is the moment a figure stops
     * being editable, which is exactly what somebody disputing it later needs pinned to a person.
     */
    COST_CLOSED,
    /**
     * A settled cost was made writable again. Its own action rather than the absence of a
     * {@link #COST_CLOSED} one, because "who un-froze this figure, and when" is the first question
     * asked about a cost that changed after it was signed off.
     */
    COST_REOPENED,

    /**
     * A shipment was offered to its carrier (migration V31). Five actions rather than one
     * {@code TENDER_UPDATED}, because each is a question somebody asks by itself - and creating the
     * draft is deliberately none of them: it publishes nothing and tells nobody, so
     * {@code TENDER_SENT} is the first moment anything left this company.
     *
     * <p>All five are recorded against {@link AuditAggregateType#SHIPMENT}, exactly as
     * {@link #DELIVERY_RESULT_RECORDED} is: the thing that changed commercially is the shipment, and
     * the tender is how it changed. Its id and attempt number travel in the metadata.
     */
    TENDER_SENT,
    /** The carrier agreed to run the shipment - the fact this whole feature exists to record. */
    TENDER_ACCEPTED,
    /** The carrier declined, and the metadata carries the reason they gave. */
    TENDER_REJECTED,
    /** The offer's deadline passed with no answer. Nobody did this; a deadline did. */
    TENDER_EXPIRED,
    /** The offer was withdrawn, by the shipper or because the shipment stopped being offerable. */
    TENDER_CANCELLED,

    /**
     * The roles held by one membership were replaced (migration V34). Its own action rather than a
     * plain {@link #UPDATE}, for the reason {@link #DRIVER_CHANGE} is: "who gave this account
     * permission to confirm shipments, and when" is a question asked on its own - usually after
     * something has already gone wrong - and a generic update row would bury it among corrections to
     * somebody's name.
     *
     * <p>The metadata carries the codes before <em>and</em> after. Granting and revoking access
     * themselves stay {@link #CREATE}, {@link #ACTIVATE} and {@link #DEACTIVATE} on
     * {@link AuditAggregateType#MEMBERSHIP}, which already say exactly what happened.
     */
    ROLES_CHANGED,

    /**
     * An order that came back short was put into the plannable pool for another attempt
     * (migration V36).
     *
     * <p>Its own action rather than an {@link #UPDATE}, because it is the one moment a delivery
     * that failed becomes work somebody still owes a customer, and "who decided to try again, and
     * why" is the whole question a second attempt raises. The metadata carries the status it was
     * reopened from, so the reason it needed reopening is in the row rather than only in the
     * timeline of the trip that failed.
     */
    ORDER_REOPENED,

    /**
     * A tender waterfall was started, and the moment it ended (migration V40).
     *
     * <p>Two actions, not six. Every step in between already produces {@link #TENDER_SENT},
     * {@link #TENDER_REJECTED} or {@link #TENDER_EXPIRED} against the shipment; minting a parallel
     * row for each would duplicate the trail rather than extend it. What these two add is the pair
     * of facts the per-tender rows cannot carry: who decided to run a waterfall at all, over how
     * many candidates - and how it ended, after how many offers.
     */
    WATERFALL_STARTED,
    WATERFALL_ENDED,

    /**
     * What a person decides about a dock booking (migration V41).
     *
     * <p>Four actions and not seven. Booking, moving, cancelling and marking a no-show are each a
     * commercial fact somebody may later be charged for - detention, a missed slot, a wasted trip.
     * Arriving and completing are recorded on the appointment row itself and produce no separate
     * action, exactly as V27 decided for stop transitions: a row per operational step would bury
     * the four that matter.
     */
    APPOINTMENT_BOOKED,
    APPOINTMENT_RESCHEDULED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_NO_SHOW,

    /**
     * A vehicle or a driver was taken out of service, or put back (migration V42).
     *
     * <p>Recorded against the VEHICLE or the DRIVER, not against a new aggregate type: what changed
     * is the availability of an existing master, and a planner asking "why did truck TR-04 not run
     * on the 14th" reads it on the truck. Releasing deletes the block row, so this pair is the only
     * surviving record that it ever existed - which is the point.
     */
    RESOURCE_BLOCKED,
    RESOURCE_RELEASED,

    /**
     * Freight audit (migration V46).
     *
     * <p>Five, and the last three are the ones an auditor searches for by name: who authorised an
     * obligation, who refused one, and when it was handed to whoever pays. Receiving and matching
     * are here because "when did this arrive" and "what did we conclude" are the questions asked
     * immediately before those three.
     */
    INVOICE_RECEIVED,
    INVOICE_MATCHED,
    INVOICE_APPROVED,
    INVOICE_REJECTED,
    INVOICE_EXPORTED
}
