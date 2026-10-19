package com.ebim.tms.settlement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebim.tms.settlement.domain.CostAllocator.Allocation;
import com.ebim.tms.settlement.domain.CostAllocator.Load;
import com.ebim.tms.settlement.domain.CostAllocator.Result;
import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The allocation table of ADR-026 sections 2 and 6: every basis, missing measures, rounding. */
class CostAllocatorTest {

    private static final UUID STOP_1 = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID STOP_2 = UUID.fromString("00000000-0000-4000-8000-000000000002");

    /** weight, volume, pallets, floor, stop. */
    private static Load load(String weight, String volume, String pallets, String floor, UUID stop) {
        return new Load(UUID.randomUUID(), stop, new BigDecimal(weight), new BigDecimal(volume),
                new BigDecimal(pallets), new BigDecimal(floor));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // basis, cost, loads, expected amounts (null = received nothing), unallocated, reason
                Arguments.of("weight splits by kilograms", CostAllocationBasis.WEIGHT, "1000.00",
                        List.of(load("300", "1", "1", "1", STOP_1), load("700", "9", "1", "1", STOP_1)),
                        amounts("300.00", "700.00"), 0, null),
                Arguments.of("effective volume splits by planning m3", CostAllocationBasis.EFFECTIVE_VOLUME, "1000.00",
                        List.of(load("300", "1", "1", "1", STOP_1), load("700", "3", "1", "1", STOP_1)),
                        amounts("250.00", "750.00"), 0, null),
                Arguments.of("floor area splits by m2", CostAllocationBasis.FLOOR_AREA, "900.00",
                        List.of(load("1", "1", "1", "0.5", STOP_1), load("1", "1", "1", "1", STOP_1)),
                        amounts("300.00", "600.00"), 0, null),
                Arguments.of("pallets split by pallets", CostAllocationBasis.PALLETS, "600.00",
                        List.of(load("1", "1", "2", "1", STOP_1), load("1", "1", "4", "1", STOP_1)),
                        amounts("200.00", "400.00"), 0, null),
                Arguments.of("equal parts per row, whatever they carry", CostAllocationBasis.EQUAL, "900.00",
                        List.of(load("0", "0", "0", "0", STOP_1), load("10", "1", "1", "1", STOP_1),
                                load("999", "9", "9", "9", STOP_2)),
                        amounts("300.00", "300.00", "300.00"), 0, null),
                Arguments.of("stops: half each, then by weight within the stop", CostAllocationBasis.STOPS, "1000.00",
                        List.of(load("100", "1", "1", "1", STOP_1), load("300", "1", "1", "1", STOP_1),
                                load("5", "1", "1", "1", STOP_2)),
                        amounts("125.00", "375.00", "500.00"), 0, null),
                Arguments.of("an order without weight receives nothing and is counted", CostAllocationBasis.WEIGHT,
                        "1000.00",
                        List.of(load("400", "1", "1", "1", STOP_1), load("0", "5", "1", "1", STOP_1),
                                load("600", "1", "1", "1", STOP_2)),
                        amounts("400.00", null, "600.00"), 1, UnallocatedReason.NO_BASIS),
                Arguments.of("stops: a stop with no weighed order takes no part", CostAllocationBasis.STOPS, "800.00",
                        List.of(load("100", "1", "1", "1", STOP_1), load("0", "1", "1", "1", STOP_2)),
                        amounts("800.00", null), 1, UnallocatedReason.NO_BASIS),
                Arguments.of("nobody measurable: nothing allocated", CostAllocationBasis.FLOOR_AREA, "500.00",
                        List.of(load("100", "1", "1", "0", STOP_1), load("100", "1", "1", "0", STOP_2)),
                        amounts(null, null), 2, UnallocatedReason.NO_MEASURABLE_ORDER),
                Arguments.of("a zero cost allocates zeros", CostAllocationBasis.WEIGHT, "0",
                        List.of(load("1", "1", "1", "1", STOP_1), load("3", "1", "1", "1", STOP_1)),
                        amounts("0.00", "0.00"), 0, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void table(String name, CostAllocationBasis basis, String cost, List<Load> loads, List<String> expected,
            int unallocated, UnallocatedReason reason) {
        Result result = CostAllocator.allocate(new BigDecimal(cost), basis, loads);

        assertThat(result.allocations()).hasSize(loads.size());
        for (int i = 0; i < loads.size(); i++) {
            Allocation allocation = result.allocations().get(i);
            assertThat(allocation.assignmentId()).isEqualTo(loads.get(i).assignmentId());
            if (expected.get(i) == null) {
                assertThat(allocation.allocated()).isFalse();
                assertThat(allocation.share()).isNull();
                assertThat(allocation.basisQuantity()).isNull();
            } else {
                assertThat(allocation.amount()).isEqualByComparingTo(expected.get(i));
            }
        }
        assertThat(result.unallocatedCount()).isEqualTo(unallocated);
        assertThat(result.reason()).isEqualTo(reason);
        BigDecimal sum = result.allocations().stream().filter(Allocation::allocated).map(Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(result.allocatedAmount());
    }

    @Test
    @DisplayName("no orders: nothing to split, and it says so")
    void noOrders() {
        Result result = CostAllocator.allocate(new BigDecimal("100"), CostAllocationBasis.WEIGHT, List.of());
        assertThat(result.allocations()).isEmpty();
        assertThat(result.allocatedAmount()).isEqualByComparingTo("0");
        assertThat(result.reason()).isEqualTo(UnallocatedReason.NO_ORDERS);
    }

    @Test
    @DisplayName("rounding: three equal thirds of 100.00 add up to 100.00, the cent to the first largest")
    void roundingRemainderGoesToTheLargestShare() {
        List<Load> loads = List.of(load("1", "1", "1", "1", STOP_1), load("1", "1", "1", "1", STOP_1),
                load("1", "1", "1", "1", STOP_1));
        Result result = CostAllocator.allocate(new BigDecimal("100.00"), CostAllocationBasis.WEIGHT, loads);

        assertThat(result.allocations()).extracting(Allocation::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
        assertThat(result.allocations()).extracting(Allocation::share)
                .usingElementComparator(BigDecimal::compareTo)
                .containsOnly(new BigDecimal("0.333333"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CostAllocationBasis.class)
    @DisplayName("rounding: awkward weights and an awkward cost always sum exactly, for every basis")
    void sumIsExactForEveryBasis(CostAllocationBasis basis) {
        List<Load> loads = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            loads.add(load(String.valueOf(i * 13.7), String.valueOf(i * 0.31), String.valueOf(i),
                    String.valueOf(i * 0.77), i % 3 == 0 ? STOP_1 : STOP_2));
        }
        Result result = CostAllocator.allocate(new BigDecimal("1234.57"), basis, loads);

        BigDecimal sum = result.allocations().stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("1234.57");
        assertThat(result.allocations()).allSatisfy(allocation -> {
            assertThat(allocation.amount().scale()).isEqualTo(2);
            assertThat(allocation.share().scale()).isEqualTo(6);
        });
    }

    @Test
    @DisplayName("a negative cost is refused")
    void negativeCost() {
        assertThatThrownBy(() -> CostAllocator.allocate(new BigDecimal("-1"), CostAllocationBasis.WEIGHT,
                List.of(load("1", "1", "1", "1", STOP_1)))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> amounts(String... values) {
        return java.util.Arrays.asList(values);
    }
}
