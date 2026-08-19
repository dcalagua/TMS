package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composes the optional list filters for {@link TransportOrderRepository}. See {@code RouteSpecifications}. */
public final class TransportOrderSpecifications {

    private TransportOrderSpecifications() {}

    public static Specification<TransportOrder> matching(UUID companyId, String orderNumber, UUID originId,
            UUID destinationId, LocalDate serviceDateFrom, LocalDate serviceDateTo, OrderStatus status,
            OrderPriority priority) {
        Specification<TransportOrder> specification = (root, query, cb) -> cb.equal(root.get("companyId"), companyId);

        if (orderNumber != null && !orderNumber.isBlank()) {
            String pattern = "%" + orderNumber.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("orderNumber")), pattern));
        }
        if (originId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("originId"), originId));
        }
        if (destinationId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("destinationId"), destinationId));
        }
        if (serviceDateFrom != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("serviceDate"), serviceDateFrom));
        }
        if (serviceDateTo != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("serviceDate"), serviceDateTo));
        }
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (priority != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("priority"), priority));
        }
        return specification;
    }
}
