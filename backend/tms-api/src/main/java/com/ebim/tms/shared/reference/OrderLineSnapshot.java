package com.ebim.tms.shared.reference;

import java.math.BigDecimal;

/**
 * One line of an order as TMS holds it, for comparing against what a warehouse dispatched.
 *
 * @param stacks            the line's stacks (V62), or null when the line was not measured
 * @param effectiveVolumeM3 the cubic metres those stacks occupy (V62), or null when not measured
 */
public record OrderLineSnapshot(int lineNumber, String materialCode, BigDecimal quantity, String uom,
        Integer stacks, BigDecimal effectiveVolumeM3) {

    /** Without geometry: a line nobody measured, or a caller that does not need it. */
    public OrderLineSnapshot(int lineNumber, String materialCode, BigDecimal quantity, String uom) {
        this(lineNumber, materialCode, quantity, uom, null, null);
    }
}
