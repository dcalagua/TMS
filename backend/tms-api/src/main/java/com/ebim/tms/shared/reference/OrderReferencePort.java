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
}
