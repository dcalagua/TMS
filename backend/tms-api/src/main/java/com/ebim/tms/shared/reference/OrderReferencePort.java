package com.ebim.tms.shared.reference;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only lookups of orders by the references another system uses, for reconciling what a
 * warehouse dispatched against the plan (ADR-013 section 5). Batched and company-scoped; never
 * returns an order of another company.
 */
public interface OrderReferencePort {

    /** The ids of the company's orders among {@code keys}, matched exactly. Absent keys are unknown. */
    Map<OrderExternalKey, UUID> findIdsByExternalKeys(Collection<OrderExternalKey> keys, UUID companyId);

    /** The lines of each order in {@code orderIds}, by line number. An order with no lines maps to an empty list. */
    Map<UUID, List<OrderLineSnapshot>> linesOf(Set<UUID> orderIds, UUID companyId);

    /**
     * The company's order lines among {@code lineIds}, keyed by line id, in one query. A line of
     * another company, or one that no longer exists, is absent.
     */
    Map<UUID, OrderLineSnapshot> linesByIds(Set<UUID> lineIds, UUID companyId);

    /**
     * What the cost-to-serve report groups an order by (V68, ADR-026 section 7), keyed by order id.
     * An order of another company is absent.
     */
    Map<UUID, OrderCostReference> costReferencesOf(Set<UUID> orderIds, UUID companyId);

    /**
     * @param customerReference free text since V10; there is no customer master
     * @param customerName      free text, the fallback when there is no reference
     */
    record OrderCostReference(UUID orderId, String orderNumber, UUID destinationId, String customerReference,
            String customerName) {
    }
}
