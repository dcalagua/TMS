package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.reference.OrderPublicationPort;
import com.ebim.tms.shared.reference.PublishedOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Orders read back by a partner (V61, WAREHOUSE_EXECUTION_V1 7.4) - what a warehouse fetches after an
 * {@code ORDER_CANCELLED} webhook, and the feed it reconciles with. Read-only; scope
 * {@code integration.order:read}.
 */
@Service
public class IntegrationOrderReadService {

    /** The order statuses a partner may filter on, mirrored from {@code orders.domain.OrderStatus}. */
    static final Set<String> STATUSES = Set.of("NOT_READY", "READY_FOR_PLANNING", "PLANNED", "IN_EXECUTION",
            "DELIVERED", "PARTIALLY_DELIVERED", "DELIVERY_FAILED", "CANCELLED");

    private final OrderPublicationPort port;

    public IntegrationOrderReadService(OrderPublicationPort port) {
        this.port = port;
    }

    /** {@code 404} for another company's order exactly as for one that does not exist. */
    public OrderStatusV1 find(IntegrationPrincipal principal, String orderNumber) {
        return port.findByOrderNumber(principal.companyId(), orderNumber.trim())
                .map(IntegrationOrderReadService::toV1)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
    }

    /** Defaults to {@code CANCELLED}, the reason the feed exists - as the shipment feed defaults to {@code CONFIRMED}. */
    public PageResponse<OrderStatusV1> search(IntegrationPrincipal principal, Set<String> statuses,
            OffsetDateTime updatedSince, PageQuery pageQuery) {
        Set<String> effective = requireValidStatuses(statuses);
        PageResponse<PublishedOrder> page = port.search(principal.companyId(), effective, updatedSince, pageQuery);
        List<OrderStatusV1> content = page.content().stream().map(IntegrationOrderReadService::toV1).toList();
        return new PageResponse<>(content, page.page(), page.size(), page.totalElements());
    }

    private static Set<String> requireValidStatuses(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Set.of("CANCELLED");
        }
        for (String status : requested) {
            if (!STATUSES.contains(status)) {
                throw new InvalidRequestException("status must be one of " + STATUSES + "; was '" + status + "'.");
            }
        }
        return requested;
    }

    private static OrderStatusV1 toV1(PublishedOrder order) {
        return new OrderStatusV1(order.orderNumber(), order.externalSource(), order.externalReference(), order.status(),
                order.cancelledAt(), order.cancelReason(), order.updatedAt());
    }
}
