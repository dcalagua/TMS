package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;

/**
 * The one way the integration module writes an order without depending on
 * {@code com.ebim.tms.orders} - the counterpart of {@link LocationIntakePort}, and the write-side
 * sibling of {@link OrderPlanningPort}.
 *
 * <p>Implemented by {@code orders.application.OrderIntakeService}, which delegates to
 * {@code OrderService}: the lifecycle rules, the totals resolution and the editability check stay
 * in the module that owns them, so an integration cannot produce an order state the UI could not.
 */
public interface OrderIntakePort {

    /**
     * Creates or updates one order, keyed on {@code (externalSource, externalReference)}, inside
     * the caller's transaction.
     *
     * <p>An order that has already been planned or cancelled is <em>not</em> silently rewritten:
     * the call fails with a conflict naming the status. A sending system that changes an order a
     * planner has already put on a trip is describing a real operational problem, and answering
     * "accepted" would hide it.
     */
    OrderIntakeResult upsert(CompanyScope scope, OrderIntakeCommand command);
}
