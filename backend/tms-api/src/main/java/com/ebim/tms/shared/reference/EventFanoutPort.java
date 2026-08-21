package com.ebim.tms.shared.reference;

/**
 * Turns one published fact into the deliveries owed for it (migration V35).
 *
 * <p>Implemented by {@code integration}, called by {@code planning} - the opposite direction to
 * {@link ShipmentPublicationPort}, which {@code planning} implements for {@code integration} to
 * read. Both exist because {@code ModuleBoundaryTest} forbids the two modules from naming each
 * other, and because the outbox must not learn what a webhook is.
 *
 * <h2>What the implementation may and may not do</h2>
 *
 * <p>{@link #fanOut} runs <em>inside</em> the caller's business transaction, on purpose: the
 * deliveries owed for a confirmation must roll back with the confirmation, or a partner is told
 * about a shipment TMS does not believe was confirmed. That is the same guarantee the outbox row
 * itself has, and it is only affordable because the implementation does two indexed inserts and
 * nothing else.
 *
 * <p>It therefore must not make a network call, must not sleep, and must not swallow a database
 * failure - if the deliveries cannot be written, the business fact must not commit either.
 * Everything slow happens afterwards, in the dispatcher, outside any business transaction.
 */
public interface EventFanoutPort {

    /**
     * Records what is owed for {@code notification}, if anything. A company with no subscription
     * interested in the event type does no work beyond one indexed read, which is why the caller
     * can afford to call this unconditionally.
     */
    void fanOut(PublishedEventNotification notification);
}
