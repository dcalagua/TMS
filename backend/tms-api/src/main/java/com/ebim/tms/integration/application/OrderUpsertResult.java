package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.IntakeOutcome;
import com.ebim.tms.shared.reference.OrderIntakeResult;
import java.util.UUID;

/**
 * What one order delivery produced: the TMS identity, the human-facing order number an operator
 * will quote back to the partner, the status the order actually ended in, and whether this was a
 * create, an update or a redelivery that changed nothing.
 *
 * <p>{@code status} is the persisted status, not the one the payload asked for. A delivery that
 * set {@code markReadyForPlanning} on an order with no weight, volume or pallets gets
 * {@code NOT_READY} back and can act on it, rather than believing the order is plannable.
 */
public record OrderUpsertResult(UUID id, String orderNumber, String status, IntakeOutcome outcome) {

    public static OrderUpsertResult from(OrderIntakeResult result) {
        return new OrderUpsertResult(result.id(), result.orderNumber(), result.status(), result.outcome());
    }
}
