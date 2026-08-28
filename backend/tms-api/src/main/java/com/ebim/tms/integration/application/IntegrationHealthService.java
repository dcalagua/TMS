package com.ebim.tms.integration.application;

import com.ebim.tms.integration.domain.IntegrationRequestStatus;
import com.ebim.tms.integration.domain.WebhookDeliveryStatus;
import com.ebim.tms.integration.infrastructure.IntegrationRequestRepository;
import com.ebim.tms.integration.infrastructure.WebhookDeliveryRepository;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One answer to "are my integrations working" (JOB 13).
 *
 * <p>Everything here was already reachable by paging through the delivery list and the inbox. That
 * is precisely the problem: an operator opening the screen after a bad night should get an answer
 * rather than a search, and the two lists are where you go <em>after</em> this tells you where to
 * look.
 *
 * <p>Read-only, and computed rather than stored. Nothing about integration health is a fact worth
 * keeping - it is a description of this minute, and a stored copy would be one more thing that can
 * be stale while looking current.
 */
@Service
public class IntegrationHealthService {

    /**
     * How far back the inbound counts look.
     *
     * <p>Windowed rather than lifetime, because "is this healthy" is a question about now. A partner
     * that failed a hundred times last month and has worked all week is working, and a lifetime
     * count would go on saying otherwise forever.
     */
    private static final Duration INBOUND_WINDOW = Duration.ofHours(24);

    private final WebhookDeliveryRepository deliveryRepository;
    private final IntegrationRequestRepository requestRepository;
    private final Clock clock;

    public IntegrationHealthService(WebhookDeliveryRepository deliveryRepository,
            IntegrationRequestRepository requestRepository, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.requestRepository = requestRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public IntegrationHealthView health(CompanyScope scope) {
        Map<WebhookDeliveryStatus, Long> deliveries = new EnumMap<>(WebhookDeliveryStatus.class);
        deliveryRepository.countByStatus(scope.companyId())
                .forEach(row -> deliveries.put(row.getStatus(), row.getDeliveryCount()));

        OffsetDateTime since = OffsetDateTime.now(clock).minus(INBOUND_WINDOW);
        Map<IntegrationRequestStatus, Long> requests = new EnumMap<>(IntegrationRequestStatus.class);
        requestRepository.countByStatusSince(scope.companyId(), since)
                .forEach(row -> requests.put(row.getStatus(), row.getRequestCount()));

        return new IntegrationHealthView(
                deliveries.getOrDefault(WebhookDeliveryStatus.PENDING, 0L),
                // Null when nothing is pending, and null is the good answer here rather than a
                // missing one: there is no oldest waiting delivery because none is waiting.
                deliveryRepository.findOldestCreatedAt(scope.companyId(), WebhookDeliveryStatus.PENDING)
                        .orElse(null),
                deliveries.getOrDefault(WebhookDeliveryStatus.FAILED, 0L),
                deliveries.getOrDefault(WebhookDeliveryStatus.PROCESSED, 0L),
                deliveryRepository.countInactiveSubscriptionsWithBacklog(
                        scope.companyId(), WebhookDeliveryStatus.PENDING),
                since,
                requests.getOrDefault(IntegrationRequestStatus.SUCCEEDED, 0L),
                requests.getOrDefault(IntegrationRequestStatus.PARTIAL, 0L),
                requests.getOrDefault(IntegrationRequestStatus.REJECTED, 0L),
                requests.getOrDefault(IntegrationRequestStatus.FAILED, 0L));
    }
}
