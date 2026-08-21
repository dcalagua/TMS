package com.ebim.tms.orders.domain;

import java.math.BigDecimal;

/**
 * What the sender of an order - an integration, or an operator typing a header-only order -
 * asserts its weight, volume and pallet count to be, independently of any line detail.
 *
 * <p>Each field is nullable and the distinction matters: {@code null} means "not stated",
 * {@link BigDecimal#ZERO} means "stated as zero". {@link OrderTotals#resolve} treats the two
 * differently, so this record must never normalise a null into a zero.
 */
public record DeclaredTotals(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {

    private static final DeclaredTotals NONE = new DeclaredTotals(null, null, null);

    /** Nothing was declared - the ordinary case for a manually entered order with lines. */
    public static DeclaredTotals none() {
        return NONE;
    }

    /** True when the sender stated nothing at all, which is what makes a cross-check pointless. */
    public boolean isEmpty() {
        return weightKg == null && volumeM3 == null && pallets == null;
    }
}
