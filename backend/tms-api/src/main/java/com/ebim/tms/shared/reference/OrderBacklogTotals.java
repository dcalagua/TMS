package com.ebim.tms.shared.reference;

/**
 * How much of a range of service days somebody has planned, and how much is still waiting -
 * produced by {@link OrderPlanningPort#backlogTotals} and read by the KPI report.
 *
 * <p><b>This is the product's stated planning invariant, expressed as a type.</b>
 * {@code INPUT_ORDERS = PLANNED_ORDERS + UNPLANNED_ORDERS}: {@link #planned()} plus
 * {@link #unplanned()} is {@link #inputOrders()}, by construction and not by a check that could be
 * forgotten. Cancelled orders sit outside all three, which is the only way the identity can hold -
 * an order somebody withdrew was never work the plan failed to cover.
 *
 * <p><b>Why the committed states are counted separately and then summed.</b> Before V36 an order
 * that was on a shipment was {@code PLANNED} and stayed that way forever, so one counter said
 * everything. Now a planned order departs, is delivered, or comes back short, and all four of those
 * are still "a planner put this on a truck". Carrying the leaves and deriving {@link #planned()}
 * from them - rather than storing a total beside its own parts - is what stops the two disagreeing,
 * and it means the KPI's planned-rate did not silently fall the day the execution states arrived.
 *
 * @param notReady     orders that exist and are not yet plannable ({@code NOT_READY}). Counted as
 *                     unplanned rather than left out: from the point of view of a day's coverage a
 *                     demand nobody has released is still a demand nobody has moved, and hiding it
 *                     would let a company report 100% planned over a backlog it never looked at
 * @param readyToPlan  orders a planner may assign right now ({@code READY_FOR_PLANNING}). Includes
 *                     orders reopened after a failed attempt - a second attempt is unplanned work
 *                     again, and counting it anywhere else would report a day as covered while a
 *                     customer is still waiting
 * @param onTrip       committed to a shipment that has not departed ({@code PLANNED})
 * @param inExecution  on a vehicle that has left ({@code IN_EXECUTION})
 * @param delivered    closed out in full ({@code DELIVERED})
 * @param shortfall    closed out still owing something ({@code PARTIALLY_DELIVERED},
 *                     {@code DELIVERY_FAILED}) and not yet reopened
 * @param cancelled    orders withdrawn ({@code CANCELLED}). Reported so the figure can be shown
 *                     beside the rest instead of quietly changing what the other three sum to
 */
public record OrderBacklogTotals(long notReady, long readyToPlan, long onTrip, long inExecution,
        long delivered, long shortfall, long cancelled) {

    public static final OrderBacklogTotals EMPTY = new OrderBacklogTotals(0, 0, 0, 0, 0, 0, 0);

    /**
     * Everything a planner has committed to a shipment, however far down the road it got. Derived
     * from the four committed counters rather than stored beside them - see the record header.
     */
    public long planned() {
        return onTrip + inExecution + delivered + shortfall;
    }

    /** Everything still owed to a customer and not on a shipment. */
    public long unplanned() {
        return readyToPlan + notReady;
    }

    /**
     * Every order that is still work to be done - the denominator the report's "how much of the
     * demand is on a truck" percentage is taken over. Cancelled orders are outside it, for the
     * reason the record header gives.
     */
    public long inputOrders() {
        return planned() + unplanned();
    }
}
