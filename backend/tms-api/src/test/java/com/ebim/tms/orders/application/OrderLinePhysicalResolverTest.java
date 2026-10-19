package com.ebim.tms.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.orders.domain.DeclaredTotals;
import com.ebim.tms.orders.domain.OrderLineInput;
import com.ebim.tms.orders.domain.PhysicalSource;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import com.ebim.tms.shared.reference.MaterialProfileSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-022 section 2's copy rule, without a database. Edits (carry-over) are covered by the IT. */
class OrderLinePhysicalResolverTest {

    private static final PhysicalBlock PLP_571 = new PhysicalBlock(new BigDecimal("0.1050"), new BigDecimal("0.1050"),
            new BigDecimal("0.1750"), StackMode.NESTABLE, 100, new BigDecimal("0.0120"), null, false);
    private static final MaterialProfileSnapshot PROFILE = new MaterialProfileSnapshot("PLP-571", 4,
            new BigDecimal("0.012"), new BigDecimal("0.0019"), PLP_571);

    private final OrderLinePhysicalResolver resolver = new OrderLinePhysicalResolver((companyId, codes) -> Map.of());

    @Test
    @DisplayName("a line with no block copies the profile's block, its version and its unit figures")
    void copiesTheProfile() {
        OrderLineInput resolved = resolveOne(line("UN", null), DeclaredTotals.none());

        assertThat(resolved.physical()).isEqualTo(PLP_571);
        assertThat(resolved.physicalSource()).isEqualTo(PhysicalSource.PROFILE);
        assertThat(resolved.materialProfileVersion()).isEqualTo(4L);
        assertThat(resolved.unitWeightKg()).isEqualByComparingTo("0.012");
        assertThat(resolved.unitVolumeM3()).isEqualByComparingTo("0.0019");
    }

    @Test
    @DisplayName("the ERP's block wins as a whole: nothing of the profile's block is merged in")
    void lineBlockWinsAsAWhole() {
        PhysicalBlock erp = new PhysicalBlock(null, null, new BigDecimal("0.29"), StackMode.STACKABLE, null, null, null,
                false);

        OrderLineInput resolved = resolveOne(line("UN", erp), DeclaredTotals.none());

        assertThat(resolved.physical()).isEqualTo(erp);
        assertThat(resolved.physicalSource()).isEqualTo(PhysicalSource.ORDER_LINE);
        assertThat(resolved.materialProfileVersion()).isNull();
    }

    @Test
    @DisplayName("an invalid ERP block is refused naming the line")
    void invalidLineBlockIsRefused() {
        PhysicalBlock nestableWithoutIncrement = new PhysicalBlock(null, null, new BigDecimal("0.2"),
                StackMode.NESTABLE, null, null, null, false);

        assertThatThrownBy(() -> resolveOne(line("UN", nestableWithoutIncrement), DeclaredTotals.none()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("lines[0] (PLP-571)")
                .hasMessageContaining("nestIncrementM is required");
    }

    @Test
    @DisplayName("the line's own unit figures win; the declared total keeps a measure from being filled")
    void unitFiguresRules() {
        OrderLineInput withWeight = new OrderLineInput("PLP-571", "Vaso", new BigDecimal("1000"), "UN",
                new BigDecimal("0.020"), null, null);
        OrderLineInput own = resolveOne(withWeight, DeclaredTotals.none());
        assertThat(own.unitWeightKg()).isEqualByComparingTo("0.020");
        assertThat(own.unitVolumeM3()).isEqualByComparingTo("0.0019");

        OrderLineInput declaredWeight = resolveOne(line("UN", null),
                new DeclaredTotals(new BigDecimal("13.5"), null, null));
        assertThat(declaredWeight.unitWeightKg()).isNull();
        assertThat(declaredWeight.unitVolumeM3()).isEqualByComparingTo("0.0019");
    }

    @Test
    @DisplayName("a line counted in kilograms takes the block but never the per-unit figures")
    void measureUnitsAreNotFilled() {
        OrderLineInput resolved = resolveOne(line("KG", null), DeclaredTotals.none());

        assertThat(resolved.physicalSource()).isEqualTo(PhysicalSource.PROFILE);
        assertThat(resolved.unitWeightKg()).isNull();
        assertThat(resolved.unitVolumeM3()).isNull();
    }

    @Test
    @DisplayName("no profile, no block: the line is exactly what was sent")
    void noProfileChangesNothing() {
        OrderLineInput sent = line("UN", null);

        assertThat(resolver.resolve(List.of(sent), DeclaredTotals.none(), List.of(), Map.of())).containsExactly(sent);
    }

    private OrderLineInput resolveOne(OrderLineInput line, DeclaredTotals declared) {
        return resolver.resolve(List.of(line), declared, List.of(), Map.of("PLP-571", PROFILE)).getFirst();
    }

    private static OrderLineInput line(String uom, PhysicalBlock block) {
        return new OrderLineInput("PLP-571", "Vaso", new BigDecimal("1000"), uom, null, null, null, block,
                PhysicalSource.ORDER_LINE, null);
    }
}
