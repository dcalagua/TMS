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
 * @param planned      orders that are on a shipment ({@code OrderStatus.PLANNED})
 * @param readyToPlan  orders a planner may assign right now ({@code READY_FOR_PLANNING})
 * @param notReady     orders that exist and are not yet plannable ({@code NOT_READY}). Counted as
 *                     unplanned rather than left out: from the point of view of a day's coverage a
 *                     demand nobody has released is still a demand nobody has moved, and hiding it
 *                     would let a company report 100% planned over a backlog it never looked at
 * @param cancelled    orders withdrawn ({@code CANCELLED}). Reported so the figure can be shown
 *                     beside the rest instead of quietly changing what the other three sum to
 */
public record OrderBacklogTotals(long planned, long readyToPlan, long notReady, long cancelled) {

    public static final OrderBacklogTotals EMPTY = new OrderBacklogTotals(0, 0, 0, 0);

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
        return planned + unplanned();
    }
}
