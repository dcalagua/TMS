package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Load geometry by footprint (ADR-022 section 3, D-05): how many stacks a line needs, how tall
 * they are, how much floor they cover and how many cubic metres they really occupy.
 *
 * <p>Pure: no Spring, no clock, no repository. Orders compute a line's figures with it when the
 * line is written, planning re-stacks with it for each vehicle (OrderGeometry), and rates read what it produced - one
 * arithmetic for all three, which is why it lives in {@code shared}.
 *
 * <pre>
 * heightCap = STACKABLE: floor(H / h)    NESTABLE: floor((H - h) / dh) + 1    NONE: 1
 * weightCap = maxTopLoadKg ? floor(maxTopLoadKg / unitWeightKg) + 1 : unbounded
 * k         = min(stackMaxQuantity ?: unbounded, heightCap, weightCap)
 * stacks    = ceil(quantity / k)
 * height(n) = STACKABLE: n*h    NESTABLE: h + (n-1)*dh    NONE: h
 * effVolume = (stacks-1) * L*W*height(k) + L*W*height(last)
 * floorArea = stacks * L * W
 * </pre>
 *
 * <p><b>Nothing is estimated</b> (EWM rule 199). A missing measure, a unit that is not a count, a
 * fractional quantity, a crush limit without a unit weight or a stack with no bound at all make the
 * line {@link GeometryStatus#UNMEASURED} with the reason, and the line keeps its declared volume.
 */
public final class LoadGeometryCalculator {

    public static final String NO_PHYSICAL_BLOCK = "NO_PHYSICAL_BLOCK";
    public static final String UOM_NOT_COUNTABLE = "UOM_NOT_COUNTABLE";
    public static final String QUANTITY_NOT_WHOLE = "QUANTITY_NOT_WHOLE";
    public static final String MISSING_LENGTH = "MISSING_LENGTH";
    public static final String MISSING_WIDTH = "MISSING_WIDTH";
    public static final String MISSING_HEIGHT = "MISSING_HEIGHT";
    public static final String MISSING_STACK_MODE = "MISSING_STACK_MODE";
    public static final String MISSING_NEST_INCREMENT = "MISSING_NEST_INCREMENT";
    public static final String MISSING_UNIT_WEIGHT = "MISSING_UNIT_WEIGHT";
    public static final String UNBOUNDED_STACK = "UNBOUNDED_STACK";
    public static final String ITEM_TALLER_THAN_VEHICLE = "ITEM_TALLER_THAN_VEHICLE";
    public static final String QUANTITY_OUT_OF_RANGE = "QUANTITY_OUT_OF_RANGE";

    /** {@code stacks} and {@code perStack} are {@code integer} columns (V62). */
    private static final BigDecimal MAX_UNITS = BigDecimal.valueOf(Integer.MAX_VALUE);

    private static final int VOLUME_SCALE = 4;
    private static final int AREA_SCALE = 4;
    private static final int METRES_SCALE = 3;

    private LoadGeometryCalculator() {}

    /**
     * The geometry of one line.
     *
     * @param quantity      the line's quantity, in {@code uom}
     * @param uom           the line's unit; a measure unit ({@link UnitsOfMeasure#isMeasure}) is not
     *                      a count of units and is never converted
     * @param unitWeightKg  needed only when the block declares a crush limit
     * @param block         the line's physical block, or {@code null}
     * @param usableHeightM the height {@code H} to stack against, or {@code null} for none (then
     *                      only {@code stackMaxQuantity} bounds a stack)
     */
    public static LineGeometry compute(BigDecimal quantity, String uom, BigDecimal unitWeightKg, PhysicalBlock block,
            BigDecimal usableHeightM) {
        if (block == null || block.isEmpty()) {
            return LineGeometry.unmeasured(NO_PHYSICAL_BLOCK);
        }
        if (UnitsOfMeasure.isMeasure(uom)) {
            return LineGeometry.unmeasured(UOM_NOT_COUNTABLE);
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            return LineGeometry.unmeasured(QUANTITY_NOT_WHOLE);
        }
        // Stacks and units are counted in an int (the column), so a count beyond it is not measured
        // rather than overflowed.
        if (quantity.compareTo(MAX_UNITS) > 0) {
            return LineGeometry.unmeasured(QUANTITY_OUT_OF_RANGE);
        }
        // A zero or negative measure is as unusable as a missing one, and dividing by it would throw.
        if (!positive(block.lengthM())) {
            return LineGeometry.unmeasured(MISSING_LENGTH);
        }
        if (!positive(block.widthM())) {
            return LineGeometry.unmeasured(MISSING_WIDTH);
        }
        if (!positive(block.heightM())) {
            return LineGeometry.unmeasured(MISSING_HEIGHT);
        }

        long units = quantity.longValueExact();
        BigDecimal h = block.heightM();
        // A unit taller than H does not fit, and nothing else the block says can change that: a stack
        // mode, a nest increment, a stack maximum or a crush limit only ever shorten a stack, never a
        // unit. Checked before them, so the answer is ITEM_TALLER_THAN_VEHICLE even when the block is
        // otherwise incomplete. Described as one unit per stack: what it would be in a vehicle tall
        // enough to take it, so the order's stack height names the vehicles that can.
        if (usableHeightM != null && h.compareTo(usableHeightM) > 0) {
            return figures(GeometryStatus.DOES_NOT_FIT, ITEM_TALLER_THAN_VEHICLE, block, units, 1, h, h);
        }
        if (block.stackMode() == null) {
            return LineGeometry.unmeasured(MISSING_STACK_MODE);
        }
        if (block.stackMode() == StackMode.NESTABLE && !positive(block.nestIncrementM())) {
            return LineGeometry.unmeasured(MISSING_NEST_INCREMENT);
        }
        if (block.maxTopLoadKg() != null && block.stackMode() != StackMode.NONE
                && (unitWeightKg == null || unitWeightKg.signum() <= 0)) {
            return LineGeometry.unmeasured(MISSING_UNIT_WEIGHT);
        }

        long perStack;
        if (block.stackMode() == StackMode.NONE) {
            perStack = 1;
        } else {
            // Every bound is taken as a BigDecimal and capped at the line's own units before it
            // becomes a long: no stack holds more units than the line has, and a huge crush limit
            // over a tiny unit weight must not overflow into a negative stack.
            BigDecimal bound = null;
            if (block.stackMaxQuantity() != null) {
                bound = BigDecimal.valueOf(block.stackMaxQuantity());
            }
            if (usableHeightM != null) {
                bound = min(bound, heightCap(block, usableHeightM));
            }
            if (block.maxTopLoadKg() != null) {
                bound = min(bound, block.maxTopLoadKg().divide(unitWeightKg, 0, RoundingMode.FLOOR).add(BigDecimal.ONE));
            }
            if (bound == null) {
                return LineGeometry.unmeasured(UNBOUNDED_STACK);
            }
            perStack = Math.max(1L, bound.min(BigDecimal.valueOf(units)).longValue());
        }

        long full = Math.min(perStack, units);
        long stacks = (units + perStack - 1) / perStack;
        long last = units - (stacks - 1) * perStack;
        return figures(GeometryStatus.MEASURED, null, block, units, perStack, stackHeight(block, full),
                stackHeight(block, last));
    }

    /**
     * The figures of {@code units} in stacks of {@code perStack}. Rounded <em>up</em>: these are what a
     * vehicle is loaded against, so a rounding never makes a load smaller than it is, and a tiny unit
     * never rounds down to a zero height or floor (V62's CHECKs want both positive). A vehicle's height
     * has at most three decimals, so a stack that fits exactly still fits once rounded.
     */
    private static LineGeometry figures(GeometryStatus status, String reason, PhysicalBlock block, long units,
            long perStack, BigDecimal fullHeight, BigDecimal lastHeight) {
        long stacks = (units + perStack - 1) / perStack;
        BigDecimal footprint = block.lengthM().multiply(block.widthM());
        BigDecimal volume = footprint.multiply(fullHeight).multiply(BigDecimal.valueOf(stacks - 1))
                .add(footprint.multiply(lastHeight));
        BigDecimal floor = footprint.multiply(BigDecimal.valueOf(stacks));
        return new LineGeometry(status, reason, Math.toIntExact(perStack), Math.toIntExact(stacks),
                fullHeight.setScale(METRES_SCALE, RoundingMode.UP),
                lastHeight.setScale(METRES_SCALE, RoundingMode.UP),
                volume.setScale(VOLUME_SCALE, RoundingMode.UP),
                floor.setScale(AREA_SCALE, RoundingMode.UP),
                block.heightM().setScale(METRES_SCALE, RoundingMode.UP));
    }

    /**
     * Whether one unit fits across a load space of {@code vehicleWidthM}: its width does, or - when
     * it may be turned ({@code orientationLocked = false}) - its length does. Turning never changes
     * the floor area. A missing measure or width answers {@code true}: nothing to refuse it with.
     */
    public static boolean fitsAcross(PhysicalBlock block, BigDecimal vehicleWidthM) {
        if (block == null || vehicleWidthM == null || block.widthM() == null) {
            return true;
        }
        if (block.widthM().compareTo(vehicleWidthM) <= 0) {
            return true;
        }
        return !block.orientationLocked() && block.lengthM() != null
                && block.lengthM().compareTo(vehicleWidthM) <= 0;
    }

    /**
     * Loading metres for a floor area: {@code floorArea / (vehicleWidth x fillFactor)}, or
     * {@code null} when the width or the factor is unknown (no estimate).
     */
    public static BigDecimal loadingMetres(BigDecimal floorAreaM2, BigDecimal vehicleWidthM, BigDecimal fillFactor) {
        if (floorAreaM2 == null || vehicleWidthM == null || fillFactor == null
                || vehicleWidthM.signum() <= 0 || fillFactor.signum() <= 0) {
            return null;
        }
        return floorAreaM2.divide(vehicleWidthM.multiply(fillFactor), METRES_SCALE, RoundingMode.HALF_UP);
    }

    /** How many units H takes, given that one unit fits ({@code h <= H}) - so never below one. */
    private static BigDecimal heightCap(PhysicalBlock block, BigDecimal usableHeightM) {
        return switch (block.stackMode()) {
            case STACKABLE -> usableHeightM.divide(block.heightM(), 0, RoundingMode.FLOOR);
            case NESTABLE -> usableHeightM.subtract(block.heightM())
                    .divide(block.nestIncrementM(), 0, RoundingMode.FLOOR).add(BigDecimal.ONE);
            case NONE -> BigDecimal.ONE;
        };
    }

    private static BigDecimal min(BigDecimal current, BigDecimal candidate) {
        return current == null ? candidate : current.min(candidate);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal stackHeight(PhysicalBlock block, long units) {
        return switch (block.stackMode()) {
            case STACKABLE -> block.heightM().multiply(BigDecimal.valueOf(units));
            case NESTABLE -> block.heightM().add(block.nestIncrementM().multiply(BigDecimal.valueOf(units - 1)));
            case NONE -> block.heightM();
        };
    }
}
