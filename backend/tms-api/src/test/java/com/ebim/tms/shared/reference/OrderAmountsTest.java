package com.ebim.tms.shared.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic the allocation ledger is built on (migration V37).
 *
 * <p>Small, and worth having: every rule about splitting an order reduces to one of these
 * comparisons, and the scale trap below is the kind of defect that only shows up on the assignment
 * that exactly finishes an order - the one case a planner notices immediately and a test suite
 * built on round numbers never reaches.
 */
class OrderAmountsTest {

    private static OrderAmounts of(String weight, String volume, String pallets) {
        return new OrderAmounts(new BigDecimal(weight), new BigDecimal(volume), new BigDecimal(pallets));
    }

    @Nested
    @DisplayName("scale must not change the answer")
    class Scale {

        /**
         * {@code 30.00} and {@code 30} are the same quantity of pallets and two different
         * {@code BigDecimal}s. A ledger that compared with {@code equals} would refuse the split
         * that exactly fills an order, because the column comes back at the column's scale and the
         * request arrives at the request's.
         */
        @Test
        @DisplayName("70 + 30.00 covers 100.000")
        void trailingZerosDoNotBreakAnExactFill() {
            OrderAmounts allocated = of("700", "7", "70").plus(of("300.000", "3.0000", "30.00"));

            assertThat(allocated.covers(of("1000.000", "10.0000", "100.00"))).isTrue();
            assertThat(allocated.exceeds(of("1000.000", "10.0000", "100.00"))).isFalse();
        }

        @Test
        @DisplayName("a zero written three ways is still zero")
        void zeroIsZero() {
            assertThat(new OrderAmounts(new BigDecimal("0.000"), BigDecimal.ZERO, new BigDecimal("0.00")).isZero())
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("the ceiling")
    class Ceiling {

        @Test
        @DisplayName("exceeding in any one measure is exceeding")
        void anyMeasure() {
            OrderAmounts ordered = of("1000", "10", "100");

            assertThat(of("1001", "10", "100").exceeds(ordered)).isTrue();
            assertThat(of("1000", "10.0001", "100").exceeds(ordered)).isTrue();
            assertThat(of("1000", "10", "100.01").exceeds(ordered)).isTrue();
            assertThat(of("1000", "10", "100").exceeds(ordered)).isFalse();
        }

        /**
         * An order whose weight, volume and pallets are all unknown is plannable, and assigning it
         * has always made it PLANNED. Nothing covers nothing, so that behaviour survives V37.
         */
        @Test
        @DisplayName("nothing covers an order that asks for nothing")
        void anOrderWithNothingKnown() {
            assertThat(OrderAmounts.NONE.covers(OrderAmounts.NONE)).isTrue();
            assertThat(OrderAmounts.NONE.exceeds(OrderAmounts.NONE)).isFalse();
        }

        @Test
        @DisplayName("a part does not cover the whole")
        void aPartIsNotTheWhole() {
            assertThat(of("700", "7", "70").covers(of("1000", "10", "100"))).isFalse();
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("pending is ordered minus allocated, measure by measure")
        void pending() {
            OrderAmounts pending = of("1000", "10", "100").minus(of("700", "7", "70"));

            assertThat(pending.weightKg()).isEqualByComparingTo("300");
            assertThat(pending.volumeM3()).isEqualByComparingTo("3");
            assertThat(pending.pallets()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("releasing more than was allocated goes negative, which the caller must refuse")
        void negativeIsDetectable() {
            assertThat(of("70", "7", "7").minus(of("100", "10", "10")).isNegative()).isTrue();
            assertThat(of("100", "10", "10").minus(of("100", "10", "10")).isNegative()).isFalse();
        }

        @Test
        @DisplayName("null is nothing, not a failure")
        void nullsNormaliseToZero() {
            assertThat(new OrderAmounts(null, null, null).isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("the allocation view")
    class Allocation {

        @Test
        @DisplayName("a part-allocated order is neither untouched nor fully planned")
        void partial() {
            OrderAllocation allocation = new OrderAllocation(of("1000", "10", "100"), of("700", "7", "70"));

            assertThat(allocation.isFullyAllocated()).isFalse();
            assertThat(allocation.isPartiallyAllocated()).isTrue();
            assertThat(allocation.pending().pallets()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("an untouched order is not partially allocated")
        void untouched() {
            OrderAllocation allocation = new OrderAllocation(of("1000", "10", "100"), OrderAmounts.NONE);

            assertThat(allocation.isPartiallyAllocated()).isFalse();
            assertThat(allocation.isFullyAllocated()).isFalse();
        }

        @Test
        @DisplayName("an order with nothing known is fully allocated from the start")
        void nothingKnown() {
            OrderAllocation allocation = new OrderAllocation(OrderAmounts.NONE, OrderAmounts.NONE);

            assertThat(allocation.isFullyAllocated()).isTrue();
            assertThat(allocation.isPartiallyAllocated()).isFalse();
        }
    }
}
