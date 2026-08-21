package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.application.WebhookDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer behind the webhook dispatcher (migration V35), and the product's only scheduled task.
 *
 * <h2>What a scheduled task may assume</h2>
 *
 * <p>Nothing about a tenant. There is no security context on this thread, so there is no
 * {@code CompanyScope}, which means - see {@code TenantScopedDataSource} - the connection stays on
 * the owner role and Row Level Security does not filter anything. That is deliberate and is what
 * lets one worker drain every company's queue, but it also means the usual safety net is absent:
 * every query underneath this must carry its own predicates, and every write must be made against
 * the company id carried on the row it came from.
 *
 * <h2>fixedDelay, not fixedRate</h2>
 *
 * <p>{@code fixedRate} would start a new pass on the clock whether or not the previous one had
 * finished, so a slow batch would overlap itself and the overlap would grow. {@code fixedDelay}
 * measures from the end of the previous pass, which makes the dispatcher self-limiting: a batch that
 * takes two minutes because every endpoint is timing out simply runs less often.
 *
 * <p>The pass itself is bounded by {@code batch-size}, so "there are ten thousand due deliveries"
 * produces many short passes rather than one enormous one.
 *
 * <h2>Registered unconditionally</h2>
 *
 * <p>Deliberately not behind {@code @ConditionalOnProperty} on the signing key. That annotation
 * treats a property that is <em>present but empty</em> as a match, which is exactly what
 * {@code secret-key: ${TMS_WEBHOOK_SECRET_KEY:}} produces in a deployment that has not set the
 * variable - so the condition would read as "only when configured" and mean "always". A guard that
 * lies is worse than no guard, so the bean is registered always and
 * {@code WebhookDispatchService.dispatchDue} returns immediately when the feature is off. The cost
 * is one no-op method call every poll interval.
 */
@Component
public class WebhookDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchScheduler.class);

    private final WebhookDispatchService dispatchService;

    public WebhookDispatchScheduler(WebhookDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /**
     * The initial delay keeps the first pass out of the way of startup: Flyway, the connection pool
     * and the JWKS fetch all want the first seconds of a boot more than a webhook does.
     */
    @Scheduled(
            fixedDelayString = "${tms.integration.webhooks.poll-interval:15s}",
            initialDelayString = "${tms.integration.webhooks.poll-interval:15s}")
    public void dispatchDue() {
        try {
            dispatchService.dispatchDue();
        } catch (RuntimeException failure) {
            // A pass that throws must not stop the schedule. Spring would keep calling a fixedDelay
            // task anyway, but it would log the same stack trace every fifteen seconds at ERROR;
            // catching it here means one line per pass and a dispatcher that keeps working through a
            // transient database problem.
            log.error("A webhook dispatch pass failed; the next pass will retry the same deliveries", failure);
        }
    }
}
