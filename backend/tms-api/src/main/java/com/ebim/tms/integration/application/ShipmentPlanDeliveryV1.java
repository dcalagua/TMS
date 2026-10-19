package com.ebim.tms.integration.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Plan v1.1's {@code orders[].delivery} block (WAREHOUSE_EXECUTION_V1 §7.2): the delivery result of
 * one order, with the amounts and the per-line results migration V45 records. The flat v1 fields on
 * {@link ShipmentPlanOrderV1} stay; this is the same fact with the detail they cannot carry.
 *
 * <p>The block itself is null until a result is recorded. Inside it, {@code attempted},
 * {@code delivered} and {@code refused} are each null when no amounts were recorded - <b>never zero
 * for unknown</b> - and {@code lines} is empty when the result was recorded per order only.
 * Evidence is a count only: no URL, no bytes, no receiver identity document.
 *
 * @param result {@code DELIVERED}, {@code PARTIAL}, {@code REJECTED}, {@code FAILED} or
 *     {@code NOT_ATTEMPTED} (V28)
 */
public record ShipmentPlanDeliveryV1(
        String result,
        OffsetDateTime deliveredAt,
        String receiverName,
        int evidenceCount,
        Amounts attempted,
        Amounts delivered,
        Amounts refused,
        List<Line> lines) {

    /** One measure block of the order: what was attempted, delivered or refused. */
    public record Amounts(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {
    }

    /**
     * One order line's result, in the line's own unit.
     *
     * @param lineNumber the ERP's line number, as sent with the order
     */
    public record Line(Integer lineNumber, String materialCode, String uom, BigDecimal quantityAttempted,
            BigDecimal quantityDelivered, BigDecimal quantityRefused) {
    }
}
