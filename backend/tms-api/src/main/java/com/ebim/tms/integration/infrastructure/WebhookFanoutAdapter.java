package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.application.WebhookEventV1;
import com.ebim.tms.integration.application.WebhookProperties;
import com.ebim.tms.integration.domain.WebhookDelivery;
import com.ebim.tms.integration.domain.WebhookEventType;
import com.ebim.tms.integration.domain.WebhookSubscription;
import com.ebim.tms.shared.reference.EventFanoutPort;
import com.ebim.tms.shared.reference.PublishedEventNotification;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code integration}'s implementation of {@link EventFanoutPort} (migration V35): turns one
 * published fact into the {@code PENDING} deliveries owed for it.
 *
 * <h2>What this does inside somebody else's transaction</h2>
 *
 * <p>One indexed read of {@code webhook_subscription} joined to its event child, one JSON
 * serialisation, and one insert per interested endpoint. That is the entire cost added to
 * confirming a trip, and it is why the fan-out can be transactional at all - see
 * {@link EventFanoutPort} for why it has to be.
 *
 * <p>A company with no subscription for the event type does the read and stops. That is the
 * overwhelmingly common case and it is deliberately not optimised further with a cache: a cached
 * "this company has no webhooks" is exactly the answer that would be wrong for the first ten minutes
 * after somebody creates their first one, which is the ten minutes they are watching.
 *
 * <h2>Failures</h2>
 *
 * <p>Nothing here is caught. A database failure writing a delivery rolls back the business
 * transaction with it, which is the guarantee the whole design rests on: TMS never believes a
 * shipment was confirmed while having silently failed to record who had to be told.
 */
@Component
public class WebhookFanoutAdapter implements EventFanoutPort {

    private static final Logger log = LoggerFactory.getLogger(WebhookFanoutAdapter.class);

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookFanoutAdapter(WebhookSubscriptionRepository subscriptionRepository,
            WebhookDeliveryRepository deliveryRepository, WebhookProperties properties, ObjectMapper objectMapper,
            Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void fanOut(PublishedEventNotification notification) {
        if (!properties.configured()) {
            // No key means no subscription can exist, so there is nothing to read. Checked before
            // the query rather than relying on an empty result, so a deployment that does not use
            // webhooks adds no cost at all to its confirmation path.
            return;
        }
        Optional<WebhookEventType> eventType = WebhookEventType.byName(notification.eventType());
        if (eventType.isEmpty()) {
            // The outbox published something this module's vocabulary does not have a name for.
            // WebhookEventTypeTest exists to make that a failing build rather than a surprise, so
            // reaching this line means the two drifted anyway: log it and deliver nothing, because
            // holding up a confirmation over a webhook vocabulary gap would be the worse outcome.
            log.warn("No webhook event type matches published event {}; nothing was fanned out",
                    notification.eventType());
            return;
        }

        List<WebhookSubscription> interested =
                subscriptionRepository.findActiveForEvent(notification.companyId(), eventType.get());
        if (interested.isEmpty()) {
            return;
        }

        String payload = render(notification);
        OffsetDateTime dueNow = OffsetDateTime.now(clock);
        for (WebhookSubscription subscription : interested) {
            deliveryRepository.save(new WebhookDelivery(notification.companyId(), subscription, notification.id(),
                    eventType.get(), notification.occurredAt(), payload, dueNow));
        }
    }

    /**
     * The body, rendered once for every subscription of this company. Two endpoints subscribed to
     * the same event receive byte-identical bodies, which is what makes a support conversation
     * comparing them possible.
     */
    private String render(PublishedEventNotification notification) {
        WebhookEventV1 event = new WebhookEventV1(
                WebhookEventV1.API_VERSION,
                notification.id(),
                notification.eventType(),
                notification.occurredAt(),
                notification.companyId(),
                new WebhookEventV1.Resource(notification.resourceType(), notification.resourceId(),
                        notification.resourceReference()));
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException notSerialisable) {
            // A record of strings, ids and a timestamp. A failure here is a wiring bug, and failing
            // the business transaction is the right answer to one.
            throw new IllegalStateException("a webhook payload could not be serialised", notSerialisable);
        }
    }
}
