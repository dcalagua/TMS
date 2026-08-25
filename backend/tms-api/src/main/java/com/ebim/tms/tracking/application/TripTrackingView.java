package com.ebim.tms.tracking.application;

import java.util.List;

/**
 * Where one shipment is, as far as TMS knows.
 *
 * <p>The three "no position" cases are deliberately distinguishable from this document alone,
 * because a dispatcher does something different about each and a single empty field would tell
 * them which of the three it is: nothing.
 *
 * <ul>
 *   <li>{@code trackable} false - the shipment has not left, or it is cancelled. Nothing to do;
 *       the status already explains it.</li>
 *   <li>{@code trackable} true, {@code lastPosition} null, {@code providerConfigured} false - this
 *       deployment has no feed. Somebody's job, but not today's dispatcher's.</li>
 *   <li>{@code trackable} true, {@code lastPosition} null, {@code providerConfigured} true - there
 *       is a feed and it has said nothing about this shipment. That is the one worth a phone
 *       call.</li>
 * </ul>
 *
 * @param providerConfigured whether this deployment can obtain positions at all - either a
 *     configured pull provider ({@code TrackingProviderPort}) or a feed that has already reported
 *     something. False means "no tracking here", never "nothing yet"
 * @param lastPosition the newest position, whichever feed measured it, or null
 * @param track the recent trail, oldest first so a map can draw it directly, bounded by
 *     {@link TrackingProperties#trackLimit()}. Empty rather than null when there is nothing
 */
public record TripTrackingView(
        String shipmentNumber,
        String status,
        boolean trackable,
        boolean providerConfigured,
        String vehicleCode,
        String vehicleLicensePlate,
        TrackingPositionView lastPosition,
        List<TrackingPositionView> track) {

    public TripTrackingView {
        track = track == null ? List.of() : List.copyOf(track);
    }
}
