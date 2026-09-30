package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.TransportOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The implementation Spring Data picks up for {@link TransportOrderLocking} by its name. */
class TransportOrderLockingImpl implements TransportOrderLocking {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void lockAndRefresh(Collection<UUID> ids, UUID companyId) {
        if (ids.isEmpty()) {
            return;
        }
        // Ids only, never entities: Hibernate refuses to hydrate a row whose version differs from
        // an instance it already holds, which is exactly the case this method exists for.
        @SuppressWarnings("unchecked")
        List<UUID> locked = entityManager.createNativeQuery(
                        "SELECT id FROM tms.transport_order WHERE id IN (:ids) AND company_id = :companyId"
                                + " ORDER BY id FOR UPDATE", UUID.class)
                .setParameter("ids", ids)
                .setParameter("companyId", companyId)
                .getResultList();
        // The rows are ours now. Anything this transaction loaded before waiting for them may be
        // stale; re-read it under the lock we already hold.
        for (UUID id : locked) {
            TransportOrder order = entityManager.find(TransportOrder.class, id);
            if (order != null) {
                entityManager.refresh(order);
            }
        }
    }
}
