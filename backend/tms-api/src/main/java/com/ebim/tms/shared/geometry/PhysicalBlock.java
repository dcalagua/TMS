package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The physical description of one unit of a material that load geometry needs (ADR-022 sections 1
 * and 2): its measures in loading orientation, how it stacks or nests, and what it bears.
 *
 * <p>One type for the warehouse profile and for the snapshot on an order line, because the two
 * must obey the same rules - the line's columns repeat the profile's CHECKs - and a block copied
 * from one to the other must never become invalid on the way. Unit weight and unit volume are
 * deliberately not here: they already live on the order line (V10) with their own precedence
 * rule, and the block is what an ERP line replaces <em>as a whole</em> (D-02).
 *
 * @param orientationLocked whether the unit may be turned 90 degrees on the floor. Travels with a
 *     block; on its own it describes nothing
 */
public record PhysicalBlock(
        BigDecimal lengthM,
        BigDecimal widthM,
        BigDecimal heightM,
        StackMode stackMode,
        Integer stackMaxQuantity,
        BigDecimal nestIncrementM,
        BigDecimal maxTopLoadKg,
        boolean orientationLocked) {

    /** numeric(8,4): the largest measure or nest increment a column can hold. */
    private static final BigDecimal MAX_METRES = new BigDecimal("9999.9999");

    /** numeric(12,3). */
    private static final BigDecimal MAX_TOP_LOAD_KG = new BigDecimal("999999999.999");

    /**
     * Whether nothing physical is declared. The orientation flag alone does not count: it is a
     * boolean with a default, and a block made only of it would describe no unit at all.
     */
    public boolean isEmpty() {
        return lengthM == null && widthM == null && heightM == null && stackMode == null
                && stackMaxQuantity == null && nestIncrementM == null && maxTopLoadKg == null;
    }

    /**
     * Everything wrong with this block, as messages naming the field the way the API spells it.
     * Empty when the block is valid. The same rules as V59's CHECKs, stated once so a payload is
     * refused with a sentence instead of a constraint name.
     */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        positiveMetres(found, "lengthM", lengthM);
        positiveMetres(found, "widthM", widthM);
        positiveMetres(found, "heightM", heightM);
        if (stackMaxQuantity != null && stackMaxQuantity <= 0) {
            found.add("stackMaxQuantity must be greater than zero");
        }
        if (stackMode == StackMode.NESTABLE && nestIncrementM == null) {
            found.add("nestIncrementM is required when stackMode is NESTABLE");
        }
        if (stackMode != StackMode.NESTABLE && nestIncrementM != null) {
            found.add("nestIncrementM is only allowed when stackMode is NESTABLE");
        }
        positiveMetres(found, "nestIncrementM", nestIncrementM);
        if (maxTopLoadKg != null && (maxTopLoadKg.signum() <= 0 || maxTopLoadKg.compareTo(MAX_TOP_LOAD_KG) > 0)) {
            found.add("maxTopLoadKg must be greater than zero and at most " + MAX_TOP_LOAD_KG.toPlainString());
        }
        return List.copyOf(found);
    }

    private static void positiveMetres(List<String> found, String field, BigDecimal value) {
        if (value != null && (value.signum() <= 0 || value.compareTo(MAX_METRES) > 0)) {
            found.add(field + " must be greater than zero and at most " + MAX_METRES.toPlainString() + " m");
        }
    }
}
