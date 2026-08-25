package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What planning knows about how orders actually ended, asked by whoever is showing an order.
 *
 * <p>The direction is the point. Orders must not read {@code tms.order_delivery}: that table
 * belongs to planning, which owns what a delivery result means and when one may still be
 * corrected. So orders asks a question and planning answers it, exactly as planning asks orders
 * for a {@link PlannableOrder} rather than reading {@code tms.transport_order} itself.
 *
 * <p>Batch by construction, never one call per row. An order list is a page of fifty and the
 * fulfilment column is on every one of them; a per-order port would be an N+1 the first time
 * somebody used it, which is the same discipline {@code CarrierLookupPort.findAllInCompany}
 * exists for.
 */
public interface OrderFulfillmentPort {

    /**
     * The fulfilment state of each requested order, scoped to one company.
     *
     * <p>Every requested id is present in the answer: an order with nothing recorded against it
     * comes back {@link OrderFulfillmentStatus#PENDING} rather than missing, so no caller has to
     * decide what a null means. Ids belonging to another company are simply not found, and so are
     * also {@code PENDING} - this port never becomes a way to discover that an order exists
     * elsewhere.
     */
    Map<UUID, OrderFulfillmentStatus> fulfillmentOf(Collection<UUID> orderIds, UUID companyId);
}
