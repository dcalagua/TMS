package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CostAllocationLine;
import com.ebim.tms.settlement.domain.CostAllocationRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A trip's allocation (V68, ADR-026): the current run with what each order received, and every
 * earlier version - recalculating never overwrites, so they are all still here.
 *
 * @param current  null when the trip was never allocated
 * @param versions every run, newest first, without their rows
 */
public record CostAllocationView(UUID tripId, RunView current, List<RunView> versions) {

    public record RunView(
            UUID id,
            int version,
            String basis,
            String costSource,
            String currency,
            BigDecimal costAmount,
            BigDecimal allocatedAmount,
            int orderCount,
            int unallocatedOrderCount,
            String unallocatedReason,
            LocalDate serviceDate,
            String shipmentNumber,
            String routeLabel,
            String trigger,
            OffsetDateTime computedAt,
            UUID computedBy,
            OffsetDateTime supersededAt,
            List<LineView> lines) {

        static RunView of(CostAllocationRun run, List<CostAllocationLine> lines) {
            return new RunView(run.id(), run.version(), run.basis().name(), run.costSource().name(), run.currency(),
                    run.costAmount(), run.allocatedAmount(), run.orderCount(), run.unallocatedOrderCount(),
                    run.unallocatedReason() == null ? null : run.unallocatedReason().name(), run.serviceDate(),
                    run.shipmentNumber(), run.routeLabel(), run.trigger().name(), run.computedAt(), run.computedBy(),
                    run.supersededAt(), lines.stream().map(LineView::of).toList());
        }
    }

    /** One order's part. {@code allocatedAmount} null with a {@code reason}: it could not be measured. */
    public record LineView(
            UUID orderId,
            String orderNumber,
            String customerLabel,
            String destinationLabel,
            BigDecimal weightKg,
            BigDecimal volumeM3,
            BigDecimal pallets,
            BigDecimal floorAreaM2,
            BigDecimal basisQuantity,
            BigDecimal share,
            BigDecimal allocatedAmount,
            String reason) {

        static LineView of(CostAllocationLine line) {
            return new LineView(line.orderId(), line.orderNumber(), line.customerLabel(), line.destinationLabel(),
                    line.weightKg(), line.volumeM3(), line.pallets(), line.floorAreaM2(), line.basisQuantity(),
                    line.share(), line.allocatedAmount(), line.reason());
        }
    }
}
