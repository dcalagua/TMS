package com.ebim.tms.orders.domain;

/**
 * Who placed a hold (migration V54, {@code ck_order_hold_source}): a person in the TMS UI, or a
 * credential over the M2M API. The actor columns must agree ({@code ck_order_hold_actor}).
 */
public enum HoldSource {
    OPERATOR,
    INTEGRATION
}
