package com.ebim.tms.orders.domain;

/**
 * Where an order line's physical block came from (migration V59, ADR-022 section 2). Mirrored by
 * {@code ck_transport_order_line_physical_source}.
 */
public enum PhysicalSource {

    /** Copied from the warehouse's material profile, at the version the line records. */
    PROFILE,

    /** Sent by the ERP on the line itself. Wins over the profile as a whole block (D-02). */
    ORDER_LINE
}
