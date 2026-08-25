package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.WebhookEventType;
import com.ebim.tms.integration.domain.WebhookSubscription;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link WebhookSubscription}. Every finder carries its company predicate. */
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    Page<WebhookSubscription> findByCompanyId(UUID companyId, Pageable pageable);

    Optional<WebhookSubscription> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);

    boolean existsByCompanyIdAndNameAndIdNot(UUID companyId, String name, UUID id);

    /**
     * The fan-out query, and the one on the hot path: it runs inside the transaction that confirms a
     * trip, so it has to be a single indexed read and nothing more.
     *
     * <p>Joins the child table rather than loading each subscription's event set, so a company with
     * ten endpoints of which one wants this event type reads one row instead of ten plus their
     * children. Inactive subscriptions are excluded here rather than filtered afterwards: a
     * suspended endpoint should accumulate no deliveries at all, or reactivating it would release a
     * backlog of events from an outage the customer has already dealt with by other means.
     */
    @Query("SELECT DISTINCT s FROM WebhookSubscription s JOIN s.eventTypes e "
            + "WHERE s.companyId = :companyId AND s.active = true AND e.eventType = :eventType")
    List<WebhookSubscription> findActiveForEvent(
            @Param("companyId") UUID companyId, @Param("eventType") String eventType);

    default List<WebhookSubscription> findActiveForEvent(UUID companyId, WebhookEventType eventType) {
        return findActiveForEvent(companyId, eventType.name());
    }
}
