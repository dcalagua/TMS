package com.ebim.tms.shared.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The footprint arithmetic of ADR-022 section 3, as a table of cases. */
class LoadGeometryCalculatorTest {

    private static final BigDecimal H = new BigDecimal("2.40");

    private static final PhysicalBlock PLP_571 = new PhysicalBlock(m("0.105"), m("0.105"), m("0.175"),
            StackMode.NESTABLE, 100, m("0.012"), null, false);

    private static final PhysicalBlock CRATE = new PhysicalBlock(m("0.60"), m("0.40"), m("0.30"),
            StackMode.STACKABLE, null, null, null, false);

    @Test
    @DisplayName("PLP-571: 1,000 nested cups are 10 stacks of 1.363 m, about 0.150 m3 (naive 1.93 m3)")
    void plp571ReferenceCase() {
        LineGeometry geometry = LoadGeometryCalculator.compute(m("1000"), "UN", null, PLP_571, H);

        assertThat(geometry.status()).isEqualTo(GeometryStatus.MEASURED);
        assertThat(geometry.perStack()).isEqualTo(100);
        assertThat(geometry.stacks()).isEqualTo(10);
        assertThat(geometry.stackHeightM()).isEqualByComparingTo("1.363");
        assertThat(geometry.lastStackHeightM()).isEqualByComparingTo("1.363");
        assertThat(geometry.effectiveVolumeM3()).isEqualByComparingTo("0.1503");
        assertThat(geometry.floorAreaM2()).isEqualByComparingTo("0.1103");
        BigDecimal naive = m("1000").multiply(m("0.105")).multiply(m("0.105")).multiply(m("0.175"));
        assertThat(naive).isGreaterThan(m("1.92")).isLessThan(m("1.93"));
    }

