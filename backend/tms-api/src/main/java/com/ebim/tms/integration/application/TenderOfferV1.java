package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.CarrierTenderOffer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One offer on the wire, API version 1 (migration V31).
 *
 * <p>A record of its own rather than serialising {@link CarrierTenderOffer} directly, for the reason
 * {@code ShipmentPlanHeaderV1} exists beside {@code PublishedShipment}: the port's record is an
 * internal contract between two modules and may be reshaped whenever both agree, while this one is
 * published to somebody else's ERP and its field names are a promise. Keeping them separate is what
 * makes an internal refactor stop being a breaking change.
 *
 * @param shipmentNumber the only identity in this contract. A carrier never learns a TMS uuid - the
 *     same rule the tracking and outbound shipment APIs follow
 * @param status {@code SENT} while it is open, or the answer it has already received. Resolved
 *     against the deadline before it is written, so a client never sees {@code SENT} on an offer TMS
 *     would refuse to accept
 * @param expiresAt when the offer lapses, or null when it has no deadline. A carrier that answers
 *     after this gets 409
 * @param stopCount how many deliveries the run has - the coarsest honest measure of the job, and
 *     the only one that does not disclose who the deliveries are to
 */
public record TenderOfferV1(
        String shipmentNumber,
        int attempt,
        String status,
        LocalDate planningDate,
        OffsetDateTime plannedDepartureAt,
        String originCode,
        String originName,
        int stopCount,
        BigDecimal offeredAmount,
        String currency,
        String notes,
        OffsetDateTime sentAt,
        OffsetDateTime expiresAt,
        OffsetDateTime respondedAt,
        String responseNotes) {

    public static TenderOfferV1 from(CarrierTenderOffer offer) {
        return new TenderOfferV1(
                offer.shipmentNumber(),
                offer.attempt(),
                offer.status(),
                offer.planningDate(),
                offer.plannedDepartureAt(),
                offer.originCode(),
                offer.originName(),
                offer.stopCount(),
                offer.offeredAmount(),
                offer.currency(),
                offer.notes(),
                offer.sentAt(),
                offer.expiresAt(),
                offer.respondedAt(),
                offer.responseNotes());
    }
}
