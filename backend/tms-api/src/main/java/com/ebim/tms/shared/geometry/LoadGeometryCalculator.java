package com.ebim.tms.shared.geometry;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Load geometry by footprint (ADR-022 section 3, D-05): how many stacks a line needs, how tall
 * they are, how much floor they cover and how many cubic metres they really occupy.
 *
 * <p>Pure: no Spring, no clock, no repository. Orders compute a line's figures with it when the
 * line is written, planning will re-stack with it, and rates read what it produced - one
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
        if (block.lengthM() == null) {
            return LineGeometry.unmeasured(MISSING_LENGTH);
        }
        if (block.widthM() == null) {
            return LineGeometry.unmeasured(MISSING_WIDTH);
        }
        if (block.heightM() == null) {
            return LineGeometry.unmeasured(MISSING_HEIGHT);
        }
        if (block.stackMode() == null) {
            return LineGeometry.unmeasured(MISSING_STACK_MODE);
        }
        if (block.stackMode() == StackMode.NESTABLE && block.nestIncrementM() == null) {
            return LineGeometry.unmeasured(MISSING_NEST_INCREMENT);
        }
        if (block.maxTopLoadKg() != null && block.stackMode() != StackMode.NONE
                && (unitWeightKg == null || unitWeightKg.signum() <= 0)) {
            return LineGeometry.unmeasured(MISSING_UNIT_WEIGHT);
        }

        long units = quantity.longValueExact();
        BigDecimal h = block.heightM();
        boolean doesNotFit = usableHeightM != null && h.compareTo(usableHeightM) > 0;

        long perStack;
        if (doesNotFit || block.stackMode() == StackMode.NONE) {
            // A unit taller than H is described as one unit per stack: what it would be in a vehicle
            // tall enough to take it, so the order's stack height names the vehicles that can.
            perStack = 1;
        } else {
            long bound = Long.MAX_VALUE;
            if (block.stackMaxQuantity() != null) {
                bound = block.stackMaxQuantity();
            }
            if (usableHeightM != null) {
                bound = Math.min(bound, heightCap(block, usableHeightM));
            }
            if (block.maxTopLoadKg() != null) {
                bound = Math.min(bound, block.maxTopLoadKg().divide(unitWeightKg, 0, RoundingMode.FLOOR)
                        .longValue() + 1);
            }
            if (bound == Long.MAX_VALUE) {
                return LineGeometry.unmeasured(UNBOUNDED_STACK);
            }
            perStack = Math.max(1, bound);
        }

        long stacks = (units + perStack - 1) / perStack;
        long full = Math.min(perStack, units);
        long last = units - (stacks - 1) * perStack;
        BigDecimal footprint = block.lengthM().multiply(block.widthM());
        BigDecimal fullHeight = stackHeight(block, full);
        BigDecimal lastHeight = stackHeight(block, last);
        BigDecimal volume = footprint.multiply(fullHeight).multiply(BigDecimal.valueOf(stacks - 1))
                .add(footprint.multiply(lastHeight));
        BigDecimal floor = footprint.multiply(BigDecimal.valueOf(stacks));

        return new LineGeometry(doesNotFit ? GeometryStatus.DOES_NOT_FIT : GeometryStatus.MEASURED,
                doesNotFit ? ITEM_TALLER_THAN_VEHICLE : null,
                Math.toIntExact(perStack), Math.toIntExact(stacks),
                fullHeight.setScale(METRES_SCALE, RoundingMode.HALF_UP),
                lastHeight.setScale(METRES_SCALE, RoundingMode.HALF_UP),
                volume.setScale(VOLUME_SCALE, RoundingMode.HALF_UP),
                floor.setScale(AREA_SCALE, RoundingMode.HALF_UP),
                h.setScale(METRES_SCALE, RoundingMode.HALF_UP));
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

    private static long heightCap(PhysicalBlock block, BigDecimal usableHeightM) {
        return switch (block.stackMode()) {
            case STACKABLE -> usableHeightM.divide(block.heightM(), 0, RoundingMode.FLOOR).longValue();
            case NESTABLE -> usableHeightM.subtract(block.heightM())
                    .divide(block.nestIncrementM(), 0, RoundingMode.FLOOR).longValue() + 1;
            case NONE -> 1;
        };
    }

    private static BigDecimal stackHeight(PhysicalBlock block, long units) {
        return switch (block.stackMode()) {
            case STACKABLE -> block.heightM().multiply(BigDecimal.valueOf(units));
            case NESTABLE -> block.heightM().add(block.nestIncrementM().multiply(BigDecimal.valueOf(units - 1)));
            case NONE -> block.heightM();
        };
    }
}