    static Stream<Arguments> measuredCases() {
        return Stream.of(
                // STACKABLE: floor(2.40 / 0.30) = 8 per stack; 20 crates = 8 + 8 + 4.
                Arguments.of("stackable, last stack incomplete", m("20"), null, CRATE, H,
                        8, 3, "2.400", "1.200", "1.4400", "0.7200"),
                // NONE: one unit per floor position, whatever H allows.
                Arguments.of("not stackable", m("3"), null,
                        new PhysicalBlock(m("0.5"), m("0.5"), m("1.0"), StackMode.NONE, null, null, null, false), H,
                        1, 3, "1.000", "1.000", "0.7500", "0.7500"),
                // NESTABLE, last stack of 50: 0.175 + 49 x 0.012 = 0.763.
                Arguments.of("nestable, last stack incomplete", m("1050"), null, PLP_571, H,
                        100, 11, "1.363", "0.763", "0.1587", "0.1213"),
                // Crush: floor(25 / 10) + 1 = 3 bear on the bottom one, below the 8 the height allows.
                Arguments.of("crush limit binds", m("7"), m("10"),
                        new PhysicalBlock(m("0.60"), m("0.40"), m("0.30"), StackMode.STACKABLE, null, null, m("25"),
                                false), H,
                        3, 3, "0.900", "0.300", "0.5040", "0.7200"),
                // stackMaxQuantity binds below the height cap.
                Arguments.of("stack maximum binds", m("10"), null,
                        new PhysicalBlock(m("0.60"), m("0.40"), m("0.30"), StackMode.STACKABLE, 4, null, null, false),
                        H, 4, 3, "1.200", "0.600", "0.7200", "0.7200"),
                // No H at all: the declared maximum is the only bound, and it is enough.
                Arguments.of("no usable height, bounded by the declared maximum", m("250"), null, PLP_571, null,
                        100, 3, "1.363", "0.763", "0.0385", "0.0331"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("measuredCases")
    void measured(String name, BigDecimal quantity, BigDecimal unitWeight, PhysicalBlock block, BigDecimal height,
            int perStack, int stacks, String stackHeight, String lastHeight, String volume, String floor) {
        LineGeometry geometry = LoadGeometryCalculator.compute(quantity, "UN", unitWeight, block, height);

        assertThat(geometry.status()).isEqualTo(GeometryStatus.MEASURED);
        assertThat(geometry.reason()).isNull();
        assertThat(geometry.perStack()).isEqualTo(perStack);
        assertThat(geometry.stacks()).isEqualTo(stacks);
        assertThat(geometry.stackHeightM()).isEqualByComparingTo(stackHeight);
        assertThat(geometry.lastStackHeightM()).isEqualByComparingTo(lastHeight);
        assertThat(geometry.effectiveVolumeM3()).isEqualByComparingTo(volume);
        assertThat(geometry.floorAreaM2()).isEqualByComparingTo(floor);
    }

    @Test
    @DisplayName("a unit taller than H does not fit, and is described as one unit per stack")
    void tallerThanUsableHeight() {
        PhysicalBlock tall = new PhysicalBlock(m("0.50"), m("0.50"), m("2.50"), StackMode.STACKABLE, null, null,
                null, false);

        LineGeometry geometry = LoadGeometryCalculator.compute(m("2"), "UN", null, tall, H);

        assertThat(geometry.status()).isEqualTo(GeometryStatus.DOES_NOT_FIT);
        assertThat(geometry.reason()).isEqualTo(LoadGeometryCalculator.ITEM_TALLER_THAN_VEHICLE);
        assertThat(geometry.perStack()).isEqualTo(1);
        assertThat(geometry.stacks()).isEqualTo(2);
        assertThat(geometry.stackHeightM()).isEqualByComparingTo("2.500");
        assertThat(geometry.unitHeightM()).isEqualByComparingTo("2.500");
        assertThat(geometry.hasFigures()).isTrue();
    }

    @Test
    @DisplayName("the same crates re-stack per height: eight under 2.40 m, seven under 2.20 m")
    void restacksPerHeight() {
        LineGeometry reference = LoadGeometryCalculator.compute(m("16"), "UN", null, CRATE, H);
        LineGeometry lower = LoadGeometryCalculator.compute(m("16"), "UN", null, CRATE, m("2.20"));

        assertThat(reference.perStack()).isEqualTo(8);
        assertThat(reference.stackHeightM()).isEqualByComparingTo("2.400");
        assertThat(lower.status()).isEqualTo(GeometryStatus.MEASURED);
        assertThat(lower.perStack()).isEqualTo(7);
        assertThat(lower.stacks()).isEqualTo(3);
        assertThat(lower.stackHeightM()).isEqualByComparingTo("2.100");
        assertThat(lower.floorAreaM2()).isEqualByComparingTo("0.7200");
    }

    @Test
    @DisplayName("a unit taller than H is ITEM_TALLER_THAN_VEHICLE even when its crush limit has no unit weight")
    void tallerWinsOverAnIncompleteBlock() {
        PhysicalBlock tallCrushable = new PhysicalBlock(m("0.50"), m("0.50"), m("2.50"), StackMode.STACKABLE, null,
                null, m("25"), false);
        PhysicalBlock tallNoMode = new PhysicalBlock(m("0.50"), m("0.50"), m("2.50"), null, null, null, null, false);
        // A nestable whose base alone is taller than H cannot be nested into fitting.
        PhysicalBlock tallNest = new PhysicalBlock(m("0.50"), m("0.50"), m("2.50"), StackMode.NESTABLE, 10, m("0.01"),
                null, false);

        for (PhysicalBlock block : new PhysicalBlock[] {tallCrushable, tallNoMode, tallNest}) {
            LineGeometry geometry = LoadGeometryCalculator.compute(m("2"), "UN", null, block, H);
            assertThat(geometry.status()).isEqualTo(GeometryStatus.DOES_NOT_FIT);
            assertThat(geometry.reason()).isEqualTo(LoadGeometryCalculator.ITEM_TALLER_THAN_VEHICLE);
            assertThat(geometry.stacks()).isEqualTo(2);
            assertThat(geometry.stackHeightM()).isEqualByComparingTo("2.500");
        }
    }

    @Test
    @DisplayName("guards: zero measures are unmeasured, huge bounds do not overflow, tiny units do not round to zero")
    void guards() {
        assertThat(LoadGeometryCalculator.compute(m("10"), "UN", null,
                new PhysicalBlock(m("0.6"), m("0.4"), BigDecimal.ZERO, StackMode.STACKABLE, null, null, null, false), H)
                .reason()).isEqualTo(LoadGeometryCalculator.MISSING_HEIGHT);
        assertThat(LoadGeometryCalculator.compute(m("10"), "UN", null,
                new PhysicalBlock(m("0.1"), m("0.1"), m("0.1"), StackMode.NESTABLE, null, BigDecimal.ZERO, null, false),
                H).reason()).isEqualTo(LoadGeometryCalculator.MISSING_NEST_INCREMENT);
        assertThat(LoadGeometryCalculator.compute(m("3000000000"), "UN", null, CRATE, H).reason())
                .isEqualTo(LoadGeometryCalculator.QUANTITY_OUT_OF_RANGE);

        // A crush limit of a billion kilograms over a gram: the bound is far beyond a long's int range
        // once multiplied, and the stack is still at most the line's own units.
        LineGeometry crushable = LoadGeometryCalculator.compute(m("5"), "UN", m("0.001"),
                new PhysicalBlock(m("0.1"), m("0.1"), m("0.1"), StackMode.STACKABLE, null, null, m("999999999.999"),
                        false), null);
        assertThat(crushable.status()).isEqualTo(GeometryStatus.MEASURED);
        assertThat(crushable.perStack()).isEqualTo(5);
        assertThat(crushable.stacks()).isEqualTo(1);

        LineGeometry tiny = LoadGeometryCalculator.compute(m("1"), "UN", null,
                new PhysicalBlock(m("0.0001"), m("0.0001"), m("0.0001"), StackMode.STACKABLE, null, null, null, false),
                H);
        assertThat(tiny.stackHeightM()).isPositive();
        assertThat(tiny.unitHeightM()).isPositive();
        assertThat(tiny.floorAreaM2()).isPositive();
        assertThat(tiny.effectiveVolumeM3()).isPositive();
    }

    static Stream<Arguments> unmeasuredCases() {
        return Stream.of(
                Arguments.of("no block", m("10"), "UN", null, null, H, LoadGeometryCalculator.NO_PHYSICAL_BLOCK),
                Arguments.of("unit is a measure, never converted", m("10"), "KG", null, CRATE, H,
                        LoadGeometryCalculator.UOM_NOT_COUNTABLE),
                Arguments.of("fractional quantity", m("10.5"), "UN", null, CRATE, H,
                        LoadGeometryCalculator.QUANTITY_NOT_WHOLE),
                Arguments.of("missing height", m("10"), "UN", null,
                        new PhysicalBlock(m("0.6"), m("0.4"), null, StackMode.STACKABLE, null, null, null, false), H,
                        LoadGeometryCalculator.MISSING_HEIGHT),
                Arguments.of("missing length", m("10"), "UN", null,
                        new PhysicalBlock(null, m("0.4"), m("0.3"), StackMode.STACKABLE, null, null, null, false), H,
                        LoadGeometryCalculator.MISSING_LENGTH),
                Arguments.of("missing stack mode", m("10"), "UN", null,
                        new PhysicalBlock(m("0.6"), m("0.4"), m("0.3"), null, null, null, null, false), H,
                        LoadGeometryCalculator.MISSING_STACK_MODE),
                Arguments.of("crush limit without a unit weight", m("10"), "UN", null,
                        new PhysicalBlock(m("0.6"), m("0.4"), m("0.3"), StackMode.STACKABLE, null, null, m("25"),
                                false), H,
                        LoadGeometryCalculator.MISSING_UNIT_WEIGHT),
                Arguments.of("nothing bounds the stack", m("10"), "UN", null, CRATE, null,
                        LoadGeometryCalculator.UNBOUNDED_STACK));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unmeasuredCases")
    void unmeasuredCountsAndNeverEstimates(String name, BigDecimal quantity, String uom, BigDecimal unitWeight,
            PhysicalBlock block, BigDecimal height, String reason) {
        LineGeometry geometry = LoadGeometryCalculator.compute(quantity, uom, unitWeight, block, height);

        assertThat(geometry.status()).isEqualTo(GeometryStatus.UNMEASURED);
        assertThat(geometry.reason()).isEqualTo(reason);
        assertThat(geometry.hasFigures()).isFalse();
        assertThat(geometry.stacks()).isNull();
        assertThat(geometry.effectiveVolumeM3()).isNull();
        assertThat(geometry.floorAreaM2()).isNull();
    }

    @Test
    @DisplayName("a unit wider than the vehicle may be turned unless its orientation is locked")
    void orientationLock() {
        PhysicalBlock free = new PhysicalBlock(m("2.0"), m("2.5"), m("1.0"), StackMode.NONE, null, null, null, false);
        PhysicalBlock locked = new PhysicalBlock(m("2.0"), m("2.5"), m("1.0"), StackMode.NONE, null, null, null, true);

        assertThat(LoadGeometryCalculator.fitsAcross(free, m("2.40"))).isTrue();
        assertThat(LoadGeometryCalculator.fitsAcross(locked, m("2.40"))).isFalse();
        assertThat(LoadGeometryCalculator.fitsAcross(locked, m("2.50"))).isTrue();
        assertThat(LoadGeometryCalculator.fitsAcross(locked, null)).isTrue();
        // Turning never changes the floor the line covers.
        assertThat(LoadGeometryCalculator.compute(m("2"), "UN", null, free, H).floorAreaM2())
                .isEqualByComparingTo(LoadGeometryCalculator.compute(m("2"), "UN", null, locked, H).floorAreaM2());
    }

    @Test
    @DisplayName("loading metres need a width and a fill factor; without them there is no figure")
    void loadingMetres() {
        assertThat(LoadGeometryCalculator.loadingMetres(m("4.08"), m("2.40"), m("0.85"))).isEqualByComparingTo("2.000");
        assertThat(LoadGeometryCalculator.loadingMetres(m("4.08"), null, m("0.85"))).isNull();
        assertThat(LoadGeometryCalculator.loadingMetres(m("4.08"), m("2.40"), null)).isNull();
    }

    private static BigDecimal m(String value) {
        return new BigDecimal(value);
    }
}
