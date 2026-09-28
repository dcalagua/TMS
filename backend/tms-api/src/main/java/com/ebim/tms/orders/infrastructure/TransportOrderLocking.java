package com.ebim.tms.orders.infrastructure;

import java.util.Collection;
import java.util.UUID;

/**
 * Row locks for the execution transitions that span several orders at once (a trip's departure
 * and close-out). A Spring Data fragment of {@link TransportOrderRepository}, because it needs the
 * persistence context itself and nothing outside {@code infrastructure} may touch that.
 */
public interface TransportOrderLocking {

    /**
     * Locks {@code ids} in id order (so overlapping callers cannot deadlock) and brings any of them
     * this transaction already loaded up to date with what the lock now guarantees.
     *
     * <p>The refresh is the half a plain {@code SELECT ... FOR UPDATE} query does not do: Hibernate
     * never overwrites an entity it already manages with a query's result. A departure that read
     * an order before it waited for the lock would otherwise write that order back with the version
     * it read, and lose to the planner it had just waited for - a 409 on a legal departure.
     */
    void lockAndRefresh(Collection<UUID> ids, UUID companyId);
}
