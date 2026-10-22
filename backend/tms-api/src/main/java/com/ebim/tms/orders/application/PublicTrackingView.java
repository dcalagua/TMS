package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.CustomerTrackingStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything a customer tracking link publishes (ADR-029 decision 4, T5) - and, by omission, the list of
 * what it does not: no prices or amounts, no weights or volumes, no other order, no address beyond the
 * destination's name, no driver, no phone, no photo or signature, and no internal id of any kind.
 *
 * <p>The shape is fixed by a test that compares the JSON keys, so adding a field here is a deliberate
 * disclosure decision and fails that test until it is made on purpose.
 *
 * @param reference the sending system's reference for the order (else the customer's own), or null
 * @param vehiclePlate masked or null, per {@code company_settings.tracking_plate_display}
 * @param timeZone the company's zone, so the page shows times as the operation counts them
 * @param expiresAt when this link stops working
 */
public record PublicTrackingView(
        String companyName,
        String orderNumber,
        String reference,
        String destinationName,
        CustomerTrackingStatus status,
        List<Step> timeline,
        ArrivalWindow arrivalWindow,
        String carrierName,
        String vehiclePlate,
        ProofOfDelivery proofOfDelivery,
        String timeZone,
        OffsetDateTime expiresAt,
        OffsetDateTime generatedAt) {

    public PublicTrackingView {
        timeline = List.copyOf(timeline);
    }

    /**
     * @param code RECEIVED, PLANNED, IN_TRANSIT, ARRIVED or OUTCOME
     * @param state DONE or PENDING
     * @param outcome on the OUTCOME step once reached: DELIVERED, PARTIALLY_DELIVERED, REJECTED,
     *     NOT_DELIVERED or CANCELLED
     */
    public record Step(String code, String state, OffsetDateTime at, CustomerTrackingStatus outcome) {
    }

    /** @param basis ESTIMATE (around ADR-011's estimate) or SERVICE_WINDOW (the stop's window) */
    public record ArrivalWindow(OffsetDateTime from, OffsetDateTime to, String basis) {
    }

    /** @param receiverName first name and initial only */
    public record ProofOfDelivery(String receiverName, OffsetDateTime deliveredAt) {
    }
}
