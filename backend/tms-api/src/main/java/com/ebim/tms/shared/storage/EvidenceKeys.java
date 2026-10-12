package com.ebim.tms.shared.storage;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The storage key every {@link EvidenceStoragePort} implementation generates and accepts:
 * {@code <company>/<yyyy>/<MM>/<uuid>.<ext>}. One format for every store, so moving the bytes from a
 * volume to Supabase Storage (ADR-020) is a copy, and no row in the database changes.
 *
 * <p>The key is ours: generated here, never derived from anything a client sent. That is what
 * makes path traversal not a class of bug rather than a filter that has to be right.
 */
final class EvidenceKeys {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/heic", "heic",
            "application/pdf", "pdf");

    private static final String DEFAULT_EXTENSION = "bin";

    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/\\d{4}/\\d{2}/"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}$");

    private EvidenceKeys() {
    }

    static String newKey(UUID companyId, String contentType, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        String extension = EXTENSIONS.getOrDefault(
                contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT), DEFAULT_EXTENSION);
        return "%s/%04d/%02d/%s.%s".formatted(
                companyId, today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
    }

    /** Refuses a malformed key, and a key of another tenant - the layer that makes a leaked key useless. */
    static void requireOwned(UUID companyId, String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new EvidenceStorageException("Malformed evidence key");
        }
        if (!storageKey.startsWith(companyId.toString() + "/")) {
            throw new EvidenceStorageException("Evidence key does not belong to this company");
        }
    }
}
