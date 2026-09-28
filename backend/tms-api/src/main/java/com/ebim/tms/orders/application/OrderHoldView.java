package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.HoldSource;
import com.ebim.tms.orders.domain.HoldType;
import com.ebim.tms.orders.domain.OrderHold;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One hold of an order, active or lifted (migration V54). */
public record OrderHoldView(
        UUID id,
        UUID orderId,
        HoldType holdType,
        String reasonCode,
        String reason,
        HoldSource source,
        boolean blocking,
        boolean active,
        UUID createdBy,
        UUID createdByClient,
        OffsetDateTime createdAt,
        OffsetDateTime releasedAt,
        UUID releasedBy,
        UUID releasedByClient,
        String releaseReason,
        long version) {

    public static OrderHoldView from(OrderHold hold) {
        return new OrderHoldView(hold.id(), hold.orderId(), hold.holdType(), hold.reasonCode(), hold.reason(),
                hold.source(), hold.blocking(), hold.isActive(), hold.createdBy(), hold.createdByClient(),
                hold.createdAt(), hold.releasedAt(), hold.releasedBy(), hold.releasedByClient(), hold.releaseReason(),
                hold.version());
    }
}
