package com.ebim.tms.shared.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shape rules a material profile and an order line's block share (V59, ADR-022). */
class PhysicalBlockTest {

    private static final BigDecimal TEN_CM = new BigDecimal("0.10");

    @Test
    @DisplayName("PLP-571 as the warehouse states it is valid")
    void plp571IsValid() {
        PhysicalBlock block = new PhysicalBlock(new BigDecimal("0.105"), new BigDecimal("0.105"),
                new BigDecimal("0.175"), StackMode.NESTABLE, 100, new BigDecimal("0.012"), null, false);

        assertThat(block.violations()).isEmpty();
        assertThat(block.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("NESTABLE without a nest increment is refused, and so is a nest increment without NESTABLE")
    void nestIncrementGoesWithNestable() {
        assertThat(new PhysicalBlock(TEN_CM, TEN_CM, TEN_CM, StackMode.NESTABLE, null, null, null, false).violations())
                .containsExactly("nestIncrementM is required when stackMode is NESTABLE");
        assertThat(new PhysicalBlock(TEN_CM, TEN_CM, TEN_CM, StackMode.STACKABLE, null, TEN_CM, null, false)
                .violations()).containsExactly("nestIncrementM is only allowed when stackMode is NESTABLE");
        assertThat(new PhysicalBlock(null, null, null, null, null, TEN_CM, null, false).violations())
                .containsExactly("nestIncrementM is only allowed when stackMode is NESTABLE");
    }

    @Test
    @DisplayName("measures, stack size and top load must be greater than zero and fit their columns")
    void measuresArePositive() {
        PhysicalBlock block = new PhysicalBlock(BigDecimal.ZERO, new BigDecimal("-0.1"), new BigDecimal("10000"),
                StackMode.STACKABLE, 0, null, BigDecimal.ZERO, false);

        assertThat(block.violations()).hasSize(5)
                .anySatisfy(message -> assertThat(message).startsWith("lengthM"))
                .anySatisfy(message -> assertThat(message).startsWith("widthM"))
                .anySatisfy(message -> assertThat(message).startsWith("heightM"))
                .anySatisfy(message -> assertThat(message).startsWith("stackMaxQuantity"))
                .anySatisfy(message -> assertThat(message).startsWith("maxTopLoadKg"));
    }

    @Test
    @DisplayName("the orientation flag alone is no block")
    void orientationAloneIsEmpty() {
        assertThat(new PhysicalBlock(null, null, null, null, null, null, null, true).isEmpty()).isTrue();
        assertThat(new PhysicalBlock(null, null, null, StackMode.NONE, null, null, null, false).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("stack mode codes are exact: no case folding")
    void stackModeCodesAreExact() {
        assertThat(StackMode.fromCode("NESTABLE")).contains(StackMode.NESTABLE);
        assertThat(StackMode.fromCode("nestable")).isEmpty();
        assertThat(StackMode.fromCode(null)).isEmpty();
    }

    @Test
    @DisplayName("weight, volume and length units are measures; anything else counts units")
    void unitsOfMeasure() {
        assertThat(UnitsOfMeasure.isMeasure("KG")).isTrue();
        assertThat(UnitsOfMeasure.isMeasure("m3")).isTrue();
        assertThat(UnitsOfMeasure.isMeasure("UN")).isFalse();
        assertThat(UnitsOfMeasure.isMeasure("CJ")).isFalse();
        assertThat(UnitsOfMeasure.isMeasure(null)).isFalse();
    }
}
