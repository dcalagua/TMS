package com.ebim.tms.tracking.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One position as a screen reads it.
 *
 * <p>Carries {@code receivedAt} beside {@code occurredAt} for the reason the timeline carries
 * {@code recordedAt} beside {@code eventTime}: the gap between them is feed latency, and it is the
 * first thing worth knowing when a map looks wrong. A single timestamp would make a stale feed and
 * a stationary vehicle indistinguishable.
 *
 * <p>No {@code correlationReference}. It exists so support can answer "we sent it and you do not
 * have it" against the database; a dispatcher has no use for a vendor's message id, and a field on
 * a screen is a field somebody eventually asks to search by.
 */
public record TrackingPositionView(
        UUID id,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal speedKph,
        BigDecimal headingDegrees,
        String provider,
        String externalVehicleReference) {
}
