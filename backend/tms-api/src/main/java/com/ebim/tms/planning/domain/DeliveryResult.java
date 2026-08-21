package com.ebim.tms.planning.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * What happened to <em>the goods of one order</em> at one {@link TripStop} (migration V28).
 *
 * <ul>
 *   <li>{@link #DELIVERED} - handed over in full.</li>
 *   <li>{@link #PARTIAL} - some of it was taken and some was not.</li>
 *   <li>{@link #REJECTED} - the customer refused it. Somebody was there and said no.</li>
 *   <li>{@link #FAILED} - the attempt failed for a reason that is not a refusal: nobody at the
 *       address, dock closed, goods damaged in transit and pulled back.</li>
 *   <li>{@link #NOT_ATTEMPTED} - never taken off the vehicle.</li>
 * </ul>
 *
 * <p><b>Why this is not {@link StopExecutionStatus}.</b> That one says what the <em>vehicle</em>
 * did at a destination; this says what the <em>goods</em> did there. A stop serving three orders
 * can be {@code COMPLETED} with one of them {@code REJECTED}, and both statements are true - which
 * is precisely the case a single status could not represent. See
 * {@code docs/domain/PROOF_OF_DELIVERY_V1.md}.
 *
 * <p><b>Why there is no PENDING.</b> An order with no {@link OrderDelivery} row has simply not been
 * recorded yet, which is the same statement; a value for it would give one fact two
 * representations - the trap {@code TripStatus} avoided by not having a {@code DISPATCHED} beside
 * {@code IN_TRANSIT}.
 *
 * <p><b>Why there is no transition table.</b> Unlike a stop, a delivery result is not walked
 * through: it is recorded once, at the end, and corrected if it was typed wrong. Any result may
 * replace any other while the shipment is still open, and {@code TripDeliveryService} - not this
 * enum - is what decides when that window closes.
 */
public enum DeliveryResult {
    DELIVERED,
    PARTIAL,
    REJECTED,
    FAILED,
    NOT_ATTEMPTED;

    /** The results that assert goods changed hands, and so must carry the moment they did. */
    private static final Set<DeliveryResult> HANDOVER = EnumSet.of(DELIVERED, PARTIAL);

    /** The results reached with somebody present - the only ones a receiver can be named on. */
    private static final Set<DeliveryResult> WITNESSED = EnumSet.of(DELIVERED, PARTIAL, REJECTED);

    /**
     * The results that did not go to plan and therefore carry a sentence explaining why.
     * {@link #NOT_ATTEMPTED} is not among them: the stop it belongs to was skipped or failed, and
     * migration V27 already requires a typed {@link TripException} for that.
     */
    private static final Set<DeliveryResult> SHORTFALL = EnumSet.of(PARTIAL, REJECTED, FAILED);

    /** Whether {@code deliveredAt} is required - mirrors {@code ck_order_delivery_delivered_at_required}. */
    public boolean requiresDeliveredAt() {
        return HANDOVER.contains(this);
    }

    /** Whether {@code deliveredAt} is a contradiction: nothing was attempted, so nothing happened at a time. */
    public boolean forbidsDeliveredAt() {
        return this == NOT_ATTEMPTED;
    }

    /**
     * Whether a receiver may be named. False for {@link #FAILED} and {@link #NOT_ATTEMPTED}: nobody
     * took the goods, took part of them, or refused them to the driver's face, so the only name
     * that could go there is the driver's own - which is not what the column means.
     */
    public boolean allowsReceiver() {
        return WITNESSED.contains(this);
    }

    /** Whether this result must be explained - mirrors {@code ck_order_delivery_shortfall_requires_notes}. */
    public boolean requiresNotes() {
        return isShortfall();
    }

    /**
     * Whether the customer was left short: refused it, took part of it, or the attempt failed.
     *
     * <p>The same set {@link #requiresNotes()} reports, asked as the question the alert board asks
     * rather than as the question the database constraint asks. Two names for one set is worth it
     * here - {@code TripAlertPublisher} raising {@code DELIVERY_FAILED} because a result
     * "requires notes" would read as a coincidence, and the day somebody widens one of the two
     * meanings they would find out it was not.
     *
     * <p>{@link #NOT_ATTEMPTED} is deliberately outside it, exactly as it is outside
     * {@code SHORTFALL}: nothing was tried, and the skipped or failed stop it belongs to already
     * carries a typed {@code TripException} explaining why.
     */
    public boolean isShortfall() {
        return SHORTFALL.contains(this);
    }

    /** Whether the customer got everything they were owed. The one question a report starts from. */
    public boolean isComplete() {
        return this == DELIVERED;
    }
}
