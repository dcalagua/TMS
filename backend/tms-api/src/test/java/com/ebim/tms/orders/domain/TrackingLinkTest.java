package com.ebim.tms.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-029 T3: expiry and revocation of one link. */
class TrackingLinkTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @Test
    @DisplayName("stores the token's hash, never the token")
    void storesTheHash() {
        TrackingToken token = TrackingToken.generate();
        TrackingLink link = issue(token, NOW.plusDays(30));

        assertThat(link.tokenHash()).isEqualTo(token.hash()).isNotEqualTo(token.value());
    }

    @Test
    @DisplayName("open order: the hard cap; closed order: seven days after closing, unless the cap comes first")
    void effectiveExpiry() {
        TrackingLink link = issue(TrackingToken.generate(), NOW.plusDays(30));

        assertThat(link.effectiveExpiry(null, SEVEN_DAYS)).isEqualTo(NOW.plusDays(30));
        assertThat(link.effectiveExpiry(NOW.plusDays(2), SEVEN_DAYS)).isEqualTo(NOW.plusDays(9));
        assertThat(link.effectiveExpiry(NOW.plusDays(28), SEVEN_DAYS)).isEqualTo(NOW.plusDays(30));
    }

    @Test
    @DisplayName("revoked once, and only once: the first revocation is the one that is kept")
    void revokedOnce() {
        TrackingLink link = issue(TrackingToken.generate(), NOW.plusDays(30));
        UUID who = UUID.randomUUID();

        link.revoke("sent to the wrong customer", who, NOW.plusHours(1));
        assertThat(link.isRevoked()).isTrue();
        assertThatThrownBy(() -> link.revoke("again", UUID.randomUUID(), NOW.plusHours(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(link.revokedBy()).isEqualTo(who);
        assertThat(link.revokeReason()).isEqualTo("sent to the wrong customer");
    }

    @Test
    @DisplayName("refuses a link without an author or that expires before it exists")
    void refusesNonsense() {
        assertThatThrownBy(() -> TrackingLink.issue(UUID.randomUUID(), UUID.randomUUID(), TrackingToken.generate(),
                NOW, NOW.plusDays(1), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(TrackingToken.generate(), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static TrackingLink issue(TrackingToken token, OffsetDateTime expiresAt) {
        return TrackingLink.issue(UUID.randomUUID(), UUID.randomUUID(), token, NOW, expiresAt, UUID.randomUUID());
    }
}
