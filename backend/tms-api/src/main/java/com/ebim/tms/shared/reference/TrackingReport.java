package com.ebim.tms.shared.reference;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One reported position, in TMS's own vocabulary rather than any provider's.
 *
 * <p><b>This record is the abstraction.</b> Everything else in tracking - the table, the intake
 * rules, the screen - is written against these nine fields, so a provider is only ever a
 * translation into them (ADR-007). A vendor that reports {@code {"lat", "lon", "ts", "spd"}} and
 * one that reports {@code {"position": {"y", "x"}, "recordedAtUtc"}} both become this, at the edge,
 * and nothing downstream learns which one it was.
 *
 * <p>Nine fields and not nineteen. Every telematics API on the market offers odometer readings,
 * fuel level, engine hours, harsh-braking counters, tacho status and driver-behaviour scores; none
 * of them answers a transport question TMS asks, several are personal data about an employee, and a
 * field carried "because the provider sends it" is a field somebody eventually builds a screen on.
 * What TMS needs to answer "where is the delivery" is a point, a time and a way to trace it back.
 *
 * @param shipmentNumber the trip this is about, named the way a partner already knows it - see
 *     {@link TrackingIntakePort} for why this is not a uuid
 * @param provider a lowercase slug identifying the feed. A label on the data and never an
 *     authority: the tenant comes from the authenticated credential
 * @param occurredAt when the device was at this point, not when TMS heard about it
 * @param speedKph as measured by the device, or null. Never derived by TMS from two points - a
 *     computed speed and a measured one look identical once stored and are not the same claim
 * @param headingDegrees the bearing in {@code [0, 360)}, or null
 * @param externalVehicleReference the provider's own id for the vehicle, kept for traceability
 *     while a fleet register is being reconciled. Never used to resolve the trip
 * @param correlationReference the provider's own id for this ping, so "we sent it and you do not
 *     have it" is answerable. A reference, never a payload: a raw telematics document carries the
 *     driver's identity and their movements off shift, which TMS has no purpose for
 */
public record TrackingReport(
        String shipmentNumber,
        String provider,
        OffsetDateTime occurredAt,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKph,
        BigDecimal headingDegrees,
        String externalVehicleReference,
        String correlationReference) {
}
