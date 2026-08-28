package com.ebim.tms.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ebim.tms.shared.reference.OrderAmounts;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How much was delivered (migration V45, closing debt D3).
 *
 * <p>D3 was open from V28 until now, and its formal evaluation
 * ({@code docs/domain/DELIVERED_QUANTITY_EVALUATION.md}) named the trap this type exists to avoid:
 * a delivered amount <b>must never be inferred</b> from ordered, allocated or planned quantities,
 * because a partial delivery is by definition the case where it differs from all three. A number
 * taken from any of them would be exactly wrong in exactly the case it is needed - and would look
 * like a measurement.
 *
 * <p>So the two things worth testing hardest are what this refuses to represent:
 * {@link Absence} (nothing was recorded, which is not a delivery of nothing) and
 * {@link TheInvariant} (nothing can be delivered beyond what was taken).
 *
 * <p>A pure value object - no database, no clock.
 */
class DeliveryQuantitiesTest {

    private static OrderAmounts amounts(String weight, String volume, String pallets) {
        return new OrderAmounts(new BigDecimal(weight), new BigDecimal(volume), new BigDecimal(pallets));
    }

    private static final OrderAmounts HUNDRED = amounts("100", "10", "5");
    private static final OrderAmounts SEVENTY = amounts("70", "7", "3.5");
    private static final OrderAmounts THIRTY = amounts("30", "3", "1.5");
    private static final OrderAmounts NOTHING = OrderAmounts.NONE;

    @Nested
    @DisplayName("absent is not zero")
    class Absence {

        /**
         * The distinction the whole feature rests on. Every delivery written before V45 has no
         * quantities; reading those as zero would assert that nothing was ever delivered in the
         * history of the installation, and it would look like data rather than like a gap.
         */
        @Test
        @DisplayName("NOT_RECORDED is not a delivery of nothing")
        void notRecordedIsNotZero() {
            assertThat(DeliveryQuantities.NOT_RECORDED.isRecorded()).isFalse();
            assertThat(DeliveryQuantities.NOT_RECORDED.deliveredAnything()).isFalse();
            // ...and it is not "complete" either. Nothing is known, so nothing is claimed.
            assertThat(DeliveryQuantities.NOT_RECORDED.isComplete()).isFalse();
        }

        @Test
        @DisplayName("nine null columns rebuild as NOT_RECORDED, not as three zeros")
        void nullColumnsAreAbsence() {
            DeliveryQuantities rebuilt = DeliveryQuantities.fromColumns(
                    null, null, null, null, null, null, null, null, null);

            assertThat(rebuilt).isEqualTo(DeliveryQuantities.NOT_RECORDED);
            assertThat(rebuilt.isRecorded()).isFalse();
        }

        /**
         * A recorded delivery of zero is a different statement from an unrecorded one: somebody
         * took goods out and the customer took none of them.
         */
        @Test
        @DisplayName("a recorded zero delivery is recorded, and says nothing changed hands")
        void recordedZeroIsNotAbsence() {
            DeliveryQuantities refusedEverything = DeliveryQuantities.of(HUNDRED, NOTHING, HUNDRED);

            assertThat(refusedEverything.isRecorded()).isTrue();
            assertThat(refusedEverything.deliveredAnything()).isFalse();
        }

        @Test
        @DisplayName("half a block is refused: 800 delivered of what?")
        void partialBlockIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DeliveryQuantities(null, HUNDRED, null))
                    .withMessageContaining("recorded together or not at all");
        }
    }

    @Nested
    @DisplayName("the invariant")
    class TheInvariant {

        @Test
        @DisplayName("nothing can be delivered and refused beyond what was attempted")
        void cannotExceedAttempted() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DeliveryQuantities.of(HUNDRED, SEVENTY, amounts("40", "4", "2")))
                    .withMessageContaining("more was delivered and refused than was attempted");
        }

        @Test
        @DisplayName("delivered plus refused may equal attempted exactly")
        void mayEqualAttempted() {
            DeliveryQuantities exact = DeliveryQuantities.of(HUNDRED, SEVENTY, THIRTY);

            assertThat(exact.outstanding().isZero()).isTrue();
        }

        /**
         * Deliberately {@code <=} and not {@code =}. Goods can be attempted and neither delivered
         * nor refused - left on the vehicle because the dock closed, carried back to the depot -
         * and that difference is a real operational state rather than an accounting error.
         */
        @Test
        @DisplayName("goods can come back without anybody refusing them")
        void outstandingIsARealState() {
            DeliveryQuantities partial = DeliveryQuantities.of(HUNDRED, SEVENTY, NOTHING);

            assertThat(partial.outstanding().weightKg()).isEqualByComparingTo("30");
            assertThat(partial.isComplete()).isFalse();
            assertThat(partial.deliveredAnything()).isTrue();
        }

        /**
         * Per measure, not over a total: a shortfall in pallets is not cancelled by a surplus in
         * kilos. A block that balances on weight and over-delivers pallets is still refused.
         */
        @Test
        @DisplayName("the three measures are checked separately, never netted against each other")
        void measuresAreNotInterchangeable() {
            assertThatIllegalArgumentException().isThrownBy(() -> DeliveryQuantities.of(
                    amounts("100", "10", "5"),
                    amounts("50", "5", "5"),
                    amounts("50", "5", "1")));  // weight and volume balance; pallets do not
        }

        @Test
        @DisplayName("negative amounts are refused")
        void negativesAreRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DeliveryQuantities.of(HUNDRED, amounts("-1", "0", "0"), NOTHING))
                    .withMessageContaining("cannot be negative");
        }
    }

    @Nested
    @DisplayName("what it answers")
    class Questions {

        /** The four questions the brief asked the model to answer. */
        @Test
        @DisplayName("attempted, delivered, refused and outstanding are all readable")
        void answersTheFourQuestions() {
            DeliveryQuantities quantities = DeliveryQuantities.of(HUNDRED, SEVENTY, amounts("20", "2", "1"));

            assertThat(quantities.attempted().weightKg()).isEqualByComparingTo("100");
            assertThat(quantities.delivered().weightKg()).isEqualByComparingTo("70");
            assertThat(quantities.refused().weightKg()).isEqualByComparingTo("20");
            assertThat(quantities.outstanding().weightKg()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("complete means the customer took everything that was taken to them")
        void completeness() {
            assertThat(DeliveryQuantities.of(HUNDRED, HUNDRED, NOTHING).isComplete()).isTrue();
            assertThat(DeliveryQuantities.of(HUNDRED, SEVENTY, THIRTY).isComplete()).isFalse();
        }

        @Test
        @DisplayName("a full round trip through nine columns preserves every figure")
        void roundTripsThroughColumns() {
            DeliveryQuantities original = DeliveryQuantities.of(HUNDRED, SEVENTY, THIRTY);

            DeliveryQuantities rebuilt = DeliveryQuantities.fromColumns(
                    original.attemptedWeight(), original.attemptedVolume(), original.attemptedPallets(),
                    original.deliveredWeight(), original.deliveredVolume(), original.deliveredPallets(),
                    original.refusedWeight(), original.refusedVolume(), original.refusedPallets());

            assertThat(rebuilt).isEqualTo(original);
        }
    }
}
