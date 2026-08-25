package com.ebim.tms.integration.application;

import com.ebim.tms.shared.reference.TrackingReport;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One reported position, as it arrives on the wire (API v1).
 *
 * <p>A separate record from {@code shared.reference.TrackingReport} even though the two carry the
 * same nine fields, for the reason {@code ShipmentPublicationPort} states about its own split: this
 * one is a <em>published contract</em> that partners code against and may only change with a
 * version, while the internal record may change whenever tracking's own model does. Collapsing them
 * would make every internal rename a breaking API change.
 *
 * <p>The Bean Validation annotations here are the cheap, field-shaped half of validation - "is this
 * a number in range" - and they run inside {@code IntegrationRequestExecutor} so a rejection is
 * still recorded in the inbox. The half that needs the clock, the company and the shipment
 * (is the time plausible, does the shipment exist, is it on the road) belongs to
 * {@code TrackingIngestionService}, which is the only place it can be answered.
 *
 * @param shipmentNumber the TMS shipment this is about, e.g. {@code SH-00000123} - the identifier a
 *     partner already has from the outbound {@code ShipmentPlan V1} contract and from the
 *     paperwork. Never a uuid: see {@code shared.reference.TrackingIntakePort}
 * @param occurredAt when the device was at this point, with an offset. Not when it was sent - a
 *     ping buffered in a tunnel belongs to the minute it was measured
 * @param speedKph optional, as measured by the device
 * @param headingDegrees optional, {@code [0, 360)}
 * @param externalVehicleReference optional: the sender's own id for the vehicle, kept for
 *     traceability. Never used to resolve the shipment
 * @param correlationReference optional: the sender's own id for this ping, so "we sent it and you
 *     do not have it" is answerable. Send a reference, never a payload - TMS deliberately stores no
 *     raw telematics document
 */
public record TrackingPositionReport(
        @NotBlank(message = "shipmentNumber is required")
        @Size(max = 40, message = "shipmentNumber is at most 40 characters")
        String shipmentNumber,

        @NotNull(message = "occurredAt is required")
        OffsetDateTime occurredAt,

        @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90", message = "latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "latitude must be between -90 and 90")
        BigDecimal latitude,

        @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180", message = "longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "longitude must be between -180 and 180")
        BigDecimal longitude,

        @DecimalMin(value = "0", message = "speedKph must be between 0 and 400")
        @DecimalMax(value = "400", message = "speedKph must be between 0 and 400")
        BigDecimal speedKph,

        @DecimalMin(value = "0", message = "headingDegrees must be at least 0 and less than 360")
        @DecimalMax(value = "360", inclusive = false,
                message = "headingDegrees must be at least 0 and less than 360")
        BigDecimal headingDegrees,

        @Size(max = 128, message = "externalVehicleReference is at most 128 characters")
        String externalVehicleReference,

        @Size(max = 128, message = "correlationReference is at most 128 characters")
        String correlationReference) {

    /**
     * Translates into the internal contract, taking the feed's slug from the batch it arrived in.
     *
     * <p>The provider is on the batch and not on each position because it is a property of the
     * <em>sender</em>, not of a measurement: repeating it two hundred times would let one delivery
     * claim two providers, which is a state nothing downstream has an answer for.
     */
    public TrackingReport toReport(String provider) {
        return new TrackingReport(shipmentNumber, provider, occurredAt, latitude, longitude, speedKph,
                headingDegrees, externalVehicleReference, correlationReference);
    }
}
