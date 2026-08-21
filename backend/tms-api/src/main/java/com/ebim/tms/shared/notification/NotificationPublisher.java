package com.ebim.tms.shared.notification;

import com.ebim.tms.shared.security.CompanyScope;
import java.time.OffsetDateTime;

/**
 * The one way a business module other than {@code notification} may raise an in-app alert, without
 * depending on {@code com.ebim.tms.notification} directly.
 *
 * <p>{@code notification} owns {@code tms.notification}; {@code ModuleBoundaryTest} forbids any
 * other business module from importing it. This port is the "explicit API" that rule's message
 * points to - the same shape {@code com.ebim.tms.shared.audit.AuditRecorder} established for the
 * audit trail. {@code com.ebim.tms.notification.application.NotificationRecorder} is the only
 * implementation.
 *
 * <p><b>Every call happens inside the caller's own transaction</b> (no {@code REQUIRES_NEW}), for
 * the reason {@code AuditRecorder} gives: an alert describes something that happened, so if the
 * change it describes rolls back, the alert announcing it must roll back too. A bell reporting a
 * dispatch that never committed would send a dispatcher to a shipment still sitting in the yard.
 *
 * <p><b>And no call may fail the transaction it is in.</b> That is the harder half of the contract
 * and it is why {@link #raise} is specified as idempotent rather than merely documented as such:
 * an alert is a by-product, and a duplicate one must never be the reason a driver assignment or a
 * delivery record is refused. The implementation inserts with {@code ON CONFLICT DO NOTHING} so
 * that a re-raise is resolved inside the statement instead of surfacing as a constraint violation
 * that would poison the whole transaction.
 *
 * <p><b>No actor.</b> Unlike {@code AuditRecorder}, nothing here requires an authenticated person:
 * an alert says a thing happened, not that somebody did it, and the deliveries and tender responses
 * that raise them legitimately arrive from a partner's system with no {@code app_user} at all.
 */
public interface NotificationPublisher {

    /**
     * Raises one alert for the given tenant, or does nothing if that exact alert is already on the
     * board.
     *
     * <p>Idempotent on {@code (scope.companyId(), request.dedupeKey())}: calling it twice for one
     * fact leaves one row, and the second call neither updates the first nor un-reads it. That
     * matters beyond retries - a lapsed tender is re-resolved by whichever request touches the trip
     * next, and a dispatcher who has already acknowledged the expiry should not watch it come back.
     *
     * @param scope the tenant the fact belongs to - stamped onto the alert, not derived from the
     *     actor, so it is scoped exactly like the write it describes
     */
    void raise(CompanyScope scope, NotificationRequest request);

    /**
     * Marks the alert with this dedupe key as no longer outstanding, if it exists and is not
     * already resolved.
     *
     * <p>For a condition that closes on its own: a trip exception resolved, a shortfall delivery
     * corrected to a full one. Not a delete and not a mark-as-read - the alert stays on the board with its
     * {@code resolvedAt} set, because "this was raised and then dealt with" is a different and more
     * useful statement than "this never happened".
     *
     * <p>A key that matches nothing is a no-op rather than an error: the alert may have been raised
     * before this build, or by a condition whose type does not resolve, and failing a resolution
     * over a missing bell entry would be the by-product taking down the business change again.
     *
     * @param resolvedAt when the condition closed - the operator's own time, matching what the
     *     underlying row recorded
     */
    void resolve(CompanyScope scope, String dedupeKey, OffsetDateTime resolvedAt);
}
