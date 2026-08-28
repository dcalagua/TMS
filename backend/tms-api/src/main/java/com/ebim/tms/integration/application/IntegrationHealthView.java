package com.ebim.tms.integration.application;

import java.time.OffsetDateTime;

/**
 * Whether this company's integrations are working right now (JOB 13).
 *
 * <p>Everything here was already answerable by paging through two lists. That is the problem it
 * solves: an operator asking "is anything broken" should get an answer, not a search. The same
 * argument the control tower's summary makes for shipments.
 *
 * <p><b>Two figures matter more than the rest, and both are about the queue not draining.</b> A
 * webhook queue with a thousand pending rows that is moving is healthy; one with three that have
 * been waiting since Tuesday is not, and a count alone cannot tell those apart - which is why
 * {@code oldestPendingAt} is here beside {@code deliveriesPending}.
 *
 * @param deliveriesPending   outbound deliveries still to be sent, including ones waiting on a
 *                            backoff. Not a problem on its own
 * @param oldestPendingAt     when the oldest of them was created, or null when none are pending.
 *                            <b>Age is the signal</b>: this is what "stuck" looks like
 * @param deliveriesFailed    outbound deliveries that exhausted their retries. These will never be
 *                            sent unless somebody retries them, so this figure is a work queue and
 *                            not a statistic
 * @param deliveriesProcessed how many have landed, for proportion. A hundred failures beside twelve
 *                            successes is a different sentence from a hundred beside a million
 * @param inactiveSubscriptionsWithBacklog subscriptions switched off that still have deliveries
 *                            queued behind them. Deliberately its own figure: this is the one
 *                            failure mode that looks like silence rather than like an error
 * @param requestsSince       the start of the inbound window these counts cover
 * @param requestsSucceeded   inbound requests fully accepted in the window
 * @param requestsPartial     batches where some items were accepted and some were not
 * @param requestsRejected    refused on their merits - the partner's payload was wrong
 * @param requestsFailed      TMS could not process them. <b>The one an operator must look at</b>,
 *                            because a rejection is the partner's problem and a failure is ours
 */
public record IntegrationHealthView(
        long deliveriesPending,
        OffsetDateTime oldestPendingAt,
        long deliveriesFailed,
        long deliveriesProcessed,
        long inactiveSubscriptionsWithBacklog,
        OffsetDateTime requestsSince,
        long requestsSucceeded,
        long requestsPartial,
        long requestsRejected,
        long requestsFailed) {
}
