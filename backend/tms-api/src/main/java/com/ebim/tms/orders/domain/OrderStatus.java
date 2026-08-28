package com.ebim.tms.orders.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The transport order lifecycle, planning <em>and</em> execution, and <em>the</em> definition of
 * which moves between its states are legal (migrations V10 and V36).
 *
 * <pre>
 *   NOT_READY -&gt; READY_FOR_PLANNING -&gt; PLANNED -&gt; IN_EXECUTION -&gt; DELIVERED
 *                        ^                                     \-&gt; PARTIALLY_DELIVERED
 *                        |                                     \-&gt; DELIVERY_FAILED
 *                        \--------- reopen for another attempt ----/
 *                 (any state before departure) -&gt; CANCELLED
 * </pre>
 *
 * <h2>What each state means</h2>
 *
 * <ul>
 *   <li>{@link #NOT_READY} - the default state of every new order. Fully editable. Re-entered by
 *       any edit, because an edit may invalidate the completeness last confirmed.</li>
 *   <li>{@link #READY_FOR_PLANNING} - passed the completeness check and visible to planning as
 *       plannable. Still editable.</li>
 *   <li>{@link #PLANNED} - committed to a trip that has not departed. The goods are still on the
 *       dock and the plan can still be undone by removing the order from its trip.</li>
 *   <li>{@link #IN_EXECUTION} - the vehicle carrying this order has left. Not editable, not
 *       cancellable, and not removable from its trip.</li>
 *   <li>{@link #DELIVERED} - the customer got everything they were owed.</li>
 *   <li>{@link #PARTIALLY_DELIVERED} - some of it was handed over and some was not. The remainder
 *       is still owed, so the order may be reopened for another attempt.</li>
 *   <li>{@link #DELIVERY_FAILED} - the trip finished and the goods did not arrive: refused,
 *       failed, never taken off the vehicle, or never recorded at all. Reopenable.</li>
 *   <li>{@link #CANCELLED} - terminal. Reachable from every state in which nothing has left the
 *       dock, and from a closed-out shortfall that the business decides to give up on.</li>
 * </ul>
 *
 * <h2>Why the execution states are here and not only in {@code OrderFulfillmentStatus}</h2>
 *
 * <p>{@code OrderFulfillmentStatus} (shared) is derived on read from {@code tms.order_delivery} and
 * answers "what happened at the dock". It is the live, correctable, per-stop view and it stays
 * exactly that. This enum answers a different question - "what may be done with this order next" -
 * and three of its answers cannot be derived from delivery rows at all:
 *
 * <ul>
 *   <li>an order on a departed vehicle has no delivery row yet, and yet is emphatically not in the
 *       same state as one sitting in a draft trip planned for next Tuesday;</li>
 *   <li>an order whose delivery failed has to <em>return to the plannable pool</em> for a second
 *       attempt. Before V36 it stayed {@code PLANNED} forever and was stranded: not plannable, not
 *       cancellable, not deliverable;</li>
 *   <li>an order that is finished has to stop appearing as outstanding work.</li>
 * </ul>
 *
 * <p>This is therefore not a second copy of the delivery fact. It is the lifecycle
 * <em>consequence</em> of it, and it cannot drift from it because {@code closeOut} is recomputed
 * from the delivery rows inside the same transaction as every change to them - at trip completion
 * and again at every correction afterwards. See {@code docs/domain/ORDER_LIFECYCLE_V2.md} and
 * ADR-009.
 *
 * <h2>Why the outcomes are mutually reachable</h2>
 *
 * <p>A delivery record is corrected in place while the shipment is open, and the window stays open
 * after completion on purpose - the paperwork comes back at 18:40. So {@code DELIVERED} is not
 * terminal: a note keyed wrong and fixed an hour later must be able to move the order to
 * {@code PARTIALLY_DELIVERED}, and back. What is terminal is {@link #CANCELLED}, and
 * {@link #DELIVERED} is terminal only in the sense that there is nothing left to plan.
 */
public enum OrderStatus {
    NOT_READY,
    READY_FOR_PLANNING,
    PLANNED,
    IN_EXECUTION,
    DELIVERED,
    PARTIALLY_DELIVERED,
    DELIVERY_FAILED,
    CANCELLED;

    /** The states in which the order is committed to a trip - counted as planned demand. */
    private static final Set<OrderStatus> COMMITTED =
            EnumSet.of(PLANNED, IN_EXECUTION, DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED);

    /** The states reached by closing out a trip: what the road actually did with the goods. */
    private static final Set<OrderStatus> CLOSED_OUT =
            EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED);

    /** Closed out with something still owed to the customer - the reopenable outcomes. */
    private static final Set<OrderStatus> SHORTFALL = EnumSet.of(PARTIALLY_DELIVERED, DELIVERY_FAILED);

    /** The states in which the header and the lines may still be changed. */
    private static final Set<OrderStatus> EDITABLE = EnumSet.of(NOT_READY, READY_FOR_PLANNING);

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            NOT_READY, EnumSet.of(READY_FOR_PLANNING, CANCELLED),
            // NOT_READY: any edit resets readiness (TransportOrder.applyChanges).
            READY_FOR_PLANNING, EnumSet.of(NOT_READY, PLANNED, CANCELLED),
            // READY_FOR_PLANNING: released from its trip before it departed.
            PLANNED, EnumSet.of(READY_FOR_PLANNING, IN_EXECUTION, CANCELLED),
            // No CANCELLED: the goods are on a moving vehicle, so "this order did not happen" is
            // not a true statement any more. The same reasoning that denies TripStatus.IN_TRANSIT
            // a move to CANCELLED.
            IN_EXECUTION, EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, DELIVERY_FAILED),
            // The three outcomes are mutually reachable because a delivery record is corrected in
            // place after completion - see the class comment. DELIVERED is not reopenable: there
            // is nothing left to deliver, and not cancellable: it already happened.
            DELIVERED, EnumSet.of(PARTIALLY_DELIVERED, DELIVERY_FAILED),
            PARTIALLY_DELIVERED, EnumSet.of(DELIVERED, DELIVERY_FAILED, READY_FOR_PLANNING, CANCELLED),
            DELIVERY_FAILED, EnumSet.of(DELIVERED, PARTIALLY_DELIVERED, READY_FOR_PLANNING, CANCELLED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    /** Whether {@code target} may be reached from this state. Reflexive moves are not transitions. */
    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** The states reachable from this one, for a UI that renders only the buttons that work. */
    public Set<OrderStatus> allowedTransitions() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    /** Whether the header and lines may still be changed. */
    public boolean isEditable() {
        return EDITABLE.contains(this);
    }

    /** Whether the order is committed to a trip - planned demand, however far down the road. */
    public boolean isCommitted() {
        return COMMITTED.contains(this);
    }

    /** Whether the order is in the plannable pool right now. */
    public boolean isPlannable() {
        return this == READY_FOR_PLANNING;
    }

    /** Whether a trip has been closed out against this order. */
    public boolean isClosedOut() {
        return CLOSED_OUT.contains(this);
    }

    /**
     * Whether the order was closed out still owing the customer something, and may therefore be
     * reopened for another attempt. {@link #DELIVERED} is not one of these and neither is
     * {@link #CANCELLED}.
     */
    public boolean isReopenable() {
        return SHORTFALL.contains(this);
    }

    /** Whether nothing further can happen to the order. */
    public boolean isTerminal() {
        return this == CANCELLED;
    }
}
