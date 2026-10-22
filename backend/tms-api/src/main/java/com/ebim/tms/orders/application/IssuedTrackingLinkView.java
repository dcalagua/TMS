package com.ebim.tms.orders.application;

/**
 * A link just generated: the only response that ever carries the raw token (ADR-029 T2).
 *
 * @param token the secret - shown once, never stored, never logged
 * @param path the frontend path of the public page, {@code /t/<token>}; the office builds the absolute
 *     URL from its own origin, which is the one the customer's phone will open
 */
public record IssuedTrackingLinkView(TrackingLinkView link, String token, String path) {

    @Override
    public String toString() {
        return "IssuedTrackingLinkView[link=" + link.id() + ", token=redacted]";
    }
}
