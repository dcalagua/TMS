package com.ebim.tms.orders.domain;

/**
 * A small, fixed priority vocabulary, mirroring the {@code ck_transport_order_priority} check
 * constraint in migration V10 - the same "one flat catalogue, no administration screen" judgment
 * {@link com.ebim.tms.masterdata.domain.OriginType} already made for a small fixed set of values.
 */
public enum OrderPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
