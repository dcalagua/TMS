package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What was recorded about one order's delivery, for the published shipment plan v1.1
 * (WAREHOUSE_EXECUTION_V1 §7.2). Absent - not this record with empty fields - until a result is
 * recorded.
 *
 * @param attempted what was taken to the customer (V45), or null when no amounts were recorded.
 *     Null is "not recorded", <b>never zero</b>
 * @param delivered what the customer took, null on the same terms
 * @param refused what the customer refused, null on the same terms
 * @param lines the per-line results (V45 {@code order_delivery_line}), by line number; empty when
 *     the delivery was recorded per order only
 */
public record PublishedShipmentDelivery(
        String result,
        OffsetDateTime deliveredAt,
        String receiverName,
        int evidenceCount,
        Amounts attempted,
        Amounts delivered,
        Amounts refused,
        List<Line> lines) {

    public PublishedShipmentDelivery {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One measure block; a measure inside it may be null when only the others were recorded. */
    public record Amounts(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {

        /** Null when none of the three measures was recorded, so absence is never rendered as zero. */
        public static Amounts ofNullable(BigDecimal weightKg, BigDecimal volumeM3, BigDecimal pallets) {
            return weightKg == null && volumeM3 == null && pallets == null
                    ? null
                    : new Amounts(weightKg, volumeM3, pallets);
        }
    }

    /**
     * One order line's result, in the line's own unit.
     *
     * @param lineNumber the line number the ERP sent with the order
     * @param uom the unit snapshotted on the delivery line when it was recorded
     */
    public record Line(Integer lineNumber, String materialCode, String uom, BigDecimal quantityAttempted,
            BigDecimal quantityDelivered, BigDecimal quantityRefused) {
    }
}
