package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link WebhookDelivery}.
 *
 * <p>The administration finders carry a company predicate, as every finder in TMS does.
 * {@link #claimDue} is the exception and the reason is stated where it is defined.
 */
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByIdAndCompanyId(UUID id, UUID companyId);

    Page<WebhookDelivery> findByCompanyId(UUID companyId, Pageable pageable);

    Page<WebhookDelivery> findByCompanyIdAndSubscriptionId(UUID companyId, UUID subscriptionId, Pageable pageable);

    Page<WebhookDelivery> findByCompanyIdAndStatus(UUID companyId, WebhookDeliveryStatus status, Pageable pageable);

    Page<WebhookDelivery> findByCompanyIdAndSubscriptionIdAndStatus(
            UUID companyId, UUID subscriptionId, WebhookDeliveryStatus status, Pageable pageable);

    /**
     * How many deliveries are in each state right now (JOB 13).
     *
     * <p>Grouped in SQL rather than counted three times, and read for the health summary rather
     * than for a page: an operator asking "is anything broken" should not have to page through
     * deliveries to find out.
     */
    @Query("SELECT d.status AS status, COUNT(d) AS deliveryCount FROM WebhookDelivery d "
            + "WHERE d.companyId = :companyId GROUP BY d.status")
    List<DeliveryStatusCount> countByStatus(@Param("companyId") UUID companyId);

    /** One row of {@link #countByStatus}. */
    interface DeliveryStatusCount {
        WebhookDeliveryStatus getStatus();

        long getDeliveryCount();
    }

    /**
     * The oldest delivery still waiting, if any.
     *
     * <p><b>The signal that matters most.</b> A queue with a thousand pending rows that is draining
     * is healthy; a queue with three that have been waiting since Tuesday is not, and a plain count
     * cannot tell those apart. What this exposes is age, which is what "the integration is stuck"
     * actually looks like.
     */
    @Query("SELECT MIN(d.createdAt) FROM WebhookDelivery d "
            + "WHERE d.companyId = :companyId AND d.status = :status")
    Optional<OffsetDateTime> findOldestCreatedAt(@Param("companyId") UUID companyId,
            @Param("status") WebhookDeliveryStatus status);

    /**
     * Subscriptions that are switched off and still have deliveries queued behind them (JOB 13).
     *
     * <p><b>The failure mode that looks like silence.</b> Deactivating a subscription stops
     * deliveries being sent and discards nothing - events keep queueing, exactly as the deactivate
     * endpoint documents - so a partner switched off "for an hour" during an incident and never
     * switched back on produces no errors at all. Nothing else on this screen would show it.
     */
    @Query("SELECT COUNT(DISTINCT d.subscription.id) FROM WebhookDelivery d "
            + "WHERE d.companyId = :companyId AND d.subscription.active = false AND d.status = :status")
    long countInactiveSubscriptionsWithBacklog(@Param("companyId") UUID companyId,
            @Param("status") WebhookDeliveryStatus status);

    /**
     * The dispatcher's queue read: the deliveries that are due, locked so that no other instance
     * takes the same ones.
     *
     * <h2>Why there is no company predicate</h2>
     *
     * <p>This is the one query in the module that is deliberately cross-tenant. It runs on a
     * background thread with no security context, which - see {@code TenantScopedDataSource} - means
     * the connection is never switched to {@code tms_app} and Row Level Security does not apply to
     * it. That is what lets a single worker drain every company's queue instead of needing one
     * scheduled job per tenant. The tenant of each row travels with it, and everything the
     * dispatcher writes afterwards is written against that row's own {@code company_id}.
     *
     * <h2>SKIP LOCKED</h2>
     *
     * <p>{@code -2} is Hibernate's timeout value for {@code SKIP LOCKED}. Two application instances
     * running this at the same second therefore take <em>different</em> rows instead of one blocking
     * on the other, which is what makes the dispatcher safe to run on every node without a leader
     * election or an advisory lock. A row held by an instance that then dies is released by the
     * database when its connection closes - there is no stuck "sending" state to clean up, which is
     * exactly why {@code WebhookDeliveryStatus} has no such value.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT d FROM WebhookDelivery d WHERE d.status = :status AND d.nextAttemptAt <= :now "
            + "ORDER BY d.nextAttemptAt ASC, d.id ASC")
    List<WebhookDelivery> claimDue(
            @Param("status") WebhookDeliveryStatus status, @Param("now") OffsetDateTime now, Pageable pageable);

    /** {@link #claimDue} with the only status it is ever called with. */
    default List<WebhookDelivery> claimDue(OffsetDateTime now, Pageable pageable) {
        return claimDue(WebhookDeliveryStatus.PENDING, now, pageable);
    }

    long countByCompanyIdAndStatus(UUID companyId, WebhookDeliveryStatus status);
}
