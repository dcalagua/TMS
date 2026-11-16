package com.ebim.tms.iam.inventory.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The opaque {@code nextCursor} of the inventory: the last organization id of the page, versioned and
 * base64url-encoded so that callers treat it as a token and the format can change without a contract change.
 * Keyset pagination: stable while organizations are added or deactivated between pages.
 */
public final class InventoryCursor {

    private static final String PREFIX = "o1:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private InventoryCursor() {}

    public static String encode(UUID lastOrganizationId) {
        return ENCODER.encodeToString((PREFIX + lastOrganizationId).getBytes(StandardCharsets.US_ASCII));
    }

    /** The organization id after which the next page starts; empty for anything this class did not write. */
    public static Optional<UUID> decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > 100) {
            return Optional.empty();
        }
        try {
            String text = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
            if (!text.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String id = text.substring(PREFIX.length());
            UUID uuid = UUID.fromString(id);
            // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical one is ours.
            return uuid.toString().equals(id) ? Optional.of(uuid) : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
