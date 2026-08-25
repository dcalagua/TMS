package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * How much of the demand for these operating days is on a truck
 * ({@code docs/domain/KPIS_REPORTING_V1.md}, section "Orders").
 *
 * <p><b>This is the product's planning invariant, reported.</b>
 * {@code inputOrders = planned + unplanned}, always, by construction rather than by a check -
 * {@code OrderBacklogTotals} is where the identity is defined. Cancelled orders sit outside all
 * three: an order somebody withdrew was never work the plan failed to cover, and counting it would
 * make a company's coverage look worse every time it tidied up its backlog.
 *
 * <p>Ranged over the order's <em>service date</em>, so these days are the same days the shipment
 * figures are about. See {@code OrderPlanningPort.backlogTotals}.
 *
 * <p><b>Null, not zero, when the caller may not be told.</b> The whole record is absent from the
 * report for a caller without {@code orders.order:read} - see {@code KpiService}.
 *
 * @param inputOrders   everything still owed to a customer on these days
 * @param planned       on a shipment
 * @param unplanned     not on a shipment: {@code readyToPlan + notReady}
 * @param readyToPlan   a planner may assign these right now
 * @param notReady      exist and are not yet plannable. Counted as unplanned rather than left out,
 *                      for the reason {@code OrderBacklogTotals} gives: a demand nobody has
 *                      released is still a demand nobody has moved
 * @param cancelled     withdrawn; outside the three figures above
 * @param plannedPercent {@code planned / inputOrders}, or null when the range holds no orders at
 *                      all - which must not read as 0% planned
 */
public record KpiOrdersView(
        long inputOrders,
        long planned,
        long unplanned,
        long readyToPlan,
        long notReady,
        long cancelled,
        BigDecimal plannedPercent) {
}
