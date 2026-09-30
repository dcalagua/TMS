package com.ebim.tms.orders.domain;

/**
 * Why an order is held (migration V54, {@code ck_order_hold_type}). The type says who has to act -
 * credit, the warehouse, the customer, transport - and the free-text reason says what they have to do.
 */
public enum HoldType {
    COMMERCIAL,
    INVENTORY,
    ADDRESS,
    CUSTOMER,
    TRANSPORT,
    INTEGRATION,
    MANUAL,
    OTHER
}
