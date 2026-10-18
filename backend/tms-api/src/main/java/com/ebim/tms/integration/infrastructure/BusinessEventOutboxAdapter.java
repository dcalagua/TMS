package com.ebim.tms.integration.infrastructure;

import com.ebim.tms.integration.domain.BusinessOutboxEvent;
import com.ebim.tms.shared.reference.BusinessEventPublisher;
import com.ebim.tms.shared.reference.EventFanoutPort;
import com.ebim.tms.shared.reference.PublishedEventNotification;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * {@code integration}'s implementation of {@link BusinessEventPublisher} (V61, ADR-025 section 4): one
 * outbox row, then the same {@link EventFanoutPort} path shipment events take, both inside the
 * caller's transaction. The webhook envelope is {@code WEBHOOKS_V1.md}'s, with the resource type in
 * lower case ({@code "order"}) and the order number as its reference.
 */
@Component
public class BusinessEventOutboxAdapter implements BusinessEventPublisher {

    private final BusinessOutboxEventRepository outboxRepository;
    private final EventFanoutPort eventFanout;

    public BusinessEventOutboxAdapter(BusinessOutboxEventRepository outboxRepository, EventFanoutPort eventFanout) {
        this.outboxRepository = outboxRepository;
        this.eventFanout = eventFanout;
    }

    @Override
    public void publish(BusinessEvent event) {
        BusinessOutboxEvent published = outboxRepository.saveAndFlush(new BusinessOutboxEvent(event.companyId(),
                event.resourceType(), event.resourceId(), event.resourceReference(), event.eventType(),
                event.occurredAt()));
        eventFanout.fanOut(new PublishedEventNotification(
                published.id(),
                event.companyId(),
                event.eventType(),
                event.resourceType().toLowerCase(Locale.ROOT),
                event.resourceId(),
                event.resourceReference(),
                event.occurredAt()));
    }
}
