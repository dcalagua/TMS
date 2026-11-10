package com.ebim.tms.planning.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.GeoPoint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The body of the carrier portal's «Llegué» (V75): {@link TripStopExecutionRequest} plus the
 * phone's position at the moment of the tap.
 *
 * <p><b>Its own record, and only on the portal.</b> The office's arrival keeps
 * {@link TripStopExecutionRequest}: a dispatcher typing an arrival at a desk has no position worth
 * recording, and accepting one there would put the office's location on a stop's timeline.
 *
 * <p>The position is optional and both-or-neither. A browser that was refused permission, has no
 * fix, or timed out sends neither, and the arrival is recorded exactly as before - a position
 * informs the person reading the timeline and never decides anything (ADR-007).
 *
 * @param latitude WGS-84 degrees of the phone, or null
 * @param longitude WGS-84 degrees of the phone, or null
 */
public record PortalArrivalRequest(
        OffsetDateTime occurredAt,
        @Size(max = 500, message = "must be at most 500 characters") String notes,
        @DecimalMin(value = "-90", message = "latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "latitude must be between -90 and 90")
        BigDecimal latitude,
        @DecimalMin(value = "-180", message = "longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "longitude must be between -180 and 180")
        BigDecimal longitude) {

    /** The transition body the office service takes. */
    public TripStopExecutionRequest toStopRequest() {
        return new TripStopExecutionRequest(occurredAt, notes);
    }

    /**
     * Where the phone was, or null when it sent nothing. Half a coordinate is refused rather than
     * dropped: it can only come from a broken client, and silently recording no position would hide
     * that.
     */
    public GeoPoint position() {
        if ((latitude == null) != (longitude == null)) {
            throw new InvalidRequestException("latitude and longitude go together: send both or neither.");
        }
        return GeoPoint.of(latitude, longitude);
    }
}
