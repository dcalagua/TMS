package com.ebim.tms.orders.infrastructure;

import com.ebim.tms.orders.domain.OrderHold;
import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import com.ebim.tms.orders.domain.TransportOrder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filters ADR-014 adds over {@link TransportOrder}: the Scheduling and Release board's stored
 * filters, and "no active blocking hold" - the planning-candidate rule (section 7) applied as a
 * {@code NOT EXISTS} in the same statement as the rest of the search, never as a second query per row.
 */
public final class OrderSchedulingSpecifications {

    private OrderSchedulingSpecifications() {}

    public static Specification<TransportOrder> board(UUID companyId, String orderNumber, UUID originId,
            LocalDate serviceDateFrom, LocalDate serviceDateTo, Collection<OrderStatus> statuses,
            OrderPriority priority, String customer, Boolean hasHold) {
        Specification<TransportOrder> specification = TransportOrderSpecifications.matching(companyId, orderNumber,
                originId, null, serviceDateFrom, serviceDateTo, null, priority);
        specification = specification.and((root, query, cb) -> root.get("status").in(statuses));
        if (customer != null && !customer.isBlank()) {
            String pattern = "%" + customer.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerName")), pattern),
                    cb.like(cb.lower(root.get("customerReference")), pattern)));
        }
        if (hasHold != null) {
            specification = specification.and(hasHold ? activeHold(companyId, false) : noActiveHold(companyId, false));
        }
        return specification;
    }

    /** The planning-candidate rule's second half: no active hold with {@code blocking = true}. */
    public static Specification<TransportOrder> noActiveBlockingHold(UUID companyId) {
        return noActiveHold(companyId, true);
    }

    private static Specification<TransportOrder> activeHold(UUID companyId, boolean blockingOnly) {
        return (root, query, cb) -> cb.exists(activeHoldSubquery(root, query, cb, companyId, blockingOnly));
    }

    private static Specification<TransportOrder> noActiveHold(UUID companyId, boolean blockingOnly) {
        return (root, query, cb) -> cb.not(cb.exists(activeHoldSubquery(root, query, cb, companyId, blockingOnly)));
    }

    private static Subquery<UUID> activeHoldSubquery(Root<TransportOrder> order,
            CriteriaQuery<?> query, CriteriaBuilder cb,
            UUID companyId, boolean blockingOnly) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<OrderHold> hold = subquery.from(OrderHold.class);
        var predicate = cb.and(
                cb.equal(hold.get("companyId"), companyId),
                cb.equal(hold.get("orderId"), order.get("id")),
                cb.isNull(hold.get("releasedAt")));
        if (blockingOnly) {
            predicate = cb.and(predicate, cb.isTrue(hold.get("blocking")));
        }
        return subquery.select(hold.get("id")).where(predicate);
    }
}
