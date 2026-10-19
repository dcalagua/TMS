package com.ebim.tms.shared.reference;

import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which orders are held, asked by {@code planning} without depending on {@code com.ebim.tms.orders}
 * (ADR-014 section 7). {@code orders.application.OrderHoldService} is the only implementation.
 *
 * <p>Read-only for planning. A hold is placed and lifted in the orders module, by a person with
 * {@code orders.hold:manage}; planning only needs to know that one exists - to refuse a dispatch
 * ({@code DispatchReadiness}) and to warn a supervisor (the Control Tower advisory
 * {@code ORDER_HOLD_ON_COMMITTED_TRIP}).
 *
 * <p>The one write, {@link #placeFromIntegration}, is the warehouse's (V61, ADR-025 section 4): a
 * credential may <em>place</em> a hold, never lift one and never cancel the order (D-03).
 */
public interface OrderHoldPort {

    /**
     * One order with at least one active blocking hold.
     *
     * @param holdTypes the types of its active blocking holds, distinct, in the order they were placed
     */
    record HeldOrder(UUID orderId, String orderNumber, List<String> holdTypes) {

        public HeldOrder {
            holdTypes = List.copyOf(holdTypes);
        }
    }

    /**
     * Of {@code orderIds}, the ones with an active blocking hold, in one call. Orders of another
     * company are never returned, whatever ids are asked about.
     */
    Map<UUID, HeldOrder> activeBlockingHolds(Set<UUID> orderIds, UUID companyId);

    /**
     * A hold an integration credential asks for, on the order with the ERP key
     * ({@code externalSource}, {@code externalReference}).
     *
     * @param reasonCode the machine code ({@code WAREHOUSE_CANCELLED}, {@code WAREHOUSE_SHORT_CLOSED});
     *     an active hold with the same code on the order makes the request {@code ALREADY_HELD}
     * @param reason what a person reads, at most 500 characters
     * @param blocking whether it stops release, planning and dispatch
     */
    record IntegrationHoldCommand(UUID integrationClientId, String externalSource, String externalReference,
            String reasonCode, String reason, boolean blocking) {
    }

    /** What happened to an {@link IntegrationHoldCommand}. */
    enum IntegrationHoldOutcome {
        /** A new hold was placed. */
        PLACED,
        /** An active hold with the same reason code already exists; nothing was added. */
        ALREADY_HELD,
        /** The order is delivered or cancelled: there is nothing left to hold. */
        ORDER_CLOSED,
        /** No order with that key in this company. */
        UNKNOWN_ORDER
    }

    /**
     * @param orderId null only for {@code UNKNOWN_ORDER}
     * @param holdId the hold placed, or the active one that made it {@code ALREADY_HELD}; null otherwise
     * @param onCommittedTrip whether the order is on a trip (the hold then blocks its dispatch)
     */
    record IntegrationHoldResult(IntegrationHoldOutcome outcome, UUID orderId, String orderNumber, UUID holdId,
            boolean onCommittedTrip) {
    }

    /**
     * Places a hold on behalf of an integration credential, in the caller's transaction, taking the
     * order's row lock as an operator hold does. Audited as {@code ORDER_HOLD_PLACED} with
     * {@code source=INTEGRATION}. Never changes the order's status.
     */
    IntegrationHoldResult placeFromIntegration(CompanyScope scope, IntegrationHoldCommand command);
}
