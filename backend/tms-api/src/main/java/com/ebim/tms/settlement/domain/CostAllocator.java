package com.ebim.tms.settlement.domain;

import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Splits one trip's cost between the rows of its assignment ledger (V68, ADR-026 sections 2 and 6).
 * Pure: no clock, no repository, so every case is a line in a table test.
 *
 * <h2>No invented allocation</h2>
 *
 * <p>A row whose measure of the basis is unknown - zero, which is what the ledger stores for "not
 * known" (V11) - receives nothing and is counted. The cost goes whole to the measurable rows; when
 * none is measurable, nothing is allocated and the run says so.
 *
 * <h2>Rounding</h2>
 *
 * <p>Each amount is the cost times the row's exact proportion, rounded HALF_UP to cents; shares are
 * rounded to six decimals for display. The remainder of rounding (a few cents at most) goes to the
 * row with the largest proportion - the first of them on a tie, in ledger order - so the amounts add
 * up to the cost exactly.
 */
public final class CostAllocator {

    private static final MathContext EXACT = MathContext.DECIMAL128;

    private CostAllocator() {
    }

    /**
     * One row of the ledger.
     *
     * @param stopKey what puts the row on a stop: its destination. Only {@link CostAllocationBasis#STOPS} reads it
     */
    public record Load(
            UUID assignmentId,
            UUID stopKey,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            BigDecimal pallets,
            BigDecimal floorAreaM2) {

        public Load {
            Objects.requireNonNull(assignmentId, "assignmentId");
            weightKg = orZero(weightKg);
            volumeM3 = orZero(volumeM3);
            pallets = orZero(pallets);
            floorAreaM2 = orZero(floorAreaM2);
        }

        BigDecimal measure(CostAllocationBasis basis) {
            return switch (basis) {
                case WEIGHT, STOPS -> weightKg;
                case EFFECTIVE_VOLUME -> volumeM3;
                case FLOOR_AREA -> floorAreaM2;
                case PALLETS -> pallets;
                case EQUAL -> BigDecimal.ONE;
            };
        }
    }

    /**
     * What one row received.
     *
     * @param basisQuantity the row's measure of the basis (for STOPS, its weight within the stop); null when unmeasured
     * @param share         its part of the cost, six decimals; null when unmeasured
     * @param amount        its part in money; null when unmeasured
     */
    public record Allocation(UUID assignmentId, BigDecimal basisQuantity, BigDecimal share, BigDecimal amount) {

        public boolean allocated() {
            return amount != null;
        }
    }

    /**
     * @param allocations     one per input row, in input order
     * @param allocatedAmount the cost when any row was measured, zero otherwise
     * @param reason          null when every row was measured
     */
    public record Result(List<Allocation> allocations, BigDecimal allocatedAmount, int unallocatedCount,
            UnallocatedReason reason) {

        public Result {
            allocations = List.copyOf(allocations);
        }
    }

    public static Result allocate(BigDecimal cost, CostAllocationBasis basis, List<Load> loads) {
        Objects.requireNonNull(basis, "basis");
        BigDecimal amount = Objects.requireNonNull(cost, "cost").setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("A cost to allocate is never negative: " + amount);
        }
        if (loads.isEmpty()) {
            return new Result(List.of(), BigDecimal.ZERO.setScale(2), 0, UnallocatedReason.NO_ORDERS);
        }

        Map<UUID, BigDecimal> weights = basis == CostAllocationBasis.STOPS ? stopWeights(loads) : measured(loads, basis);
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weights.isEmpty() || total.signum() == 0) {
            List<Allocation> none = loads.stream()
                    .map(load -> new Allocation(load.assignmentId(), null, null, null)).toList();
            return new Result(none, BigDecimal.ZERO.setScale(2), loads.size(), UnallocatedReason.NO_MEASURABLE_ORDER);
        }

        List<Allocation> allocations = new ArrayList<>(loads.size());
        BigDecimal sum = BigDecimal.ZERO;
        int largest = -1;
        BigDecimal largestWeight = null;
        for (Load load : loads) {
            BigDecimal weight = weights.get(load.assignmentId());
            if (weight == null) {
                allocations.add(new Allocation(load.assignmentId(), null, null, null));
                continue;
            }
            BigDecimal proportion = weight.divide(total, EXACT);
            BigDecimal part = amount.multiply(proportion, EXACT).setScale(2, RoundingMode.HALF_UP);
            sum = sum.add(part);
            if (largestWeight == null || weight.compareTo(largestWeight) > 0) {
                largestWeight = weight;
                largest = allocations.size();
            }
            allocations.add(new Allocation(load.assignmentId(), load.measure(basis).stripTrailingZeros(),
                    proportion.setScale(6, RoundingMode.HALF_UP), part));
        }
        BigDecimal remainder = amount.subtract(sum);
        if (remainder.signum() != 0) {
            Allocation top = allocations.get(largest);
            allocations.set(largest, new Allocation(top.assignmentId(), top.basisQuantity(), top.share(),
                    top.amount().add(remainder)));
        }
        int unallocated = loads.size() - weights.size();
        return new Result(allocations, amount, unallocated, unallocated == 0 ? null : UnallocatedReason.NO_BASIS);
    }

    /** Every row with a positive measure, keyed by assignment, in ledger order. */
    private static Map<UUID, BigDecimal> measured(List<Load> loads, CostAllocationBasis basis) {
        Map<UUID, BigDecimal> weights = new LinkedHashMap<>();
        for (Load load : loads) {
            BigDecimal measure = load.measure(basis);
            if (measure.signum() > 0) {
                weights.put(load.assignmentId(), measure);
            }
        }
        return weights;
    }

    /**
     * Equal parts per stop, then by weight within the stop. A stop none of whose rows is weighed
     * takes no part, and a row without weight takes none of its stop's: their cost goes to the
     * stops and rows that can be measured, and they are counted.
     */
    private static Map<UUID, BigDecimal> stopWeights(List<Load> loads) {
        Map<UUID, BigDecimal> stopTotals = new LinkedHashMap<>();
        for (Load load : loads) {
            if (load.weightKg().signum() > 0) {
                stopTotals.merge(stopOf(load), load.weightKg(), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> weights = new LinkedHashMap<>();
        if (stopTotals.isEmpty()) {
            return weights;
        }
        BigDecimal stops = BigDecimal.valueOf(stopTotals.size());
        for (Load load : loads) {
            if (load.weightKg().signum() > 0) {
                BigDecimal withinStop = load.weightKg().divide(stopTotals.get(stopOf(load)), EXACT);
                weights.put(load.assignmentId(), withinStop.divide(stops, EXACT));
            }
        }
        return weights;
    }

    /** A row with no destination is a stop of its own: it is never merged with another unknown. */
    private static UUID stopOf(Load load) {
        return load.stopKey() == null ? load.assignmentId() : load.stopKey();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
