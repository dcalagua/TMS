package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.TrackingLink;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One tracking link as the office sees it in the order drawer (ADR-029). Never the token, never its
 * hash: once generated, a link can be revoked from here but not copied again - a new one is generated.
 *
 * @param state {@code ACTIVE}, {@code EXPIRED} or {@code REVOKED}, judged against the effective expiry
 * @param expiresAt the effective expiry: the earlier of the hard cap and seven days after the order closed
 */
public record TrackingLinkView(
        UUID id,
        String state,
        OffsetDateTime createdAt,
        UUID createdBy,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        String revokeReason,
        OffsetDateTime lastViewedAt,
        long viewCount,
        long version) {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String REVOKED = "REVOKED";

    static TrackingLinkView of(TrackingLink link, OffsetDateTime effectiveExpiry, OffsetDateTime now) {
        String state = link.isRevoked() ? REVOKED : effectiveExpiry.isAfter(now) ? ACTIVE : EXPIRED;
        return new TrackingLinkView(link.id(), state, link.createdAt(), link.createdBy(), effectiveExpiry,
                link.revokedAt(), link.revokeReason(), link.lastViewedAt(), link.viewCount(), link.version());
    }
}
