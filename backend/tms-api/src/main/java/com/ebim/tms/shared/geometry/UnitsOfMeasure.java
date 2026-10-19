package com.ebim.tms.shared.geometry;

import java.util.Locale;
import java.util.Set;

/**
 * Which order-line units are a <em>measure</em> rather than a count of units (ADR-022 section 3).
 *
 * <p>A profile describes one unit of a material. Multiplying its unit weight by a quantity
 * expressed in kilograms or cubic metres would invent a figure, so a line in one of these units
 * never takes the profile's unit weight or volume, and load geometry treats it as unmeasured
 * ({@code UOM_NOT_COUNTABLE}). Anything else - UN, CJ, PZA, a customer's own code - is taken as a
 * count, because the order line is where the ERP says how many of the thing it ships.
 */
public final class UnitsOfMeasure {

    private static final Set<String> MEASURE_UNITS = Set.of(
            "KG", "KGM", "G", "GR", "GRM", "T", "TN", "TNE", "TON", "LB", "LBR",
            "M3", "MTQ", "L", "LT", "LTR", "ML", "CM3",
            "M", "MTR", "CM", "MM", "M2", "MTK");

    private UnitsOfMeasure() {}

    /** Whether {@code uom} is a weight, volume, length or area unit rather than a count. */
    public static boolean isMeasure(String uom) {
        return uom != null && MEASURE_UNITS.contains(uom.trim().toUpperCase(Locale.ROOT));
    }
}
