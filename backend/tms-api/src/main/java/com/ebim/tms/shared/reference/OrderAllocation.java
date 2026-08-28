package com.ebim.tms.shared.reference;

/**
 * How much of one order is on trips and how much is still waiting for one (migration V37).
 *
 * <p>The answer {@code OrderPlanningPort.allocate} and {@code releaseAllocation} give back, and the
 * figure a planning board shows in its "pending" column. {@link #pending()} is derived rather than
 * carried, so it cannot disagree with the two numbers it is the difference of.
 *
 * @param ordered   everything the customer asked for
 * @param allocated the part of it currently committed to trips that have not closed out. Returns to
 *                  zero when the trip carrying it is closed out: what was on the truck is by then
 *                  either delivered or owed again, and neither is "waiting on a shipment"
 */
public record OrderAllocation(OrderAmounts ordered, OrderAmounts allocated) {

    /** What is still to be planned. Never negative: V37's CHECK makes over-allocation impossible. */
    public OrderAmounts pending() {
        return ordered.minus(allocated);
    }

    /** Whether the whole order is on a trip, and so has nothing left for a planner to place. */
    public boolean isFullyAllocated() {
        return allocated.covers(ordered);
    }

    /** Whether any of it is on a trip - the difference between "untouched" and "part-planned". */
    public boolean isPartiallyAllocated() {
        return !allocated.isZero() && !isFullyAllocated();
    }
}
