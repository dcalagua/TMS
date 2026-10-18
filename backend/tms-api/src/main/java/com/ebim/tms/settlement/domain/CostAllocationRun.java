package com.ebim.tms.settlement.domain;

import com.ebim.tms.shared.settings.CostAllocationBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One stored allocation of one trip's cost (V68, ADR-026): {@code tms.trip_cost_allocation_run}.
 * Immutable once written; the next version supersedes it.
 */
public record CostAllocationRun(
        UUID id,
        UUID tripId,
        int version,
        CostAllocationBasis basis,
        AllocationCostSource costSource,
        String currency,
        BigDecimal costAmount,
        BigDecimal allocatedAmount,
        int orderCount,
        int unallocatedOrderCount,
        UnallocatedReason unallocatedReason,
        LocalDate serviceDate,
        String shipmentNumber,
        UUID carrierId,
        UUID routeId,
        String routeLabel,
        OffsetDateTime computedAt,
        UUID computedBy,
        AllocationTrigger trigger,
        OffsetDateTime supersededAt) {

    public boolean current() {
        return supersededAt == null;
    }
}
