package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.Eligibility;
import com.ebim.tms.orders.domain.OrderPriority;
import com.ebim.tms.orders.domain.OrderStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * The Scheduling and Release board's filters (ADR-014 section 8). The first group narrows in SQL;
 * {@code routeCode}, {@code eligibility} and {@code frequency} are derived on read and narrow the
 * evaluated set.
 *
 * @param status   one lifecycle state; absent means the two the board is about - {@code NOT_READY}
 *                 (to release) and {@code READY_FOR_PLANNING} (released, not yet on a trip)
 * @param customer matched, case-insensitively, against the customer name and reference
 * @param hasHold  true: only orders with an active hold; false: only orders without one
 * @param routeCode the resolved route's code, or {@code NONE} for orders that resolve to no route
 * @param frequency a destination or route frequency code
 */
public record SchedulingFilter(
        UUID originId,
        String routeCode,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateTo,
        String customer,
        OrderPriority priority,
        Eligibility eligibility,
        Boolean hasHold,
        OrderStatus status,
        String frequency,
        String orderNumber) {

    /** The pseudo route code for "no route resolved" - NOT_CONFIGURED, NOT_FOUND or AMBIGUOUS. */
    public static final String NO_ROUTE = "NONE";

    boolean hasDerivedFilter() {
        return notBlank(routeCode) || eligibility != null || notBlank(frequency);
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
