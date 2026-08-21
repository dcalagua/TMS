package com.ebim.tms.shared.reference;

/**
 * How far an order got on the road, as a second dimension beside {@code OrderStatus}.
 *
 * <p><b>Why this is not more values on {@code OrderStatus}.</b> That enum is a
 * <em>planning</em> lifecycle: not ready, ready, planned, cancelled. It answers "may this be put
 * on a truck?", and a planner's board is built on it. What happened at the customer's dock is a
 * different question with a different owner and a different moment - an order stays {@code
 * PLANNED} while it is delivered, refused, or brought back, and none of those change whether it
 * was planned. Bolting {@code DELIVERED} onto the planning enum would make every planning query
 * carry a fulfilment meaning it never asked for, and the first status a report needed that
 * planning does not have ({@code PARTIALLY_DELIVERED}) would prove the mistake.
 *
 * <p><b>Why it is derived and not stored.</b> {@code tms.order_delivery} (migration V28) already
 * records what happened to each order at each stop, and it is the fact. A column on the order
 * would be a second copy of that fact, kept in step by whoever remembered to - and the day the two
 * disagreed there would be no way to say which was right. This is computed from the delivery rows
 * on read, so there is nothing to drift. It is also why there is no migration for this: nothing
 * new is recorded, only something that was already recorded is now shown.
 *
 * <p><b>Why {@code PENDING} rather than "unknown".</b> An order with no delivery row has not been
 * delivered yet - whether because it is not planned, or is planned for tomorrow, or is on a
 * vehicle right now. Which of those is true is exactly what {@code OrderStatus} and the trip say,
 * so this dimension does not repeat them.
 */
public enum OrderFulfillmentStatus {

    /** Nothing has been recorded against this order yet. The starting state of every order. */
    PENDING,

    /** Handed over in full. */
    DELIVERED,

    /** Some of it was taken and some was not. */
    PARTIALLY_DELIVERED,

    /** The customer was there and refused it. */
    REJECTED,

    /** The attempt failed without being a refusal: nobody at the address, dock closed, goods damaged. */
    FAILED,

    /** The goods never came off the vehicle - the stop was skipped or failed before them. */
    NOT_ATTEMPTED;

    /** Whether the customer got everything they were owed. The one question a report starts from. */
    public boolean isComplete() {
        return this == DELIVERED;
    }

    /**
     * Whether this order still owes the customer something. {@code PENDING} is deliberately not
     * one of these: an order that has not been attempted yet is not a problem, it is a Tuesday.
     */
    public boolean isShortfall() {
        return this == PARTIALLY_DELIVERED || this == REJECTED || this == FAILED || this == NOT_ATTEMPTED;
    }
}
