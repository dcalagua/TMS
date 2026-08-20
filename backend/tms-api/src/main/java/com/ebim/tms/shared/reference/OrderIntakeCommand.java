package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * One transport order as a sending system describes it.
 *
 * <p>Origin and destination arrive as {@code code}s, not uuids: an ERP or a WMS knows its own
 * store and warehouse codes and has no reason to learn ours. They are resolved through the
 * company-scoped lookup ports, so a payload naming another tenant's code sees exactly what a
 * payload naming a nonexistent one sees - the same rule the bulk import follows.
 *
 * <p>{@code externalSource} + {@code externalReference} is mandatory here, unlike on the manual
 * API where it is optional. It is the pair {@code uq_transport_order_external} indexes, and
 * without it a redelivery would have no identity to be idempotent about; the integration API
 * therefore refuses an order it could not recognise a second time.
 *
 * <p>{@code priority} is the plain code rather than {@code OrderPriority} for the module-boundary
 * reason {@link PlannableOrder} documents.
 *
 * @param markReadyForPlanning whether a newly created or updated order should be promoted out of
 *     {@code NOT_READY}. False by default so a partner that streams drafts keeps control of when
 *     the order becomes plannable; the promotion still runs the same completeness check the UI
 *     does and refuses an order with no weight, volume or pallets
 */
public record OrderIntakeCommand(
        String externalSource,
        String externalReference,
        String originCode,
        String destinationCode,
        String customerName,
        String customerReference,
        LocalDate serviceDate,
        String priority,
        LocalTime requestedWindowStart,
        LocalTime requestedWindowEnd,
        BigDecimal declaredWeightKg,
        BigDecimal declaredVolumeM3,
        BigDecimal declaredPallets,
        List<Line> lines,
        boolean markReadyForPlanning) {

    public OrderIntakeCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One requested line; mirrors {@code OrderRequest.OrderLineRequest} without its types. */
    public record Line(
            String materialCode,
            String materialDescription,
            BigDecimal quantity,
            String uom,
            BigDecimal unitWeightKg,
            BigDecimal unitVolumeM3,
            BigDecimal palletQuantity) {
    }
}
