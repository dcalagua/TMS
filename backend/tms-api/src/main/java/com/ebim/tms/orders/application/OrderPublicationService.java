package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.reference.OrderPublicationPort;
import com.ebim.tms.shared.reference.PublishedOrder;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code orders}' implementation of {@link OrderPublicationPort}: what a partner reads back about an
 * order (V61, WAREHOUSE_EXECUTION_V1 7.4). The key, the status and the cancellation - nothing a
 * partner could use to change the order.
 */
@Service
public class OrderPublicationService implements OrderPublicationPort {

    private final TransportOrderRepository transportOrderRepository;

    public OrderPublicationService(TransportOrderRepository transportOrderRepository) {
        this.transportOrderRepository = transportOrderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedOrder> findByOrderNumber(UUID companyId, String orderNumber) {
        return transportOrderRepository.findByCompanyIdAndOrderNumber(companyId, orderNumber)
                .map(OrderPublicationService::toPublished);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PublishedOrder> search(UUID companyId, Set<String> statuses, OffsetDateTime updatedSince,
            PageQuery pageQuery) {
        Set<OrderStatus> wanted = EnumSet.noneOf(OrderStatus.class);
        statuses.forEach(code -> wanted.add(OrderStatus.valueOf(code)));
        PageRequest pageable = PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize());
        Page<TransportOrder> page = updatedSince == null
                ? transportOrderRepository.findPublishable(companyId, wanted, pageable)
                : transportOrderRepository.findPublishable(companyId, wanted, updatedSince, pageable);
        List<PublishedOrder> content = page.getContent().stream().map(OrderPublicationService::toPublished).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    /**
     * {@code cancelledAt} is the last update of a cancelled order: once cancelled it cannot be edited,
     * planned or held into another state, so nothing touches the row after the cancellation.
     */
    private static PublishedOrder toPublished(TransportOrder order) {
        boolean cancelled = order.status() == OrderStatus.CANCELLED;
        return new PublishedOrder(order.id(), order.orderNumber(), order.externalSource(), order.externalReference(),
                order.status().name(), cancelled ? order.updatedAt() : null, cancelled ? order.cancelReason() : null,
                order.updatedAt());
    }
}
