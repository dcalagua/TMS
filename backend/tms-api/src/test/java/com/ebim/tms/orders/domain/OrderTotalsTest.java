package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The totals precedence rule (migration V17, {@code docs/domain/ORDER_TOTALS_V1.md}), proved
 * without a database - the rule is arithmetic and a policy decision, and neither needs Docker to
 * be running to be worth testing.
 */
class OrderTotalsTest {

    private static OrderLineInput line(String quantity, String unitWeight, String unitVolume, String pallets) {
        return new OrderLineInput("SKU-1", "Something", new BigDecimal(quantity), "EA",
                unitWeight == null ? null : new BigDecimal(unitWeight),
                unitVolume == null ? null : new BigDecimal(unitVolume),
                pallets == null ? null : new BigDecimal(pallets));
    }

    private static DeclaredTotals declared(String weight, String volume, String pallets) {
        return new DeclaredTotals(weight == null ? null : new BigDecimal(weight),
                volume == null ? null : new BigDecimal(volume),
                pallets == null ? null : new BigDecimal(pallets));
    }

    // --- lines present: CALCULATED ------------------------------------------------

    @Test
    @DisplayName("with lines and nothing declared, the totals are the line sums and the source is CALCULATED")
    void linesAloneAreCalculated() {
        OrderTotals totals = OrderTotals.resolve(
                List.of(line("2", "10", "0.5", "1"), line("3", "4", "0.25", "0.5")),
                DeclaredTotals.none());

        assertThat(totals.source()).isEqualTo(TotalsSource.CALCULATED);
        assertThat(totals.weightKg()).isEqualByComparingTo("32");
        assertThat(totals.volumeM3()).isEqualByComparingTo("1.75");
        assertThat(totals.pallets()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("a measure no line carries falls back to the declared value, still CALCULATED overall")
    void declaredFillsAMeasureTheLinesAreSilentAbout() {
        // No line states a weight, so the weight sum is not zero - it is unknown, and the only
        // real figure available is the sender's. Pallets, which the lines do state, stay theirs.
        OrderTotals totals = OrderTotals.resolve(
                List.of(line("2", null, null, "1"), line("3", null, null, "0.5")),
                declared("1200", null, null));

        assertThat(totals.source()).isEqualTo(TotalsSource.CALCULATED);
        assertThat(totals.weightKg()).isEqualByComparingTo("1200");
        assertThat(totals.volumeM3()).isEqualByComparingTo("0");
        assertThat(totals.pallets()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("a measure neither the lines nor the sender state is zero, not null")
    void unstatedMeasureIsZero() {
        OrderTotals totals = OrderTotals.resolve(List.of(line("2", "10", null, null)), DeclaredTotals.none());

        assertThat(totals.volumeM3()).isEqualByComparingTo("0");
        assertThat(totals.pallets()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the lines win over a declaration that agrees with them")
    void linesWinWhereBothSpeak() {
        OrderTotals totals = OrderTotals.resolve(List.of(line("2", "10", null, null)), declared("20", null, null));

        assertThat(totals.weightKg()).isEqualByComparingTo("20");
        assertThat(totals.source()).isEqualTo(TotalsSource.CALCULATED);
    }

    // --- no lines: DECLARED --------------------------------------------------------

    @Test
    @DisplayName("with no lines, the declared figures are the totals and the source is DECLARED")
    void declaredOnlyIsDeclared() {
        OrderTotals totals = OrderTotals.resolve(List.of(), declared("1200", "3.4", "2"));

        assertThat(totals.source()).isEqualTo(TotalsSource.DECLARED);
        assertThat(totals.weightKg()).isEqualByComparingTo("1200");
        assertThat(totals.volumeM3()).isEqualByComparingTo("3.4");
        assertThat(totals.pallets()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("an order with neither lines nor declarations is DECLARED with zero totals")
    void emptyOrderIsDeclaredZero() {
        OrderTotals totals = OrderTotals.resolve(List.of(), DeclaredTotals.none());

        assertThat(totals.source()).isEqualTo(TotalsSource.DECLARED);
        assertThat(totals.weightKg()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a null declaration is treated as none rather than exploding")
    void nullDeclarationIsNone() {
        assertThat(OrderTotals.resolve(List.of(), null).source()).isEqualTo(TotalsSource.DECLARED);
    }

    // --- cross-check ----------------------------------------------------------------

    @Test
    @DisplayName("a declaration that contradicts the lines beyond 1% is reported")
    void contradictionIsReported() {
        // Lines add to 20 kg; the sender says 1,200 - the classic per-unit/per-case mistake.
        List<OrderTotals.Mismatch> mismatches =
                OrderTotals.mismatches(List.of(line("2", "10", null, null)), declared("1200", null, null));

        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.get(0).measure()).isEqualTo(OrderTotals.Measure.WEIGHT_KG);
        assertThat(mismatches.get(0).declared()).isEqualByComparingTo("1200");
        assertThat(mismatches.get(0).calculated()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("rounding within 1% is not a contradiction")
    void toleranceAbsorbsRounding() {
        // 100 lines of 1.005 kg add to 100.5; a sender rounding each unit to 1.0 reports 100.
        List<OrderLineInput> lines = IntStream.range(0, 100)
                .mapToObj(index -> line("1", "1.005", null, null))
                .toList();

        assertThat(OrderTotals.mismatches(lines, declared("100", null, null))).isEmpty();
    }

    @Test
    @DisplayName("a measure the lines are silent about cannot contradict a declaration")
    void silentMeasureIsNotComparable() {
        // The weight sum is unknown, not zero, so declaring 1,200 kg is filling a gap rather
        // than disagreeing - the case declaredFillsAMeasureTheLinesAreSilentAbout relies on.
        assertThat(OrderTotals.mismatches(List.of(line("2", null, null, "1")), declared("1200", null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("with no lines there is nothing to contradict")
    void noLinesNoMismatch() {
        assertThat(OrderTotals.mismatches(List.of(), declared("1200", "3.4", "2"))).isEmpty();
    }

    @Test
    @DisplayName("every contradicting measure is reported, not only the first")
    void everyMeasureIsReported() {
        List<OrderTotals.Mismatch> mismatches = OrderTotals.mismatches(
                List.of(line("2", "10", "0.5", "1")), declared("999", "999", "999"));

        assertThat(mismatches).extracting(OrderTotals.Mismatch::measure)
                .containsExactly(OrderTotals.Measure.WEIGHT_KG, OrderTotals.Measure.VOLUME_M3,
                        OrderTotals.Measure.PALLETS);
    }

    @Test
    @DisplayName("an explicit zero pallet count on the lines contradicts a declared pallet count")
    void explicitZeroIsAStatement() {
        // pallet_quantity 0 is "this line occupies no pallet", not "unknown" - so a declaration
        // of 2 pallets disagrees with it and must not silently win.
        assertThat(OrderTotals.mismatches(List.of(line("2", null, null, "0")), declared(null, null, "2")))
                .extracting(OrderTotals.Mismatch::measure)
                .containsExactly(OrderTotals.Measure.PALLETS);
    }
}
