package com.ebim.tms.planning.application;

import java.math.BigDecimal;

/**
 * How full the range's vehicles actually ran, in the three dimensions the capacity model measures
 * ({@code docs/domain/CAPACITY_MODEL.md}, and
 * {@code docs/domain/KPIS_REPORTING_V1.md} section "Utilisation").
 *
 * <p><b>Summed, then divided - never averaged.</b> Each percentage is the range's whole load over
 * the range's whole capacity, not the mean of the per-shipment percentages. The two differ whenever
 * the fleet is mixed, and only the first answers the question an operations manager is asking: a
 * day with one full van and one empty articulated truck is not 50% utilised, it is however much of
 * the total tonnage went out. Averaging percentages would let a van's 100% cancel a truck's 10%.
 *
 * <p><b>Over the shipments whose limit is on file, and no others.</b> Only trips that carry a
 * capacity snapshot - confirmed or beyond - and only those not cancelled; and per dimension, only
 * those whose snapshot for that dimension is stated. A draft's limit lives on the vehicle master
 * and a cancelled trip never ran, so both would put load over a capacity this figure has no right
 * to claim. {@link #trips} is what makes that visible: it is how many shipments the three
 * percentages are actually about, and a screen showing 82% over eleven of a quarter's four hundred
 * shipments has to be able to say so.
 *
 * @param trips              how many shipments the figures cover
 * @param weightUsedKg       assigned weight, summed
 * @param weightCapacityKg   frozen weight limits, summed over the same shipments
 * @param weightPercent      {@code used / capacity}, or null when no limit was stated
 * @param volumeUsedM3       as {@code weightUsedKg}
 * @param volumeCapacityM3   as {@code weightCapacityKg}
 * @param volumePercent      as {@code weightPercent}
 * @param palletsUsed        as {@code weightUsedKg}
 * @param palletCapacity     as {@code weightCapacityKg}
 * @param palletsPercent     as {@code weightPercent}
 */
public record KpiUtilizationView(
        long trips,
        BigDecimal weightUsedKg,
        BigDecimal weightCapacityKg,
        BigDecimal weightPercent,
        BigDecimal volumeUsedM3,
        BigDecimal volumeCapacityM3,
        BigDecimal volumePercent,
        BigDecimal palletsUsed,
        BigDecimal palletCapacity,
        BigDecimal palletsPercent) {
}
