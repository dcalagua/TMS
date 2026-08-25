package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.WebhookDeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link WebhookDeliveryAttempt}. Append-only: there is no update and no delete
 * here, because the table is a record of calls that were already made.
 */
public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    /**
     * One delivery's attempts in the order they happened. Bounded in practice by
     * {@code tms.integration.webhooks.max-attempts} plus whatever an operator retried by hand, so it
     * is read whole rather than paged.
     */
    List<WebhookDeliveryAttempt> findByWebhookDeliveryIdOrderByAttemptNumberAsc(UUID webhookDeliveryId);
}
