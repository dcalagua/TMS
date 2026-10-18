package com.ebim.tms.shared.geometry;

/**
 * Whether load geometry could describe an order line (ADR-022 section 3), mirrored by
 * {@code ck_transport_order_line_geometry_status} (V62). A line with no physical block at all has
 * no status ({@code null}): there was nothing to compute, which is different from a block that
 * could not be used.
 */
public enum GeometryStatus {

    /** Stacks, stack height, effective volume and floor area were computed from the block. */
    MEASURED,

    /**
     * A block exists but something it needs is missing. Nothing is estimated (EWM rule 199): the
     * line keeps its declared volume and is counted in the order's unmeasured lines.
     */
    UNMEASURED,

    /**
     * One unit is taller than the usable height it was computed for. The line is still described
     * as one unit per stack - which is what it would be in a vehicle tall enough - so the order's
     * stack height says exactly which vehicles can take it. Never "fits with a warning".
     */
    DOES_NOT_FIT
}
