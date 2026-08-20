package com.ebim.tms.orders.domain;

/**
 * Which input produced an order's effective totals. Mirrors {@code ck_transport_order_totals_source}
 * (migration V17) and is decided exclusively by {@link OrderTotals#resolve}.
 *
 * <p>See {@code docs/domain/ORDER_TOTALS_V1.md} for the rule this enum records the outcome of.
 */
public enum TotalsSource {

    /** Summed from {@code transport_order_line}. The order has at least one line. */
    CALCULATED,

    /** Copied from the declared figures because the order has no lines at all. */
    DECLARED
}
